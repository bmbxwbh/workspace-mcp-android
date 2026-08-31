package workspacemcp.app.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import workspacemcp.app.AppRuntime
import workspacemcp.app.domain.WorkspaceController
import workspacemcp.app.mcp.WorkspaceTool
import workspacemcp.app.mcp.createWorkspaceTools

class McpServerHandle(
    val port: Int,
    private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
) {
    fun stop() {
        server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    }
}

private const val SERVER_NAME = "workspace-mcp-server"
private const val SERVER_VERSION = "0.1.0"
private const val PROTOCOL_VERSION = "2025-06-18"

/**
 * 全宽容 MCP HTTP 服务器 (不使用官方 SDK 传输层, 参考 SOMCP 的做法):
 * - POST /mcp (/rpc, /messages 别名): 任意 Accept/Content-Type, JSON-RPC 请求直接返回 JSON 响应
 * - GET /mcp: 服务器发现信息; Accept 含 text/event-stream 时返回 SSE 兼容 hello
 * - 通知 (无 id 或 notifications/*) 不回复, 返回 202
 * - 鉴权: Bearer 头或 ?token= 查询参数; 首页与健康检查免鉴权
 * - CORS 全开放, 浏览器客户端可直接连接
 */
fun startMcpServer(runtime: AppRuntime): McpServerHandle {
    val controller = WorkspaceController(runtime)
    val tools = createWorkspaceTools(controller).associateBy { it.name }
    val port = runtime.settings.port()
    val token = runtime.settings.token()

    val server = embeddedServer(CIO, host = "0.0.0.0", port = port) {
        installPermissiveCorsAndAuth(token)

        routing {
            get("/") {
                call.respondText(
                    "$SERVER_NAME is running. Endpoints: /mcp (POST JSON-RPC), /sse (SSE hello), /health",
                    ContentType.Text.Plain,
                )
            }
            get("/health") {
                call.respondText(
                    buildJsonObject {
                        put("ok", true)
                        put("server", SERVER_NAME)
                        put("endpoint", "/mcp")
                    }.toString(),
                    ContentType.Application.Json,
                )
            }
            get("/.well-known/mcp") { call.respondText(discoveryJson(), ContentType.Application.Json) }
            get("/mcp") {
                val accept = call.request.header(HttpHeaders.Accept).orEmpty()
                if (accept.contains("text/event-stream")) {
                    call.respondText(sseHello(), ContentType.Text.EventStream)
                } else {
                    call.respondText(discoveryJson(), ContentType.Application.Json)
                }
            }
            get("/sse") { call.respondText(sseHello(), ContentType.Text.EventStream) }
            post("/mcp") { handleJsonRpcPost(call, tools) }
            post("/rpc") { handleJsonRpcPost(call, tools) }
            post("/messages") { handleJsonRpcPost(call, tools) }
            delete("/mcp") { call.respond(HttpStatusCode.OK) }
        }
    }
    server.start(wait = false)
    return McpServerHandle(port, server)
}

/** 全宽容 CORS + 可选 token 鉴权, 浏览器客户端的关键兼容层 */
private fun Application.installPermissiveCorsAndAuth(token: String?) {
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT, DELETE, OPTIONS")
        call.response.header(
            HttpHeaders.AccessControlAllowHeaders,
            "Content-Type, Authorization, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID",
        )
        call.response.header(HttpHeaders.AccessControlExposeHeaders, "Mcp-Session-Id")

        if (call.request.httpMethod == HttpMethod.Options) {
            call.respond(HttpStatusCode.OK)
            finish()
            return@intercept
        }

        if (token != null && call.request.path() != "/" && call.request.path() != "/health") {
            val bearer = call.request.headers[HttpHeaders.Authorization]
                ?.removePrefix("Bearer")?.trim()
            val queryToken = call.request.queryParameters["token"]
            if (bearer != token && queryToken != token) {
                call.respondText("Unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                finish()
            }
        }
    }
}

// ===== JSON-RPC 分发 =====

private suspend fun handleJsonRpcPost(call: ApplicationCall, tools: Map<String, WorkspaceTool>) {
    val body = call.receiveText()
    val payload = dispatchBody(body, tools)
    if (payload == null) {
        // 通知或空批次: 按 JSON-RPC 规范不返回 body
        call.respond(HttpStatusCode.Accepted)
    } else {
        call.respondText(payload, ContentType.Application.Json)
    }
}

