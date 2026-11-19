package com.separ.locationdrifter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.separ.locationdrifter.data.ConfigManager
import com.separ.locationdrifter.data.RouteManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 侧边栏导航抽屉、参数设置页（速度/海拔/夜间模式）、手动检查更新与关于页面的独立控制器。
 */
object SidebarController {

    private const val GITHUB_REPO_URL = "https://github.com/SeparateDuck517/LocationDrifter"
    private const val GITHUB_RELEASES_URL = "https://github.com/SeparateDuck517/LocationDrifter/releases"
    private const val GITHUB_ACTIONS_URL = "https://github.com/SeparateDuck517/LocationDrifter/actions"
    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/SeparateDuck517/LocationDrifter/releases/latest"
    private const val AUTHOR_NAME = "SeparateDuck517"

    private val updateExecutor = Executors.newSingleThreadExecutor()

    private class DrawerHandle(
        val panel: View,
        val scrim: View,
        var isOpen: Boolean = false
    ) {
        fun open() {
            if (isOpen) return
            isOpen = true
            val width = panel.width.takeIf { it > 0 }?.toFloat()
                ?: TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    296f,
                    panel.resources.displayMetrics
                )
            scrim.visibility = View.VISIBLE
            scrim.bringToFront()
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(200L).start()

            panel.visibility = View.VISIBLE
            panel.bringToFront()
            panel.translationX = -width
            panel.animate()
                .translationX(0f)
                .setDuration(230L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            val width = panel.width.takeIf { it > 0 }?.toFloat()
                ?: TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    296f,
                    panel.resources.displayMetrics
                )
            scrim.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    if (!isOpen) scrim.visibility = View.GONE
                }
                .start()

