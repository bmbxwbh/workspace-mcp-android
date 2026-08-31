package workspacemcp.app.server

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * 悬浮球视图: 圆形状态球 + "MCP" 文字, 运行中绿色/未运行灰色。
 */
class StatusBallView(context: Context) : View(context) {

    var text: String = "MCP"

    private val density = resources.displayMetrics.density
    private val sizePx = (48 * density).toInt()

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.argb(90, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = sizePx / 2f - 2 * density

        val running = McpService.isRunning.value
        val baseColor = if (running) Color.rgb(46, 160, 96) else Color.rgb(120, 120, 120)
        circlePaint.shader = RadialGradient(
            cx - radius / 4, cy - radius / 4, radius * 1.4f,
            lighten(baseColor), baseColor, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, circlePaint)
        circlePaint.shader = null
        canvas.drawCircle(cx, cy, radius, strokePaint)

        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, cx, textY, textPaint)
    }

    private fun lighten(color: Int): Int {
        val r = (Color.red(color) + 60).coerceAtMost(255)
        val g = (Color.green(color) + 60).coerceAtMost(255)
        val b = (Color.blue(color) + 60).coerceAtMost(255)
        return Color.rgb(r, g, b)
    }
}
