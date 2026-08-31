package workspacemcp.app.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import workspacemcp.app.data.WorkspaceRecord
import workspacemcp.app.domain.WorkspaceController
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_WRITE_FILE_BYTES = 8L * 1024 * 1024

/**
 * MCP 工具定义: 与传输层解耦, 由 McpHost 的 JSON-RPC 分发器调用。
 * handler 抛出的异常由传输层统一包装为 isError 的 CallToolResult。
 */
class WorkspaceTool(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String>,
    val handler: suspend (JsonObject?) -> String,
)

fun createWorkspaceTools(controller: WorkspaceController): List<WorkspaceTool> {
    val manager = controller.manager

    return listOf(
        // ===== 工作区管理 (保留原 App 的创建/切换能力) =====

        tool(
            name = "workspace_create",
            description = "Create a new workspace and select it as current. " +
                "Each workspace has an isolated files area and its own optional Linux rootfs.",
            "name" to propString("Name of the new workspace, must be unique"),
        ) { args ->
            val name = args.requireString("name")
            withIO { controller.create(name) }.let {
                json {
                    put("id", it.id)
                    put("name", it.name)
                    put("root", it.root)
                    put("createdAt", it.createdAt)
                    put("current", true)
                }
            }
        },

        tool(
            name = "workspace_list",
            description = "List all workspaces. The current workspace is marked with current=true.",
        ) {
            val current = controller.current()?.id
            withIO { controller.list() }.joinToString(
                prefix = "[",
                separator = ",",
                postfix = "]",
            ) { it.toJsonString(current == it.id) }
        },

        tool(
            name = "workspace_switch",
            description = "Switch the current workspace. Later file/shell tool calls will act on it.",
            "id" to propString("Workspace id returned by workspace_list / workspace_create"),
        ) { args ->
            val id = args.requireString("id")
            withIO { controller.switch(id) }.let {
                json {
                    put("id", it.id)
                    put("name", it.name)
                    put("hasRootfs", manager.hasRootfs(it.root))
                    put("current", true)
                }
            }
        },

        tool(
            name = "workspace_current",
            description = "Get the currently selected workspace.",
        ) {
            val record = withIO { controller.requireCurrent() }
            json {
                put("id", record.id)
                put("name", record.name)
                put("root", record.root)
                put("hasRootfs", manager.hasRootfs(record.root))
            }
        },

        tool(
            name = "workspace_rename",
            description = "Rename a workspace.",
            "id" to propString("Workspace id"),
            "name" to propString("New unique name"),
        ) { args ->
            val id = args.requireString("id")
            val name = args.requireString("name")
            withIO { controller.rename(id, name) }.let {
                json { put("id", it.id); put("name", it.name) }
            }
        },

        tool(
            name = "workspace_delete",
            description = "Delete a workspace together with its files and rootfs. Irreversible.",
            "id" to propString("Workspace id"),
        ) { args ->
            val id = args.requireString("id")
            val deleted = withIO { controller.delete(id) }
            json { put("deleted", deleted) }
        },

        tool(
            name = "workspace_install_rootfs",
            description = "Download and install a Linux rootfs (default: Ubuntu 24.04 base arm64) into a workspace. " +
                "Required before workspace_shell can run. This is a long-running operation (hundreds of MB).",
            "id" to propString("Workspace id. Defaults to the current workspace.", optional = true),
            "url" to propString("Rootfs tar.gz/tar.xz download url. Defaults to Ubuntu 24.04 base arm64.", optional = true),
        ) { args ->
            val id = args.optString("id") ?: withIO { controller.requireCurrent().id }
            val url = args.optString("url") ?: WorkspaceController.DEFAULT_ROOTFS_URL
            controller.installRootfs(id, url)
            json { put("id", id); put("installed", true); put("url", url) }
        },

        // ===== 文件工具 (Rootfs 绝对路径, 与原 App 的 agent 工具语义一致) =====

        tool(
            name = "workspace_read_file",
            description = "Read a UTF-8 text file in the current workspace. Paths must be absolute inside Rootfs. " +
                "Use /workspace for the workspace files area.",
            "path" to propString("Absolute path inside Rootfs, e.g. /workspace/src/main.kt"),
        ) { args ->
            val root = withIO { controller.requireCurrent().root }
            val path = args.requireAbsolutePath("path")
            withIO {
                val text = readRootfsText(manager, root, path)
                json { put("path", path); put("text", text) }
            }
        },

        tool(
            name = "workspace_write_file",
            description = "Write a UTF-8 text file in the current workspace. Paths must be absolute inside Rootfs. " +
                "Use /workspace for the workspace files area.",
            "path" to propString("Absolute path inside Rootfs"),
            "text" to propString("UTF-8 text content to write"),
            "overwrite" to propBoolean("Whether to overwrite an existing file. Defaults to true.", optional = true),
        ) { args ->
            val root = withIO { controller.requireCurrent().root }
            val path = args.requireAbsolutePath("path")
            val text = args.requireString("text")
            val overwrite = args.optBool("overwrite") ?: true
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_WRITE_FILE_BYTES) { "Content is too large to write: ${bytes.size} bytes" }
            withIO { writeRootfsText(manager, root, path, text, overwrite) }.let {
                json {
                    put("path", path)
                    put("sizeBytes", it.sizeBytes)
                    put("updatedAt", it.updatedAt)
                }
            }
        },

        tool(
            name = "workspace_edit_file",
            description = "Edit a UTF-8 text file in the current workspace by replacing old_text with new_text. " +
                "Paths must be absolute inside Rootfs. Use /workspace for the workspace files area. " +
                "By default old_text must occur exactly once; set replace_all=true to replace every occurrence. " +
                "If no exact match is found, whitespace-tolerant line matching is attempted automatically.",
            "path" to propString("Absolute path inside Rootfs"),
            "old_text" to propString("Exact text to replace"),
            "new_text" to propString("Replacement text"),
            "replace_all" to propBoolean("Whether to replace every occurrence. Defaults to false.", optional = true),
        ) { args ->
            val root = withIO { controller.requireCurrent().root }
            val path = args.requireAbsolutePath("path")
            val oldText = args.requireString("old_text")
            val newText = args.requireString("new_text")
            val replaceAll = args.optBool("replace_all") ?: false
            require(oldText.isNotEmpty()) { "old_text must not be empty" }

            withIO {
                val original = readRootfsText(manager, root, path)
                val result = try {
                    replaceText(original, oldText, newText, replaceAll)
                } catch (e: IllegalArgumentException) {
                    error("${e.message} (path: $path)")
                }
                val entry = writeRootfsText(manager, root, path, result.updated, overwrite = true)
                val diff = generateUnifiedDiff(original, result.updated, path)
                buildJsonObject {
                    put("path", path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    if (diff != null) put("diff", diff)
                }.toString()
            }
        },

        tool(
            name = "workspace_shell",
            description = "Run a shell command in the current workspace Rootfs (via PRoot). " +
                "The workspace files area is mounted at /workspace. Use cwd for a path relative to the files root. " +
                "Requires Rootfs to be installed (workspace_install_rootfs).",
            "command" to propString("Shell command to run"),
            "cwd" to propString("Working directory relative to the workspace files root. Defaults to root.", optional = true),
            "timeout" to propInt("Command timeout in seconds. Defaults to 30, max 600.", optional = true),
        ) { args ->
            val root = withIO { controller.requireCurrent().root }
            val command = args.requireString("command")
            val cwd = (args.optString("cwd") ?: "")
                .removePrefix("/workspace/").removePrefix("/workspace")
            val timeoutMillis = args.optInt("timeout")?.toLong()
                ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)?.times(1_000L)
                ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
            withIO { manager.executeCommand(root, command, cwd, timeoutMillis) }.let { result ->
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            }
        },

        // ===== 文件区工具 (files 区相对路径, 对应原 App 的文件管理功能) =====

        tool(
            name = "workspace_list_files",
            description = "List entries in the current workspace files area (/workspace). " +
                "Path is relative to the files root, empty for the root itself.",
            "path" to propString("Directory path relative to the files root. Defaults to root.", optional = true),
        ) { args ->
            val record = withIO { controller.requireCurrent() }
            val path = args.optString("path").orEmpty()
            withIO { manager.listFiles(record.root, path) }.let { entries ->
                json {
                    put("path", path.ifBlank { "/" })
                    put("count", entries.size)
                    put("entries", entries.toJsonArray())
                }
            }
        },

        tool(
            name = "workspace_delete_file",
            description = "Delete a file or directory in the current workspace files area. " +
                "Directory delete requires recursive=true.",
            "path" to propString("Path relative to the files root"),
            "recursive" to propBoolean("Required true when deleting a directory.", optional = true),
        ) { args ->
            val record = withIO { controller.requireCurrent() }
            val path = args.requireString("path")
            val recursive = args.optBool("recursive") ?: false
            val deleted = withIO { manager.deleteFile(record.root, path, recursive) }
            json { put("deleted", deleted) }
        },

        tool(
            name = "workspace_move_file",
            description = "Move or rename a file inside the current workspace files area.",
            "source" to propString("Source path relative to the files root"),
            "target" to propString("Target path relative to the files root"),
            "overwrite" to propBoolean("Whether to overwrite the target if it exists. Defaults to false.", optional = true),
        ) { args ->
            val record = withIO { controller.requireCurrent() }
            val source = args.requireString("source")
            val target = args.requireString("target")
            val overwrite = args.optBool("overwrite") ?: false
            withIO { manager.moveFile(record.root, source, target, overwrite) }.let {
                json { put("path", it.path); put("sizeBytes", it.sizeBytes) }
            }
        },

        tool(
            name = "workspace_glob",
            description = "Find files in the current workspace files area by glob pattern (e.g. **/*.kt).",
            "pattern" to propString("Glob pattern, matched against paths relative to the files root"),
            "path" to propString("Base directory relative to the files root. Defaults to root.", optional = true),
        ) { args ->
            val record = withIO { controller.requireCurrent() }
            val pattern = args.requireString("pattern")
            val path = args.optString("path").orEmpty()
            withIO { manager.glob(record.root, pattern, path) }.let { entries ->
                json {
                    put("pattern", pattern)
                    put("count", entries.size)
                    put("entries", entries.toJsonArray())
                }
            }
        },

        tool(
            name = "workspace_grep",
            description = "Search text in the current workspace files area.",
            "query" to propString("Search query, plain text or regex"),
            "path" to propString("Base directory relative to the files root. Defaults to root.", optional = true),
            "regex" to propBoolean("Treat query as regex. Defaults to false.", optional = true),
            "ignore_case" to propBoolean("Case-insensitive search. Defaults to true.", optional = true),
            "include_glob" to propString("Only search files matching this glob, e.g. *.kt", optional = true),
        ) { args ->
            val record = withIO { controller.requireCurrent() }
            val query = args.requireString("query")
            val path = args.optString("path").orEmpty()
            val regex = args.optBool("regex") ?: false
            val ignoreCase = args.optBool("ignore_case") ?: true
            val includeGlob = args.optString("include_glob")
            withIO {
                manager.grep(record.root, query, path, regex, ignoreCase, includeGlob)
            }.let { matches ->
                json {
                    put("query", query)
                    put("count", matches.size)
                    put("matches", matches.toJsonArray())
                }
            }
        },
    )
}