            panel.animate()
                .translationX(-width)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (!isOpen) panel.visibility = View.GONE
                }
                .start()
        }
    }

    fun attach(
        activity: Activity,
        getThemeMode: () -> Int,
        onApplyThemeMode: (Int) -> Unit
    ) {
        val drawerPanel = activity.findViewById<View>(R.id.drawerPanel) ?: return
        val drawerScrim = activity.findViewById<View>(R.id.drawerScrim) ?: return
        val btnOpenDrawer = activity.findViewById<View>(R.id.btnOpenDrawer)
        val btnCloseDrawer = activity.findViewById<View>(R.id.btnCloseDrawer)
        val tvDrawerVersion = activity.findViewById<TextView>(R.id.tvDrawerVersion)
        val btnOpenSettings = activity.findViewById<View>(R.id.btnOpenSettings)
        val btnCheckUpdate = activity.findViewById<View>(R.id.btnCheckUpdate)
        val btnOpenAbout = activity.findViewById<View>(R.id.btnOpenAbout)

        tvDrawerVersion?.text = "v${resolveVersionName(activity)}"

        val handle = DrawerHandle(drawerPanel, drawerScrim)

        btnOpenDrawer?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handle.open()
        }
        btnCloseDrawer?.setOnClickListener { handle.close() }
        drawerScrim.setOnClickListener { handle.close() }

        // 点击抽屉内原有入口（切换模式/指引/收藏夹/状态胶囊）后平滑收起抽屉，不覆盖 MainActivity 原始点击事件
        val autoCloseIds = intArrayOf(
            R.id.btnSwitchMode,
            R.id.btnHelpGuide,
            R.id.btnFavorites,
            R.id.tvRootStatus,
            R.id.tvXposedStatus
        )
        for (id in autoCloseIds) {
            activity.findViewById<View>(id)?.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    v.postDelayed({ handle.close() }, 80L)
                }
                false
            }
        }

        btnOpenSettings?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handle.close()
            showSettingsDialog(activity, getThemeMode(), onApplyThemeMode)
        }

        btnCheckUpdate?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handle.close()
            checkForUpdates(activity)
        }

        btnOpenAbout?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handle.close()
            showAboutDialog(activity)
        }
    }

    private fun resolveVersionName(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName?.takeIf { it.isNotBlank() } ?: "1.3.0"
        } catch (_: Throwable) {
            "1.3.0"
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun checkForUpdates(activity: Activity) {
        val currentVersion = resolveVersionName(activity)
        Toast.makeText(activity, "正在检查更新...", Toast.LENGTH_SHORT).show()

        updateExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LocationDrifter/$currentVersion (Android)")
                }
                val code = conn.responseCode
                if (code == 404) {
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        AlertDialog.Builder(activity)
                            .setTitle("检查更新")
                            .setMessage("当前版本：v$currentVersion\n\n仓库暂未发布正式 Release，如需体验最新开发版构建，可前往 GitHub Actions 下载。")
                            .setPositiveButton("打开 Actions") { _, _ -> openUrl(activity, GITHUB_ACTIONS_URL) }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                    return@execute
                }
                if (code !in 200..299) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(activity, "检查更新失败 (HTTP $code)", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@execute
                }

                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val remoteVersion = tagName.removePrefix("v").removePrefix("V").ifBlank { currentVersion }
                val releaseUrl = json.optString("html_url", GITHUB_RELEASES_URL).ifBlank { GITHUB_RELEASES_URL }
                val releaseNotes = json.optString("body", "").trim().ifBlank { "包含最新功能优化与问题修复。" }

                var apkUrl = releaseUrl
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val dl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                            apkUrl = dl
                            break
                        }
                    }
                }

                val hasNewer = isNewerVersion(remoteVersion, currentVersion)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (hasNewer) {
                        val preview = if (releaseNotes.length > 360) {
                            releaseNotes.take(360) + "..."
                        } else {
                            releaseNotes
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("发现新版本 v$remoteVersion")
                            .setMessage("当前版本：v$currentVersion\n最新版本：v$remoteVersion\n\n更新说明：\n$preview")
                            .setPositiveButton("前往下载") { _, _ -> openUrl(activity, apkUrl) }
                            .setNeutralButton("查看发布页") { _, _ -> openUrl(activity, releaseUrl) }
                            .setNegativeButton("稍后再说", null)
                            .show()
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("已是最新版本")
                            .setMessage("当前版本 v$currentVersion 已是最新正式版（GitHub 最新 Release：v$remoteVersion）。")
                            .setPositiveButton("查看 Releases") { _, _ -> openUrl(activity, releaseUrl) }
                            .setNegativeButton("确定", null)
                            .show()
                    }
                }
            } catch (_: Throwable) {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, "检查更新失败，请检查网络连接", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val lv = lParts.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun showSettingsDialog(
        activity: Activity,
        currentThemeMode: Int,
        onApplyThemeMode: (Int) -> Unit
    ) {
        val scroll = ScrollView(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 8))
        }
        scroll.addView(root)

        fun addSectionTitle(title: String, topMarginDp: Int = 0) {
            val tv = TextView(activity).apply {
                text = title
                setTextColor(activity.getColor(R.color.accent_blue_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(activity, topMarginDp), 0, dp(activity, 6))
            }
            root.addView(tv)
        }

        fun addNumberRow(
            label: String,
            unit: String,
            initialValue: String,
            allowNegative: Boolean = false
        ): EditText {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 3), 0, dp(activity, 3))
            }
            val tvLabel = TextView(activity).apply {
                text = label
                setTextColor(activity.getColor(R.color.text_body))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f)
            }
            val et = EditText(activity).apply {
                setText(initialValue)
                setTextColor(activity.getColor(R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                inputType = if (allowNegative) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvUnit = TextView(activity).apply {
                text = unit
                setTextColor(activity.getColor(R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(activity, 6), 0, 0, 0)
            }
            row.addView(tvLabel)
            row.addView(et)
            row.addView(tvUnit)
            root.addView(row)
            return et
        }

        addSectionTitle("移动速度参数（摇杆与轨迹回放通用）")
        val etWalk = addNumberRow("步行速度", "m/s", String.format(Locale.US, "%.1f", RouteManager.walkSpeedMps))
        val etRun = addNumberRow("跑步速度", "m/s", String.format(Locale.US, "%.1f", RouteManager.runSpeedMps))
        val etBike = addNumberRow("骑行速度", "m/s", String.format(Locale.US, "%.1f", RouteManager.bikeSpeedMps))
        val etDrive = addNumberRow("驾驶速度", "m/s", String.format(Locale.US, "%.1f", RouteManager.driveSpeedMps))

        addSectionTitle("位置海拔高度", topMarginDp = 12)
        val currentAlt = ConfigManager.getAltitude(activity)
        val etAltitude = addNumberRow(
            "模拟海拔",
            "米",
            String.format(Locale.US, "%.1f", currentAlt),
            allowNegative = true
        )

        addSectionTitle("界面外观（夜间模式）", topMarginDp = 12)
        val rgTheme = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbSystem = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "跟随系统"
            setTextColor(activity.getColor(R.color.text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            isChecked = currentThemeMode == LocationDrifterApp.THEME_MODE_SYSTEM
        }
        val rbLight = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "浅色"
            setTextColor(activity.getColor(R.color.text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            isChecked = currentThemeMode == LocationDrifterApp.THEME_MODE_LIGHT
        }
        val rbDark = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "夜间"
            setTextColor(activity.getColor(R.color.text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            isChecked = currentThemeMode == LocationDrifterApp.THEME_MODE_DARK
        }
        rgTheme.addView(rbSystem)
        rgTheme.addView(rbLight)
        rgTheme.addView(rbDark)
        root.addView(rgTheme)

        AlertDialog.Builder(activity)
            .setTitle("参数设置")
            .setView(scroll)
            .setPositiveButton("保存设置") { _, _ ->
                val walk = etWalk.text.toString().trim().toFloatOrNull() ?: RouteManager.DEFAULT_WALK_SPEED
                val run = etRun.text.toString().trim().toFloatOrNull() ?: RouteManager.DEFAULT_RUN_SPEED
                val bike = etBike.text.toString().trim().toFloatOrNull() ?: RouteManager.DEFAULT_BIKE_SPEED
                val drive = etDrive.text.toString().trim().toFloatOrNull() ?: RouteManager.DEFAULT_DRIVE_SPEED
                val alt = etAltitude.text.toString().trim().toDoubleOrNull() ?: ConfigManager.DEFAULT_ALTITUDE

                RouteManager.saveCustomSpeeds(activity, walk, run, bike, drive)
                ConfigManager.setAltitude(activity, alt)

                val selectedTheme = when (rgTheme.checkedRadioButtonId) {
                    rbLight.id -> LocationDrifterApp.THEME_MODE_LIGHT
                    rbDark.id -> LocationDrifterApp.THEME_MODE_DARK
                    else -> LocationDrifterApp.THEME_MODE_SYSTEM
                }

                // 同步刷新主界面轨迹速度按钮文字（若已展示）
                val btnRouteSpeed = activity.findViewById<Button>(R.id.btnRouteSpeed)
                if (btnRouteSpeed != null) {
                    val activeSpeed = RouteManager.getActiveRouteSpeed(activity)
                    btnRouteSpeed.text = RouteManager.speedShortLabel(activeSpeed)
                }

                if (selectedTheme != currentThemeMode) {
                    onApplyThemeMode(selectedTheme)
                } else {
                    Toast.makeText(activity, "参数设置已保存 ✓", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("恢复默认") { _, _ ->
                RouteManager.resetCustomSpeeds(activity)
                ConfigManager.setAltitude(activity, ConfigManager.DEFAULT_ALTITUDE)
                Toast.makeText(activity, "已恢复默认速度与海拔 (36.0m)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAboutDialog(activity: Activity) {
        val versionName = resolveVersionName(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 8))
        }

        fun addInfoRow(label: String, value: String, isLink: Boolean = false) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            }
            val tvLabel = TextView(activity).apply {
                text = label
                setTextColor(activity.getColor(R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
            val tvVal = TextView(activity).apply {
                text = value
                setTextColor(
                    activity.getColor(if (isLink) R.color.accent_blue else R.color.text_primary)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = if (isLink) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(0, dp(activity, 2), 0, 0)
                if (isLink) {
                    setOnClickListener { openUrl(activity, GITHUB_REPO_URL) }
                }
            }
            row.addView(tvLabel)
            row.addView(tvVal)
            root.addView(row)
        }

        addInfoRow("应用名称", "LocationDrifter (定位漂移助手)")
        addInfoRow("当前版本", "v$versionName")
        addInfoRow("作者", AUTHOR_NAME)
        addInfoRow("GitHub 仓库（点击访问）", GITHUB_REPO_URL, isLink = true)

        val tvDesc = TextView(activity).apply {
            text = "零三方应用注入架构：支持 Root + LSPosed 系统级防漂移、Shizuku 增强及免 Root 降级模式，内置悬浮摇杆、轨迹规划与实验性步数模拟。"
            setTextColor(activity.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            setPadding(0, dp(activity, 10), 0, dp(activity, 2))
        }
        root.addView(tvDesc)

        AlertDialog.Builder(activity)
            .setTitle("关于 LocationDrifter")
            .setView(root)
            .setPositiveButton("打开 GitHub") { _, _ ->
                openUrl(activity, GITHUB_REPO_URL)
            }
            .setNeutralButton("复制链接") { _, _ ->
                try {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("GitHub", GITHUB_REPO_URL))
                    Toast.makeText(activity, "已复制 GitHub 链接", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(activity, "无法打开浏览器，请复制链接访问", Toast.LENGTH_SHORT).show()
        }
    }
}
