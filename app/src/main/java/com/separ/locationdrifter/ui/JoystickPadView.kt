package com.separ.locationdrifter.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 360° 虚拟摇杆自定义视图：
 * - 输出归一化方向向量 (dx: 东正西负, dy: 北正南负)
 * - 支持普通模式（松手回中）与方向锁定模式（松手保持当前方向与幅度持续巡航）
 */
class JoystickPadView(context: Context) : View(context) {

    var onVectorChanged: ((dx: Float, dy: Float, active: Boolean) -> Unit)? = null

    var isDirectionLockEnabled: Boolean = false
        private set

    var isDirectionLockedActive: Boolean = false
        private set

    private var knobX = 0f
    private var knobY = 0f
    private var isDragging = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0F172A")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#663B82F6")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3394A3B8")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val vectorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9960A5FA")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = dp(9.5f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#553B82F6")
        style = Paint.Style.FILL
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.FILL
    }

    private val knobCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 切换方向锁定开关：关闭时立即解锁并回中停止移动。
     */
    fun setDirectionLock(enabled: Boolean) {
        isDirectionLockEnabled = enabled
        if (!enabled) {
            resetStick()
        } else {
            invalidate()
        }
    }

    /**
     * 强制摇杆回中并通知停止移动。
     */
    fun resetStick() {
        isDragging = false
        isDirectionLockedActive = false
        knobX = 0f
        knobY = 0f
        onVectorChanged?.invoke(0f, 0f, false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(4f)
        val knobRadius = radius * 0.34f
        val maxOffset = (radius - knobRadius).coerceAtLeast(1f)

        // 根据锁定状态切换视觉高亮色（锁定巡航时为琥珀橙，常态为蓝色）
        borderPaint.color = when {
            isDirectionLockedActive -> Color.parseColor("#CCF59E0B")
            isDirectionLockEnabled -> Color.parseColor("#77F59E0B")
            else -> Color.parseColor("#663B82F6")
        }
        knobPaint.color = if (isDirectionLockedActive) {
            Color.parseColor("#F59E0B")
        } else {
            Color.parseColor("#3B82F6")
        }
        knobShadowPaint.color = if (isDirectionLockedActive) {
            Color.parseColor("#55F59E0B")
        } else {
            Color.parseColor("#553B82F6")
        }
        vectorLinePaint.color = if (isDirectionLockedActive) {
            Color.parseColor("#CCFB923C")
        } else {
            Color.parseColor("#9960A5FA")
        }

        // 外圆底盘与边框
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)
        canvas.drawCircle(cx, cy, radius * 0.56f, innerRingPaint)

        // 东南西北方位标识
        val textOffset = (labelPaint.descent() + labelPaint.ascent()) / 2f
        val dirDist = radius - dp(11f)
        canvas.drawText("N", cx, cy - dirDist - textOffset, labelPaint)
        canvas.drawText("S", cx, cy + dirDist - textOffset, labelPaint)
        canvas.drawText("W", cx - dirDist, cy - textOffset, labelPaint)
        canvas.drawText("E", cx + dirDist, cy - textOffset, labelPaint)

        val drawKnobX = cx + knobX * maxOffset
        val drawKnobY = cy + knobY * maxOffset

        if (isDragging || isDirectionLockedActive) {
            canvas.drawLine(cx, cy, drawKnobX, drawKnobY, vectorLinePaint)
            canvas.drawCircle(drawKnobX, drawKnobY, knobRadius * 1.18f, knobShadowPaint)
        }
        canvas.drawCircle(drawKnobX, drawKnobY, knobRadius, knobPaint)
        canvas.drawCircle(drawKnobX, drawKnobY, knobRadius * 0.36f, knobCenterPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(4f)
        val knobRadius = radius * 0.34f
        val maxOffset = (radius - knobRadius).coerceAtLeast(1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isDragging = true
                isDirectionLockedActive = false
                val rawX = event.x - cx
                val rawY = event.y - cy
                val dist = hypot(rawX.toDouble(), rawY.toDouble()).toFloat()
                val clamped = if (dist > maxOffset) maxOffset / dist else 1f
                knobX = (rawX * clamped) / maxOffset
                knobY = (rawY * clamped) / maxOffset
                // 屏幕 Y 轴向下，地理北向为正，故 dy 取反
                onVectorChanged?.invoke(knobX, -knobY, true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                val mag = hypot(knobX.toDouble(), knobY.toDouble()).toFloat()
                if (isDirectionLockEnabled && mag >= 0.15f) {
                    // 方向锁定模式下松手保持当前方向持续移动；轻点中心死区可停止
                    isDirectionLockedActive = true
                    onVectorChanged?.invoke(knobX, -knobY, true)
                } else {
                    isDirectionLockedActive = false
                    knobX = 0f
                    knobY = 0f
                    onVectorChanged?.invoke(0f, 0f, false)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
