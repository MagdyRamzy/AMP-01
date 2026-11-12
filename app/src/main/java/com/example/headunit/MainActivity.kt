package com.example.headunit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.ToneGenerator
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale

data class AppItem(val label: String, val pkg: String, val icon: Drawable)

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val limits = intArrayOf(60, 80, 120)
    private val levelColors = intArrayOf(Color.WHITE, 0xFFFFC107.toInt(), 0xFFFF7043.toInt(), 0xFFFF1744.toInt())

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var lm: LocationManager
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var accent = 0
    private var muted = false
    private var askedPerms = false

    private var apps = listOf<AppItem>()
    private lateinit var root: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var speedTv: TextView
    private lateinit var warnTv: TextView
    private lateinit var muteTv: TextView
    private lateinit var kmhTv: TextView
    private lateinit var clockTv: TextClock
    private lateinit var dateTv: TextClock
    private var uiScale = 1f
    private lateinit var appsView: View
    private lateinit var vizPanel: View
    private lateinit var vizView: VisualizerView
    private lateinit var modeTv: TextView
    private lateinit var titleTv: TextView
    private val chips = mutableListOf<TextView>()
    private var lastLevel = 0
    private var testSpeed = 0
    private var viz: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shade(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    private fun cardBg(color: Int = 0xCC1E1E1E.toInt()) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(26).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("hu", Context.MODE_PRIVATE)
        accent = prefs.getInt("accent", 0xFFE8838F.toInt())
        muted = prefs.getBoolean("mute", false)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS; if (ttsReady) tts.language = Locale.getDefault() }
        loadApps()
        buildUi()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onBackPressed() {}

    // ---------------------------------------------------------------- apps
    private fun loadApps() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(i, 0)
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .filter { it.pkg != packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun launch(a: AppItem) { packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it) } }

    private fun pinned(): MutableList<String> {
        val saved = prefs.getString("pinned", null)
        return if (saved != null) saved.split(",").filter { it.isNotBlank() }.toMutableList()
        else apps.take(5).map { it.pkg }.toMutableList()
    }

    private fun togglePin(a: AppItem) {
        val p = pinned()
        if (!p.remove(a.pkg)) { if (p.size >= 6) p.removeAt(0); p.add(a.pkg) }
        prefs.edit().putString("pinned", p.joinToString(",")).apply()
        refreshDock()
        Toast.makeText(this, "${a.label}: dock updated", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- UI
    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockTv = TextClock(this).apply {                    // always 12-hour, even if the system is set to 24h
            format12Hour = "h:mm a"; format24Hour = "h:mm a"; textSize = 40f; setTextColor(Color.WHITE)
        }
        dateTv = TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"; textSize = 16f
            setTextColor(0xCCFFFFFF.toInt())
        }
        header.addView(clockTv); header.addView(dateTv)
        root.addView(header)

        val right = FrameLayout(this).apply { background = cardBg() }
        appsView = buildAppGrid()
        vizPanel = buildVizPanel()
        right.addView(appsView, FrameLayout.LayoutParams(MATCH, MATCH))
        right.addView(vizPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        applyWidgetVisibility()

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mid.addView(buildSpeedCard(), LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(14) })
        mid.addView(right, LinearLayout.LayoutParams(0, MATCH, 2.2f))
        root.addView(mid, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6)); background = cardBg(0xDD111111.toInt())
        }
        root.addView(dock, LinearLayout.LayoutParams(MATCH, dp(78)))
        refreshDock()
        applyTheme()
        root.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ -> applyScale((b - t) / resources.displayMetrics.density) }
        setContentView(root)
    }

    private fun applyTheme() {
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(shade(accent, 0.85f), shade(accent, 0.35f), 0xFF101015.toInt()))
        if (::vizView.isInitialized) vizView.accent = accent
    }

    private fun buildSpeedCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnLongClickListener {                       // test mode: cycles fake speeds
                testSpeed = if (testSpeed >= 130) 0 else testSpeed + 20
                updateSpeed(testSpeed); true
            }
        }
        speedTv = TextView(this).apply {
            text = "0"; textSize = 72f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false
        }
        card.addView(speedTv)
        kmhTv = TextView(this).apply { text = "km/h"; textSize = 16f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER }
        card.addView(kmhTv)
        warnTv = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; minHeight = dp(24) }
        card.addView(warnTv)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        limits.forEach { l ->
            val c = TextView(this).apply {
                text = "$l"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(dp(12), dp(3), dp(12), dp(3)); background = cardBg(0xFF333333.toInt())
            }
            chips.add(c)
            row.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(3), dp(4), dp(3), 0) })
        }
        card.addView(row)
        muteTv = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener {
                muted = !muted; prefs.edit().putBoolean("mute", muted).apply()
                if (muted) tts.stop(); updateMuteButton()
            }
        }
        card.addView(muteTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
        updateMuteButton()
        // scrolls as a safety net on very short screens
        return ScrollView(this).apply {
            background = cardBg(); isFillViewport = true; isVerticalScrollBarEnabled = false
            addView(card, ViewGroup.LayoutParams(MATCH, WRAP))
        }
    }

    /** Shrinks text, spacing and dock to fit short screens (phones in landscape). */
    private fun applyScale(hDp: Float) {
        val sc = (hDp / 460f).coerceIn(0.6f, 1f)
        if (kotlin.math.abs(sc - uiScale) < 0.02f) return
        uiScale = sc
        root.post {
            clockTv.textSize = 40f * sc; dateTv.textSize = 16f * sc
            speedTv.textSize = 72f * sc; kmhTv.textSize = 16f * sc
            warnTv.textSize = 15f * sc; warnTv.minHeight = dp((24 * sc).toInt())
            chips.forEach { it.textSize = 15f * sc; it.setPadding(dp((12 * sc).toInt()), dp(3), dp((12 * sc).toInt()), dp(3)) }
            muteTv.textSize = 14f * sc
            muteTv.setPadding(dp((14 * sc).toInt()), dp((6 * sc).toInt()), dp((14 * sc).toInt()), dp((6 * sc).toInt()))
            (muteTv.layoutParams as LinearLayout.LayoutParams).topMargin = dp((10 * sc).toInt())
            dock.layoutParams.height = dp((78 * sc).toInt())
            refreshDock()
            root.requestLayout()
        }
    }

    private fun updateMuteButton() {
        muteTv.text = if (muted) "🔕  Alerts muted" else "🔔  Alerts on"
        muteTv.background = cardBg(if (muted) 0xFF5A2A2A.toInt() else 0xFF333333.toInt())
    }

    private fun buildAppGrid(): View = GridView(this).apply {
        numColumns = 5; verticalSpacing = dp(8); horizontalSpacing = dp(8)
        setPadding(dp(10), dp(10), dp(10), dp(10)); clipToPadding = false
        selector = ColorDrawable(Color.TRANSPARENT)
        adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val a = apps[p]
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    addView(ImageView(context).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(56), dp(56)))
                    addView(TextView(context).apply {
                        text = a.label; textSize = 12f; setTextColor(Color.WHITE)
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    })
                }
            }
        }
        setOnItemClickListener { _, _, p, _ -> launch(apps[p]) }
        setOnItemLongClickListener { _, _, p, _ -> togglePin(apps[p]); true }
    }

    // ---------------------------------------------------------------- music widget
    private fun buildVizPanel(): View {
        val f = FrameLayout(this)
        vizView = VisualizerView(this).apply {
            accent = this@MainActivity.accent; mode = prefs.getInt("vmode", 0)
            setOnClickListener { cycleMode(1) }
            setOnLongClickListener { cycleMode(-1); true }
        }
        f.addView(vizView, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = dp(52) })
        modeTv = TextView(this).apply { textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(dp(16), dp(10), 0, 0) }
        f.addView(modeTv, FrameLayout.LayoutParams(WRAP, WRAP))

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(10), dp(6)) }
        titleTv = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { if (!hasListenerAccess()) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        bar.addView(titleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        fun btn(t: String, action: () -> Unit) = TextView(this).apply {
            text = t; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
        bar.addView(btn("⏮") { controller()?.transportControls?.skipToPrevious() }, LinearLayout.LayoutParams(dp(52), dp(44)))
        bar.addView(btn("⏯") {
            controller()?.let { c ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            }
        }, LinearLayout.LayoutParams(dp(52), dp(44)))
        bar.addView(btn("⏭") { controller()?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(dp(52), dp(44)))
        f.addView(bar, FrameLayout.LayoutParams(MATCH, dp(52), Gravity.BOTTOM))
        updateModeLabel()
        return f
    }

    private fun cycleMode(d: Int) {
        val n = VisualizerView.NAMES.size
        vizView.mode = (vizView.mode + d + n) % n
        prefs.edit().putInt("vmode", vizView.mode).apply()
        updateModeLabel()
    }

    private fun updateModeLabel() {
        modeTv.text = VisualizerView.NAMES[vizView.mode] + "  ·  tap to change" + if (vizView.simulated) "  ·  demo" else ""
    }

    private fun applyWidgetVisibility() {
        val on = prefs.getBoolean("viz", false)
        appsView.visibility = if (on) View.GONE else View.VISIBLE
        vizPanel.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun hasListenerAccess() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun controller(): MediaController? = try {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = msm.getActiveSessions(ComponentName(packageName, "com.example.headunit.NowPlayingService"))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    } catch (e: Exception) { null }

    private val ticker = object : Runnable {
        override fun run() {
            val md = controller()?.metadata
            titleTv.text = when {
                md != null -> listOfNotNull(md.getString(MediaMetadata.METADATA_KEY_TITLE),
                    md.getString(MediaMetadata.METADATA_KEY_ARTIST)).joinToString(" — ")
                !BuildConfig.FULL -> "Lite build: demo visualizer"
                !hasListenerAccess() -> "Tap to allow track info"
                else -> "Nothing playing"
            }
            updateModeLabel()
            handler.postDelayed(this, 1000)
        }
    }

    private fun startViz() {
        stopViz()
        if (!BuildConfig.FULL || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { vizView.simulated = true; return }
        try {
            val v = Visualizer(0)                      // 0 = whatever is playing on the device
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, rate: Int) { w?.let { vizView.feedWave(it) } }
                override fun onFftDataCapture(vz: Visualizer?, f: ByteArray?, rate: Int) { f?.let { vizView.feedFft(it) } }
            }, Visualizer.getMaxCaptureRate() / 2, true, true)
            v.enabled = true
            viz = v; vizView.simulated = false
        } catch (e: Exception) { vizView.simulated = true }
    }

    private fun stopViz() { try { viz?.enabled = false; viz?.release() } catch (_: Exception) {}; viz = null }

    // ---------------------------------------------------------------- dock + colours
    private fun refreshDock() {
        dock.removeAllViews()
        val ic = (58 * uiScale * resources.displayMetrics.density).toInt()
        pinned().mapNotNull { pk -> apps.find { it.pkg == pk } }.forEach { a ->
            dock.addView(ImageView(this).apply { setImageDrawable(a.icon); setOnClickListener { launch(a) } },
                LinearLayout.LayoutParams(ic, ic).apply { rightMargin = dp(16) })
        }
        dock.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val on = prefs.getBoolean("viz", false)
        dock.addView(TextView(this).apply {
            text = if (on) "▦" else "🎵"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setOnClickListener { prefs.edit().putBoolean("viz", !on).apply(); applyWidgetVisibility(); refreshDock() }
        }, LinearLayout.LayoutParams(ic, ic))
        dock.addView(TextView(this).apply {
            text = "🎨"; textSize = 30f; gravity = Gravity.CENTER
            setOnClickListener { showColorDialog() }
        }, LinearLayout.LayoutParams(ic, ic))
    }

    private fun setAccent(c: Int) { accent = c; prefs.edit().putInt("accent", c).apply(); applyTheme() }

    private fun showColorDialog() {
        val presets = intArrayOf(0xFFE8838F.toInt(), 0xFF42A5F5.toInt(), 0xFF26A69A.toInt(), 0xFFAB47BC.toInt(),
            0xFFFFA726.toInt(), 0xFF66BB6A.toInt(), 0xFFEF5350.toInt(), 0xFF8D6E63.toInt())
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val row = LinearLayout(this)
        presets.forEach { c ->
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener { setAccent(c) }           // live update, dialog stays open
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        }
        box.addView(row)
        box.addView(TextView(this).apply { text = "Custom hue"; setPadding(0, dp(14), 0, 0) })
        val hsv = FloatArray(3); Color.colorToHSV(accent, hsv)
        box.addView(SeekBar(this).apply {
            max = 360; progress = hsv[0].toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                    if (user) setAccent(Color.HSVToColor(floatArrayOf(p.toFloat(), 0.55f, 0.95f)))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}   // nothing: dialog stays open
            })
        })
        AlertDialog.Builder(this).setTitle("Theme colour").setView(box).setPositiveButton("Done", null).show()
    }

    // ---------------------------------------------------------------- speed
    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) { if (l.hasSpeed()) updateSpeed((l.speed * 3.6f).toInt()) }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private fun updateSpeed(kmh: Int) {
        speedTv.text = kmh.toString()
        val level = limits.count { kmh >= it }
        speedTv.setTextColor(levelColors[level])
        chips.forEachIndexed { i, c -> c.background = cardBg(if (level > i) levelColors[i + 1] else 0xFF333333.toInt()) }
        if (level > lastLevel) { lastLevel = level; warn(limits[level - 1]) }
        else if (level < lastLevel && kmh <= limits[lastLevel - 1] - 3) {
            lastLevel = level; if (level == 0) warnTv.text = ""
        }
    }

    private fun warn(limit: Int) {
        warnTv.text = "⚠ Slow down: over $limit km/h"      // banner always shows
        if (muted) return                                  // beep + voice only when not muted
        try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700) } catch (_: Exception) {}
        if (ttsReady) tts.speak("Warning. Speed over $limit kilometers per hour.", TextToSpeech.QUEUE_FLUSH, null, "warn")
    }

    // ---------------------------------------------------------------- lifecycle / permissions
    private fun ensurePerms() {
        if (askedPerms) return
        val need = listOfNotNull(Manifest.permission.ACCESS_FINE_LOCATION, if (BuildConfig.FULL) Manifest.permission.RECORD_AUDIO else null)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { askedPerms = true; requestPermissions(need.toTypedArray(), 1) }
    }

    private fun startGps() {
        try { lm.removeUpdates(locListener) } catch (_: Exception) {}
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locListener) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); startGps(); startViz()
    }

    override fun onResume() {
        super.onResume(); loadApps(); refreshDock(); ensurePerms(); startGps(); startViz()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause(); lm.removeUpdates(locListener); stopViz(); handler.removeCallbacks(ticker)
    }

    override fun onDestroy() { tts.shutdown(); super.onDestroy() }
}
