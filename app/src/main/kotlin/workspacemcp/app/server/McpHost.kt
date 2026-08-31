package workspacemcp.app.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.StatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import workspacemcp.app.AppRuntime
import workspacemcp.app.domain.WorkspaceController
import workspacemcp.app.mcp.createWorkspaceMcpServer

class McpServerHandle(
    val port: Int,
    private val server: EmbeddedServer<CIOApplicationEngine, Application>,
) {
    fun stop() {
        server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    }
}

/**
 * 启动 Ktor (CIO) 承载 MCP server:
 * - /mcp  -> Streamable HTTP (MCP 2025-03-26+)
 * - /sse  -> SSE (legacy HTTP+SSE transport)
 * 可选 Bearer Token 鉴权; 局域网访问需要禁用 SDK 默认的 DNS rebinding 保护。
 */
fun startMcpServer(runtime: AppRuntime): McpServerHandle {
    val controller = WorkspaceController(runtime)
    val port = runtime.settings.port()
    val token = runtime.settings.token().takeIf { it.isNotBlank() }

    val server = embeddedServer(CIO, host = "0.0.0.0", port = port) {
        installCors()
        if (token != null) {
            installBearerAuth(token)
        }

        // Streamable HTTP 传输
        mcpStreamableHttp(path = "/mcp", enableDnsRebindingProtection = false) {
            createWorkspaceMcpServer(controller)
        }

        // SSE 传输 (旧协议)
        mcp(path = "/sse", enableDnsRebindingProtection = false) {
            createWorkspaceMcpServer(controller)
        }

        routing {
            // 健康检查 / 首页
            get("/") {
                call.respondText(
                    "workspace-mcp-server is running. Endpoints: /mcp (Streamable HTTP), /sse (SSE)",
                    ContentType.TextPlain,
                )
            }
        }
    }
    server.start(wait = false)
    return McpServerHandle(port, server)
}

private fun Application.installCors() {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("Mcp-Session-Id")
        allowHeader("MCP-Protocol-Version")
        allowHeader("Last-Event-ID")
    }
}

/** 简单 Bearer Token 校验; 跳过 CORS 预检与首页健康检查 */
private fun Application.installBearerAuth(token: String) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.httpMethod == HttpMethod.Options) return@intercept
        if (call.request.path() == "/") return@intercept
        val provided = call.request.headers[HttpHeaders.Authorization]
        if (provided != "Bearer $token") {
            call.respondText("Unauthorized", ContentType.TextPlain, StatusCode.Unauthorized)
            finish()
        }
    }
}
