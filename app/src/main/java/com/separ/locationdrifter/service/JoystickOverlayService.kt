package com.separ.locationdrifter.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.data.RouteManager
import com.separ.locationdrifter.ui.JoystickPadView
import com.separ.locationdrifter.util.CoordTransform
import java.util.Locale
import kotlin.math.abs

/**
 * 全局悬浮虚拟摇杆服务：
 * - 支持在任意第三方应用上方通过 360° 摇杆实时平移虚拟位置
 * - 支持顶部拖动调整悬浮窗位置、一键切换移动速度档位、折叠为迷你悬浮球以及关闭
 */
class JoystickOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_JOYSTICK"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_JOYSTICK"

        private const val STEP_INTERVAL_MS = 200L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var rootContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var joystickBody: LinearLayout? = null
    private var tvSpeedBadge: TextView? = null
    private var tvHeadingInfo: TextView? = null
    private var btnCollapse: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentDx = 0f
    private var currentDy = 0f
    private var joystickActive = false
    private var isCollapsed = false
    private var speedMps = 4.0f

    private val stepRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !joystickActive) return
            performJoystickStep()
            mainHandler.postDelayed(this, STEP_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) {
            speedMps = RouteManager.getJoystickSpeed(this)
            showOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(220)
        }
        layoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dp(1), Color.parseColor("#4460A5FA"))
            }
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }

        // 顶部拖拽栏 + 折叠 + 关闭
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvDragHandle = TextView(this).apply {
            text = "≡ 摇杆"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val collapseBtn = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                toggleCollapse()
            }
        }
        btnCollapse = collapseBtn

        val closeBtn = TextView(this).apply {
            text = "×"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(1), dp(4), dp(1))
            setOnClickListener {
                removeOverlay()
                stopSelf()
            }
        }

        header.addView(tvDragHandle)
        header.addView(collapseBtn)
        header.addView(closeBtn)

        // 拖拽移动悬浮窗位置
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        val dragListener = View.OnTouchListener { _, event ->
            val lp = layoutParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    try {
                        wm.updateViewLayout(container, lp)
                    } catch (_: Throwable) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && isCollapsed) {
                        toggleCollapse()
                    }
                    true
                }
                else -> false
            }
        }
        tvDragHandle.setOnTouchListener(dragListener)

        // 主体：摇杆盘 + 速度切换与航向读数
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        joystickBody = body

        val padView = JoystickPadView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(128), dp(128))
            onVectorChanged = { dx, dy, active ->
                currentDx = dx
                currentDy = dy
                if (active && !joystickActive) {
                    joystickActive = true
                    // 摇杆手动接管时自动暂停轨迹自动巡航，避免位置冲突
                    if (MockLocationService.isRoutePlaying) {
                        MockLocationService.pauseRoutePlayback()
                    }
                    mainHandler.removeCallbacks(stepRunnable)
                    mainHandler.post(stepRunnable)
                } else if (!active) {
                    joystickActive = false
                    mainHandler.removeCallbacks(stepRunnable)
                    MockLocationService.setLiveMotion(0f, 0f, false)
                    tvHeadingInfo?.text = "待机中"
                }
            }
        }
        body.addView(padView)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }

        val speedBtn = TextView(this).apply {
            text = "${RouteManager.speedShortLabel(speedMps)} ↻"
            setTextColor(Color.parseColor("#BFDBFE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#332563EB"))
                setStroke(dp(1), Color.parseColor("#663B82F6"))
            }
            setPadding(dp(7), dp(3), dp(7), dp(3))
            setOnClickListener {
                speedMps = RouteManager.nextSpeedPreset(speedMps)
                RouteManager.setJoystickSpeed(this@JoystickOverlayService, speedMps)
                text = "${RouteManager.speedShortLabel(speedMps)} ↻"
            }
        }
        tvSpeedBadge = speedBtn

        val headingView = TextView(this).apply {
            text = "待机中"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }
        tvHeadingInfo = headingView

        footer.addView(speedBtn)
        footer.addView(headingView)
        body.addView(footer)

        container.addView(header)
        container.addView(body)

        try {
            wm.addView(container, params)
            rootContainer = container
            isRunning = true
        } catch (_: Throwable) {
            isRunning = false
            stopSelf()
        }
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        joystickBody?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        btnCollapse?.text = if (isCollapsed) "▢" else "—"
        if (isCollapsed) {
            joystickActive = false
            mainHandler.removeCallbacks(stepRunnable)
            MockLocationService.setLiveMotion(0f, 0f, false)
        }
    }

    private fun performJoystickStep() {
        val cfg = ConfigManager.load(this)
        val dtSec = STEP_INTERVAL_MS / 1000.0
        val (newWgsLat, newWgsLng, bearingDeg) = RouteManager.stepByVector(
            cfg.wgsLat,
            cfg.wgsLng,
            currentDx,
            currentDy,
            speedMps,
            dtSec
        )
        if (newWgsLat == cfg.wgsLat && newWgsLng == cfg.wgsLng) return

        val newGcj = CoordTransform.wgs84ToGcj02(newWgsLat, newWgsLng)
        ConfigManager.updateLiveCoordinates(
            this,
            wgsLat = newWgsLat,
            wgsLng = newWgsLng,
            gcjLat = newGcj.lat,
            gcjLng = newGcj.lng,
            address = "摇杆移动中"
        )
        MockLocationService.setLiveMotion(speedMps, bearingDeg, true)
        MockLocationService.notifyPositionListeners(
            newGcj.lat,
            newGcj.lng,
            newWgsLat,
            newWgsLng,
            "摇杆移动中",
            bearingDeg,
            speedMps
        )
        tvHeadingInfo?.text = String.format(Locale.US, "%03.0f°", bearingDeg)
    }

    private fun removeOverlay() {
        isRunning = false
        joystickActive = false
        mainHandler.removeCallbacksAndMessages(null)
        MockLocationService.setLiveMotion(0f, 0f, false)
        val view = rootContainer
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {
            }
        }
        rootContainer = null
        layoutParams = null
        joystickBody = null
        tvSpeedBadge = null
        tvHeadingInfo = null
        btnCollapse = null
    }
}
