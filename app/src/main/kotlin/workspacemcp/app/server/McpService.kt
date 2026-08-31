package workspacemcp.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import workspacemcp.app.MainActivity
import workspacemcp.app.R
import workspacemcp.app.Runtime
import java.net.NetworkInterface

/**
 * 前台 Service 承载 MCP HTTP 服务器:
 * - 常驻通知显示连接地址 (中文), 附停止/重启动作
 * - 持有 PARTIAL_WAKE_LOCK, 防止息屏后 CPU 休眠导致连接中断
 * - 可选悬浮窗保活 (需要悬浮窗权限)
 */
class McpService : Service() {

    private var handle: McpServerHandle? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_FLOATING -> {
                FloatingWindow.toggle(this)
                return START_STICKY
            }
        }
        if (handle == null) {
            startForegroundInternal()
            runCatching {
                handle = startMcpServer(Runtime.get())
                updateNotification(handle!!.port)
                acquireWakeLock()
                _isRunning.value = true
                updateFloating()
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
        releaseWakeLock()
        FloatingWindow.dismiss(this)
        _isRunning.value = false
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text = "正在启动…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text = "正在启动…"))
        }
    }

    private fun updateNotification(port: Int) {
        val address = "http://${lanIpAddress() ?: "0.0.0.0"}:$port"
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(text = "运行中 · $address/mcp"),
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
            .addAction(0, "停止服务", stopIntent)
            .build()
    }

    private fun updateFloating() {
        if (Runtime.get().settings.floatingEnabled()) {
            FloatingWindow.show(this)
        }
    }

    // ===== WakeLock 保活 =====

    @Synchronized
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkspaceMcp:server").also {
            it.setReferenceCounted(false)
            it.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "workspacemcp.app.action.STOP"
        private const val ACTION_TOGGLE_FLOATING = "workspacemcp.app.action.TOGGLE_FLOATING"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000 // 12 小时, 防异常泄漏

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

        /** 跳转到系统「通知访问/应用通知」设置页, 用于 Android 13+ 通知权限请求 */
        fun requestNotificationPermission(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }

        /** 跳转到系统悬浮窗权限设置页 */
        fun requestOverlayPermission(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        fun hasOverlayPermission(context: Context): Boolean =
            android.provider.Settings.canDrawOverlays(context)

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