// ===== helpers =====

private suspend fun <T> withIO(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun json(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject(block).toString()

private fun WorkspaceRecord.toJsonString(current: Boolean): String = buildJsonObject {
    put("id", id)
    put("name", name)
    put("root", root)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    lastAccessAt?.let { put("lastAccessAt", it) }
    put("current", current)
}.toString()

@JvmName("fileEntriesToJsonArray")
private fun List<WorkspaceFileEntry>.toJsonArray(): String = kotlinx.serialization.json.JsonArray(
    map { entry ->
        buildJsonObject {
            put("path", entry.path)
            put("name", entry.name)
            put("isDirectory", entry.isDirectory)
            put("sizeBytes", entry.sizeBytes)
            put("updatedAt", entry.updatedAt)
        }
    },
).toString()

@JvmName("searchMatchesToJsonArray")
private fun List<me.rerere.workspace.WorkspaceSearchMatch>.toJsonArray(): String =
    kotlinx.serialization.json.JsonArray(
        map { match ->
            buildJsonObject {
                put("path", match.path)
                put("line", match.line)
                put("text", match.text)
            }
        },
    ).toString()

/** Rootfs 绝对路径读取 (对应原 readRootfsBuffer: 大小限制 + 路径映射) */
private fun readRootfsText(
    manager: WorkspaceManager,
    root: String,
    path: String,
): String {
    val size = manager.rootfsFileSize(root, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). " +
            "Use shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    manager.exportRootfsFile(root, path, buffer)
    return buffer.toString(Charsets.UTF_8.name())
}

/** Rootfs 绝对路径写入: /workspace 与 bind mounts 映射到宿主目录, 其余写入 rootfs 内部 */
private fun writeRootfsText(
    manager: WorkspaceManager,
    root: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val location = manager.resolveRootfsPath(root, path)
    require(location.relativePath.isNotBlank()) { "Cannot write to a mount root: $path" }
    val file = java.io.File(location.rootDir, location.relativePath)
    if (file.exists()) {
        require(overwrite) { "File already exists: $path" }
        require(file.isFile) { "Path is not a file: $path" }
    }
    file.parentFile?.mkdirs()
    file.writeText(text)
    return WorkspaceFileEntry(
        path = path,
        name = file.name,
        isDirectory = false,
        sizeBytes = file.length(),
        updatedAt = file.lastModified(),
    )
}

// ===== schema DSL =====

private class Prop(val json: JsonObject, val required: Boolean)

private fun tool(
    name: String,
    description: String,
    vararg props: Pair<String, Prop>,
    handler: suspend (JsonObject?) -> String,
): WorkspaceTool = WorkspaceTool(
    name = name,
    description = description,
    properties = buildJsonObject { props.forEach { (n, p) -> put(n, p.json) } },
    required = props.filter { it.second.required }.map { it.first },
    handler = handler,
)

private fun propString(description: String, optional: Boolean = false): Prop = Prop(
    json = buildJsonObject {
        put("type", "string")
        put("description", description)
    },
    required = !optional,
)

private fun propBoolean(description: String, optional: Boolean = false): Prop = Prop(
    json = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    },
    required = !optional,
)

private fun propInt(description: String, optional: Boolean = false): Prop = Prop(
    json = buildJsonObject {
        put("type", "integer")
        put("description", description)
    },
    required = !optional,
)

// ===== argument 解析 (对应原 JsonObject.string / absolutePath) =====

private fun JsonObject?.requireString(name: String): String {
    val value = this?.get(name)?.asStringOrNull()
    require(!value.isNullOrBlank()) { "$name is required" }
    return value
}

private fun JsonObject?.optString(name: String): String? =
    this?.get(name)?.asStringOrNull()?.takeIf { it.isNotBlank() }

private fun JsonObject?.optBool(name: String): Boolean? =
    this?.get(name)?.asBooleanOrNull()

private fun JsonObject?.optInt(name: String): Int? =
    this?.get(name)?.asIntOrNull()

private fun JsonObject?.requireAbsolutePath(name: String): String {
    val path = this?.get(name)?.asStringOrNull()?.replace('\\', '/')?.trim()
        ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(path.none { it.code == 0 }) { "$name contains invalid character" }
    return path
}

private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement.asBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

private fun kotlinx.serialization.json.JsonElement.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.intOrNull
