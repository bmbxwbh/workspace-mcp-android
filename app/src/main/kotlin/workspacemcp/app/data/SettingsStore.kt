package workspacemcp.app.data

import android.content.Context
import android.content.SharedPreferences

data class ServerSettings(
    val port: Int,
    val token: String?,
    val currentWorkspaceId: String?,
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)

    fun settings(): ServerSettings = ServerSettings(
        port = port(),
        token = token(),
        currentWorkspaceId = prefs.getString(KEY_CURRENT_WORKSPACE, null),
    )

    fun port(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        require(port in 1..65535) { "Port out of range: $port" }
        prefs.edit().putInt(KEY_PORT, port).apply()
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setToken(token: String?) {
        prefs.edit().putString(KEY_TOKEN, token?.trim().orEmpty()).apply()
    }

    fun currentWorkspaceId(): String? = prefs.getString(KEY_CURRENT_WORKSPACE, null)

    fun setCurrentWorkspaceId(id: String?) {
        prefs.edit().putString(KEY_CURRENT_WORKSPACE, id).apply()
    }

    /** 用户是否期望服务保持运行 (进程重启后据此恢复前台服务) */
    fun serviceEnabled(): Boolean = prefs.getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_CURRENT_WORKSPACE = "current_workspace_id"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }
}