/** @return 序列化的 JSON-RPC 响应; null 表示无需回复 (通知) */
private suspend fun dispatchBody(body: String, tools: Map<String, WorkspaceTool>): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return errorJson(null, -32700, "Parse error")
    val element = try {
        Json.parseToJsonElement(trimmed)
    } catch (_: IllegalArgumentException) {
        return errorJson(null, -32700, "Parse error")
    }
    return if (element is JsonArray) {
        if (element.isEmpty()) {
            errorJson(null, -32600, "Invalid Request")
        } else {
            val responses = element.mapNotNull { dispatchMessage(it, tools) }
            if (responses.isEmpty()) null else JsonArray(responses).toString()
        }
    } else {
        dispatchMessage(element, tools)?.toString()
    }
}

private suspend fun dispatchMessage(element: JsonElement, tools: Map<String, WorkspaceTool>): JsonObject? {
    val obj = element as? JsonObject ?: return errorObj(null, -32600, "Invalid Request")
    val id = obj["id"]
    val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
    if (method.isNullOrBlank()) return null // 无 method: 客户端对服务器请求的响应, 忽略
    if (id == null || method.startsWith("notifications/")) return null // 通知不回复

    val params = obj["params"] as? JsonObject
    val result: JsonObject = when (method) {
        "initialize" -> initializeResult(params)
        "ping" -> buildJsonObject { }
        "tools/list" -> toolsListResult(tools)
        "tools/call" -> return toolCallResult(id, params, tools)
        "resources/list" -> buildJsonObject { put("resources", JsonArray(emptyList())) }
        "prompts/list" -> buildJsonObject { put("prompts", JsonArray(emptyList())) }
        else -> return errorObj(id, -32601, "Method not found: $method")
    }
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }
}

private fun initializeResult(params: JsonObject?): JsonObject = buildJsonObject {
    val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
    put("protocolVersion", requested ?: PROTOCOL_VERSION)
    putJsonObject("capabilities") {
        putJsonObject("tools") { put("listChanged", false) }
    }
    putJsonObject("serverInfo") {
        put("name", SERVER_NAME)
        put("version", SERVER_VERSION)
    }
}

private fun toolsListResult(tools: Map<String, WorkspaceTool>): JsonObject = buildJsonObject {
    put("tools", JsonArray(tools.values.map { tool ->
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            putJsonObject("inputSchema") {
                put("type", "object")
                put("properties", tool.properties)
                if (tool.required.isNotEmpty()) {
                    put("required", JsonArray(tool.required.map { JsonPrimitive(it) }))
                }
            }
        }
    }))
}

private suspend fun toolCallResult(
    id: JsonElement,
    params: JsonObject?,
    tools: Map<String, WorkspaceTool>,
): JsonObject {
    val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
    if (name.isNullOrBlank()) return errorObj(id, -32602, "Missing tool name")
    val tool = tools[name] ?: return errorObj(id, -32602, "Unknown tool: $name")
    val args = params?.get("arguments") as? JsonObject

    val text = try {
        tool.handler(args)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 工具执行失败: 返回 isError 的 CallToolResult 而非 JSON-RPC error, 便于客户端展示
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("result") {
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", e.message ?: e.toString())
                    })
                }
                put("isError", true)
            }
        }
    }

    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("result") {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        }
    }
}

private fun errorObj(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

private fun errorJson(id: JsonElement?, code: Int, message: String): String =
    errorObj(id, code, message).toString()

private fun discoveryJson(): String = buildJsonObject {
    put("ok", true)
    put("name", SERVER_NAME)
    put("protocol", "MCP JSON-RPC 2.0")
    put("endpoint", "/mcp")
    put("sseEndpoint", "/sse")
    put("messagesEndpoint", "/messages")
    put(
        "methods",
        JsonArray(
            listOf(
                "initialize",
                "notifications/initialized",
                "ping",
                "tools/list",
                "tools/call",
                "resources/list",
                "prompts/list",
            ).map { JsonPrimitive(it) },
        ),
    )
    put("hint", "POST JSON-RPC to /mcp. No strict Accept/Content-Type requirements; every response is plain JSON.")
}.toString()

private fun sseHello(): String {
    val endpoint = buildJsonObject { put("uri", "/messages"); put("method", "POST") }
    return "event: endpoint\ndata: $endpoint\n\n: $SERVER_NAME ready\n\n"
}
