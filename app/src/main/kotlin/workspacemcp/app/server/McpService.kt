package workspacemcp.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import workspacemcp.app.MainActivity
import workspacemcp.app.R
import workspacemcp.app.Runtime
import java.net.NetworkInterface

/**
 * 前台 Service 承载 MCP HTTP 服务器, 保证后台持续可用。
 */
class McpService : Service() {

    private var handle: McpServerHandle? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (handle == null) {
            startForegroundInternal()
            runCatching {
                handle = startMcpServer(Runtime.get())
                updateNotification(handle!!.port)
                _isRunning.value = true
            }.onFailure { error ->
                _lastError.value = error.message ?: error.toString()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handle?.stop()
        handle = null
        _isRunning.value = false
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MCP Server", NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text = "Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text = "Starting…"))
        }
    }

    private fun updateNotification(port: Int) {
        val address = "http://${lanIpAddress() ?: "0.0.0.0"}:$port"
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(text = "MCP: $address/mcp"),
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "workspacemcp.app.action.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()

        fun start(context: Context) {
            Runtime.get().settings.setServiceEnabled(true)
            val intent = Intent(context, McpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            Runtime.get().settings.setServiceEnabled(false)
            context.stopService(Intent(context, McpService::class.java))
        }

        /** 取局域网 IPv4 地址用于展示连接地址 */
        fun lanIpAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress }
                .firstOrNull { !it.startsWith("127.") }
        }.getOrNull()
    }
}
