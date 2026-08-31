package workspacemcp.app.server

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import workspacemcp.app.MainActivity
import workspacemcp.app.Runtime

/**
 * 悬浮窗保活: 显示 MCP 服务运行状态的可拖动小球。
 * - 拖动: 移动位置
 * - 单击: 返回应用主界面
 * - 双击: 复制连接地址到剪贴板
 * - 长按: 关闭悬浮窗 (关闭后不再跟随服务启动, 需在应用内重新开启)
 *
 * 需要悬浮窗权限 (Settings.canDrawOverlays), 未授权时 show() 静默跳过。
 */
object FloatingWindow {

    private const val LONG_PRESS_TIMEOUT_MS = 600L

    private var view: View? = null
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastClickTime = 0L

    private val _isShowing = MutableStateFlow(false)
    val isShowing = _isShowing.asStateFlow()

    fun show(context: Context) {
        val appContext = context.applicationContext
        if (view != null || !McpService.hasOverlayPermission(appContext)) return
        val wm = appContext.getSystemService(WindowManager::class.java)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 400
        }

        val ball = StatusBallView(appContext).apply {
            text = "MCP"
        }
        attachTouchListener(appContext, ball, params, wm)
        runCatching {
            wm.addView(ball, params)
            view = ball
            _isShowing.value = true
        }
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        val current = view ?: return
        runCatching {
            appContext.getSystemService(WindowManager::class.java).removeView(current)
        }
        view = null
        _isShowing.value = false
    }

    fun toggle(context: Context) {
        if (view != null) {
            dismiss(context)
            Runtime.get().settings.setFloatingEnabled(false)
        } else {
            if (!McpService.hasOverlayPermission(context)) {
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                McpService.requestOverlayPermission(context)
                return
            }
            Runtime.get().settings.setFloatingEnabled(true)
            show(context)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener(
        context: Context,
        ball: StatusBallView,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastDownX = event.rawX
                    lastDownY = event.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastDownX
                    val dy = event.rawY - lastDownY
                    if (moved || dx * dx + dy * dy > 100) { // ~10px 阈值
                        moved = true
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        runCatching { wm.updateViewLayout(ball, params) }
                    }
                    lastDownX = event.rawX
                    lastDownY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                        val now = System.currentTimeMillis()
                        when {
                            now - downTime >= LONG_PRESS_TIMEOUT_MS -> dismissAndDisable(context)

                            now - lastClickTime < 300 -> { // 双击: 复制地址
                                lastClickTime = 0
                                copyAddress(context)
                            }

                            else -> {
                                lastClickTime = now
                                // 延迟判断双击, 简化处理: 单击直接回应用
                                openApp(context)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dismissAndDisable(context: Context) {
        Runtime.get().settings.setFloatingEnabled(false)
        dismiss(context)
        Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun openApp(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun copyAddress(context: Context) {
        val port = Runtime.get().settings.port()
        val address = "http://${McpService.lanIpAddress() ?: "127.0.0.1"}:$port/mcp"
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("MCP 地址", address))
            Toast.makeText(context, "已复制: $address", Toast.LENGTH_SHORT).show()
        }
    }
}
