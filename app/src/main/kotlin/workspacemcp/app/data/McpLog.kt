package workspacemcp.app.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简文件日志: 写入应用数据目录 files/logs/, 按天分文件, 自动清理过期日志。
 * 用于排查 MCP 连接问题 (如 HTTP 502 / 连接不通):
 * - 若日志中完全没有请求记录, 说明请求没有到达本机服务器 (多半被客户端侧代理拦截)
 * - 若有请求记录但状态异常, 会附带服务器侧错误堆栈
 */
object McpLog {

    private const val KEEP_DAYS = 7
    private const val PREFIX = "mcp-"
    private const val SUFFIX = ".log"

    private val lock = Any()

    @Volatile
    private var dir: File? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(filesDir: File) {
        val logDir = File(filesDir, "logs")
        logDir.mkdirs()
        dir = logDir
        cleanupOldLogs(logDir)
        i("Log", "log directory: ${logDir.absolutePath}")
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String) = write("W", tag, message, null)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val logDir = dir ?: return
        val now = Date()
        val line = buildString {
            append(timeFormat.format(now))
            append(' ').append(level).append('/').append(tag).append(": ")
            append(message)
            error?.let { append(" | ").append(it.toString()) }
        }
        synchronized(lock) {
            runCatching {
                File(logDir, PREFIX + fileFormat.format(now) + SUFFIX).appendText(line + "\n")
            }
        }
    }

    private fun cleanupOldLogs(logDir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        logDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }
}
