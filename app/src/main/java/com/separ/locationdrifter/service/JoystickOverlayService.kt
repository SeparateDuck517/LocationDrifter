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
import android.os.SystemClock
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
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * 全局悬浮虚拟摇杆服务：
 * - 支持在任意第三方应用上方通过 360° 摇杆实时平移虚拟位置
 * - 支持方向锁定（松手后沿锁定方向自动巡航，折叠后也可后台保持巡航）
 * - 支持顶部拖动调整悬浮窗位置、一键切换移动速度档位、折叠与关闭
 */
class JoystickOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.separ.locationdrifter.action.START_JOYSTICK"
        const val ACTION_STOP = "com.separ.locationdrifter.action.STOP_JOYSTICK"

        private const val STEP_INTERVAL_MS = 200L

        @Volatile
        var isRunning: Boolean = false
            private set

        private val mainUiHandler = Handler(Looper.getMainLooper())
        private val runningStateListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        fun addRunningStateListener(listener: (Boolean) -> Unit) {
            runningStateListeners.add(listener)
        }

        fun removeRunningStateListener(listener: (Boolean) -> Unit) {
            runningStateListeners.remove(listener)
        }

        private fun notifyRunningState(running: Boolean) {
            if (runningStateListeners.isEmpty()) return
            mainUiHandler.post {
                for (l in runningStateListeners) {
                    try {
                        l(running)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var rootContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var joystickBody: LinearLayout? = null
    private var joystickPad: JoystickPadView? = null
    private var tvDragHandle: TextView? = null
    private var tvSpeedBadge: TextView? = null
    private var btnLockDirection: TextView? = null
    private var btnCollapse: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentDx = 0f
    private var currentDy = 0f
    private var joystickActive = false
    private var isCollapsed = false
    private var isDirectionLockEnabled = false
    private var speedMps = 4.0f
    private var lastStepElapsedMs = 0L

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
            isDirectionLockEnabled = false
            showOverlay()
        }
        return START_STICKY
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

        val dragHandleView = TextView(this).apply {
            text = "≡ 摇杆"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(4), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvDragHandle = dragHandleView

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

        header.addView(dragHandleView)
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
        dragHandleView.setOnTouchListener(dragListener)

        // 主体：摇杆盘 + 速度/纯图标方向锁控制条
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        joystickBody = body

        val padView = JoystickPadView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(124), dp(124))
            onVectorChanged = { dx, dy, active ->
                currentDx = dx
                currentDy = dy
                if (active && !joystickActive) {
                    joystickActive = true
                    lastStepElapsedMs = SystemClock.elapsedRealtime()
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
                    refreshHeaderTitle(null)
                }
            }
        }
        joystickPad = padView
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#332563EB"))
                setStroke(dp(1), Color.parseColor("#663B82F6"))
            }
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                speedMps = RouteManager.nextSpeedPreset(speedMps)
                RouteManager.setJoystickSpeed(this@JoystickOverlayService, speedMps)
                text = "${RouteManager.speedShortLabel(speedMps)} ↻"
            }
        }
        tvSpeedBadge = speedBtn

        val lockBtn = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                toggleDirectionLock()
            }
        }
        btnLockDirection = lockBtn

        footer.addView(speedBtn)
        footer.addView(lockBtn)
        body.addView(footer)

        container.addView(header)
        container.addView(body)

        refreshLockButtonUI()
        refreshHeaderTitle(null)

        try {
            wm.addView(container, params)
            rootContainer = container
            isRunning = true
            notifyRunningState(true)
        } catch (_: Throwable) {
            isRunning = false
            notifyRunningState(false)
            stopSelf()
        }
    }

    private fun toggleDirectionLock() {
        isDirectionLockEnabled = !isDirectionLockEnabled
        joystickPad?.setDirectionLock(isDirectionLockEnabled)
        refreshLockButtonUI()
        if (!joystickActive) {
            refreshHeaderTitle(null)
        }
    }

    private fun refreshLockButtonUI() {
        val btn = btnLockDirection ?: return
        if (isDirectionLockEnabled) {
            btn.text = "🔒"
            btn.setTextColor(Color.parseColor("#FDE68A"))
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33F59E0B"))
                setStroke(dp(1), Color.parseColor("#99F59E0B"))
            }
        } else {
            btn.text = "🔓"
            btn.setTextColor(Color.parseColor("#CBD5E1"))
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33475569"))
                setStroke(dp(1), Color.parseColor("#6664748B"))
            }
        }
    }

    private fun refreshHeaderTitle(bearingDeg: Float?) {
        val locked = isDirectionLockEnabled && (joystickPad?.isDirectionLockedActive == true)
        when {
            isCollapsed && locked && joystickActive -> {
                tvDragHandle?.text = "🔒 巡航"
                tvDragHandle?.setTextColor(Color.parseColor("#FDE68A"))
            }
            bearingDeg != null && locked -> {
                tvDragHandle?.text = String.format(Locale.US, "🔒 %03.0f°", bearingDeg)
                tvDragHandle?.setTextColor(Color.parseColor("#FDE68A"))
            }
            bearingDeg != null -> {
                tvDragHandle?.text = String.format(Locale.US, "≡ %03.0f°", bearingDeg)
                tvDragHandle?.setTextColor(Color.parseColor("#93C5FD"))
            }
            else -> {
                tvDragHandle?.text = "≡ 摇杆"
                tvDragHandle?.setTextColor(Color.parseColor("#E2E8F0"))
            }
        }
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        joystickBody?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        btnCollapse?.text = if (isCollapsed) "▢" else "—"
        if (isCollapsed) {
            if (isDirectionLockEnabled && joystickActive) {
                refreshHeaderTitle(null)
            } else {
                joystickPad?.resetStick()
                joystickActive = false
                mainHandler.removeCallbacks(stepRunnable)
                MockLocationService.setLiveMotion(0f, 0f, false)
                refreshHeaderTitle(null)
            }
        } else {
            refreshHeaderTitle(null)
        }
    }

    private fun performJoystickStep() {
        val cfg = ConfigManager.load(this)
        val nowMs = SystemClock.elapsedRealtime()
        val dtSec = if (lastStepElapsedMs > 0L) {
            ((nowMs - lastStepElapsedMs).coerceIn(50L, 1000L)) / 1000.0
        } else {
            STEP_INTERVAL_MS / 1000.0
        }
        lastStepElapsedMs = nowMs

        val (newWgsLat, newWgsLng, bearingDeg) = RouteManager.stepByVector(
            cfg.wgsLat,
            cfg.wgsLng,
            currentDx,
            currentDy,
            speedMps,
            dtSec
        )
        if (newWgsLat == cfg.wgsLat && newWgsLng == cfg.wgsLng) return

        val stepDist = RouteManager.distanceMeters(cfg.wgsLat, cfg.wgsLng, newWgsLat, newWgsLng)
        if (stepDist > 0.0) {
            ConfigManager.accumulateSimulatedSteps(this, stepDist, speedMps)
        }
        val newGcj = CoordTransform.wgs84ToGcj02(newWgsLat, newWgsLng)
        val locked = isDirectionLockEnabled && (joystickPad?.isDirectionLockedActive == true)
        val label = if (locked) "摇杆锁定巡航中" else "摇杆移动中"
        ConfigManager.updateLiveCoordinates(
            this,
            wgsLat = newWgsLat,
            wgsLng = newWgsLng,
            gcjLat = newGcj.lat,
            gcjLng = newGcj.lng,
            address = label
        )
        MockLocationService.pushLiveLocationNow(newWgsLat, newWgsLng, speedMps, bearingDeg)
        MockLocationService.notifyPositionListeners(
            newGcj.lat,
            newGcj.lng,
            newWgsLat,
            newWgsLng,
            label,
            bearingDeg,
            speedMps
        )
        refreshHeaderTitle(bearingDeg)
    }

    private fun removeOverlay() {
        val wasRunning = isRunning
        isRunning = false
        joystickActive = false
        isDirectionLockEnabled = false
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
        joystickPad = null
        tvDragHandle = null
        tvSpeedBadge = null
        btnLockDirection = null
        btnCollapse = null
        if (wasRunning) {
            notifyRunningState(false)
        }
    }
}
