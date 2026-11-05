package com.example.myapplication.presentation

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // ---- Colors ----
    private val green = Color.parseColor("#30D158")
    private val red = Color.parseColor("#FF453A")
    private val amber = Color.parseColor("#FFD60A")
    private val blue = Color.parseColor("#0A84FF")
    private val recentBlue = Color.parseColor("#1C3A5E")
    private val grey = Color.parseColor("#8E8E93")
    private val darkGrey = Color.parseColor("#2C2C2E")

    // ---- Screen size: every size below is a fraction of this ----
    private var screen = 0f

    // ---- Which screen is showing ----
    private enum class Page { HOME, PROFILE, PICK, TYPE, COUNTDOWN, ACTIVITY, SAVED }
    private var page = Page.HOME
    private lateinit var container: FrameLayout

    // ---- HOME pieces we update every second ----
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var timer: TextView
    private lateinit var filesValue: TextView
    private lateinit var sizeValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var activityButton: TextView
    private lateinit var mainButton: TextView
    private lateinit var profileLine: TextView
    private lateinit var batteryWarning: TextView
    private var stopArmed = false

    // ---- PROFILE ----
    private lateinit var profileValue: TextView
    private lateinit var leftButton: TextView
    private lateinit var rightButton: TextView
    private var profileNumber = 1
    private var profileWrist = "left"
    private var profileThenPick = false

    // ---- TYPE ("Other…") ----
    private lateinit var typeField: EditText
    private lateinit var typeError: TextView

    // ---- COUNTDOWN ----
    private lateinit var countNumber: TextView
    private var countLeft = 0
    private var countActivity = ""

    // ---- ACTIVITY (labeling) ----
    private lateinit var actDot: View
    private lateinit var actTimer: TextView
    private lateinit var actWarning: TextView
    private lateinit var actStop: TextView
    private var actStopArmed = false

    // ---- SAVED ----
    private var lastSaved: Labels.Active? = null
    private var lastSavedSeconds = 0L

    private val ui = Handler(Looper.getMainLooper())
    private var pulse: ObjectAnimator? = null

    private val autoRefresh = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private val disarmStop = Runnable {
        stopArmed = false
        actStopArmed = false
        refresh()
    }

    private val countdownStep = object : Runnable {
        override fun run() {
            countLeft--
            if (countLeft > 0) {
                countNumber.text = countLeft.toString()
                buzz(40)
                ui.postDelayed(this, 1000)
            } else {
                beginActivity()
            }
        }
    }

    private val savedTimeout = Runnable { if (page == Page.SAVED) show(Page.HOME) }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dm = resources.displayMetrics
        screen = minOf(dm.widthPixels, dm.heightPixels) * 1.1f

        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(container)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // If a label is running (app was closed, or the watch restarted), go straight back to it
        show(if (Labels.active(this) != null) Page.ACTIVITY else Page.HOME)
    }

    override fun onResume() {
        super.onResume()
        val labeling = Labels.active(this) != null
        if (labeling && page == Page.HOME) show(Page.ACTIVITY)
        if (!labeling && page == Page.ACTIVITY) show(Page.HOME)
        ui.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(autoRefresh)
        pulse?.cancel()
        pulse = null
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)    // e.g. a countdown that was still running
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (page) {
            Page.HOME -> super.onBackPressed()
            Page.COUNTDOWN -> cancelCountdown()
            Page.TYPE -> show(Page.PICK)
            else -> show(Page.HOME)            // from ACTIVITY: the label keeps running
        }
    }

    /** Swap the whole screen for another page. */
    private fun show(p: Page) {
        page = p
        pulse?.cancel(); pulse = null
        stopArmed = false; actStopArmed = false
        ui.removeCallbacks(disarmStop)
        ui.removeCallbacks(savedTimeout)
        if (p != Page.COUNTDOWN) ui.removeCallbacks(countdownStep)
        container.removeAllViews()
        container.addView(
            when (p) {
                Page.HOME -> buildHome()
                Page.PROFILE -> buildProfile()
                Page.PICK -> buildPick()
                Page.TYPE -> buildType()
                Page.COUNTDOWN -> buildCountdown()
                Page.ACTIVITY -> buildActivity()
                Page.SAVED -> buildSaved()
            }
        )
        refresh()
    }

    private fun refresh() {
        when (page) {
            Page.HOME -> refreshHome()
            Page.PROFILE -> refreshProfile()
            Page.ACTIVITY -> refreshActivity()
            else -> {}
        }
    }

    // =====================================================================
    // HOME: recording status, "Start activity", Start/Stop recording
    // =====================================================================

    private fun buildHome(): View {
        val title = text(0.045f, grey).apply {
            text = "HAR RECORDER"
            letterSpacing = 0.15f
        }

        statusDot = dot()
        statusLabel = text(0.055f, grey, medium = true).apply { letterSpacing = 0.08f }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(statusDot, LinearLayout.LayoutParams(px(0.032f), px(0.032f)).apply { marginEnd = px(0.02f) })
            addView(statusLabel)
        }

        timer = text(0.12f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }

        val (filesCol, fv) = statColumn("files")
        val (sizeCol, sv) = statColumn("MB")
        val (battCol, bv) = statColumn("battery")
        filesValue = fv; sizeValue = sv; batteryValue = bv
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(filesCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sizeCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(battCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        activityButton = button { onActivityButton() }
        mainButton = button { onMainButton() }

        profileLine = text(0.042f, grey).apply { setOnClickListener { openProfile(thenPick = false) } }

        batteryWarning = text(0.045f, amber).apply {
            text = "⚠ Allow background use"
            setOnClickListener { askBatteryExemption() }
        }

        val c = column().apply {
            addView(title)
            addView(statusRow, below(0.02f))
            addView(timer, below(0.0f))
            addView(statsRow, LinearLayout.LayoutParams(px(0.66f), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(0.01f) })
            addView(activityButton, buttonParams(0.035f))
            addView(mainButton, buttonParams(0.02f))
            addView(profileLine, below(0.03f))
            addView(batteryWarning, below(0.02f))
        }
        return pageView(c, pad = 0.07f)
    }

    private fun onActivityButton() {
        when {
            Labels.active(this) != null -> show(Page.ACTIVITY)
            !Prefs.hasProfile(this) -> openProfile(thenPick = true)   // first time: who is wearing it?
            else -> show(Page.PICK)
        }
    }

    private fun onMainButton() {
        when {
            !HarRecorderService.isRunning -> {
                HarRecorderService.start(this)
                ui.postDelayed({ refresh() }, 300)
            }
            !stopArmed -> {
                stopArmed = true
                refresh()
                ui.postDelayed(disarmStop, 3000)
            }
            else -> {
                ui.removeCallbacks(disarmStop)
                stopArmed = false
                Labels.stop(this, "recording_stopped")      // close a running label first
                HarRecorderService.stop(this)
                ui.postDelayed({ refresh() }, 500)
            }
        }
    }

    private fun refreshHome() {
        val running = HarRecorderService.isRunning

        val files = HarRecorderService.chunkDir(this)
            .listFiles { f -> f.name.endsWith(".bin") } ?: emptyArray()
        filesValue.text = files.size.toString()
        sizeValue.text = String.format(Locale.US, "%.1f", files.sumOf { it.length() } / 1_000_000.0)
        batteryValue.text = "${batteryPct()}%"

        if (running) {
            statusLabel.text = when {
                !LiveConfig.ENABLED -> "RECORDING"
                LiveUploader.connected -> "REC · LIVE"
                else -> "REC · OFFLINE"
            }
            statusLabel.setTextColor(green)
            setDotColor(statusDot, green)
            startPulse(statusDot)
            val s = (SystemClock.elapsedRealtime() - HarRecorderService.startedAtMs) / 1000
            timer.text = hms(s)
            timer.setTextColor(Color.WHITE)
            if (stopArmed) style(mainButton, "Tap again to stop", red, Color.WHITE)
            else style(mainButton, "Stop recording", darkGrey, red)
        } else {
            statusLabel.text = "STOPPED"
            statusLabel.setTextColor(grey)
            setDotColor(statusDot, grey)
            pulse?.cancel(); pulse = null
            statusDot.alpha = 1f
            timer.text = "--:--:--"
            timer.setTextColor(grey)
            style(mainButton, "Start recording", green, Color.BLACK)
        }

        val a = Labels.active(this)
        if (a != null) style(activityButton, "● " + Labels.pretty(a.activity), recentBlue, Color.WHITE)
        else style(activityButton, "Start activity", blue, Color.WHITE)

        profileLine.text = Prefs.participant(this)
            ?.let { "$it · ${Prefs.wrist(this)} wrist · edit" }
            ?: "Set participant"

        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryWarning.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    // =====================================================================
    // PROFILE: participant number and wrist
    // =====================================================================

    private fun openProfile(thenPick: Boolean) {
        profileThenPick = thenPick
        profileNumber = Prefs.participant(this)?.removePrefix("P")?.toIntOrNull() ?: 1
        profileWrist = Prefs.wrist(this)
        show(Page.PROFILE)
    }

    private fun buildProfile(): View {
        val heading = text(0.045f, grey).apply { text = "WHO IS WEARING IT?"; letterSpacing = 0.08f }

        val minus = button { profileNumber = if (profileNumber <= 1) 99 else profileNumber - 1; refresh() }
        val plus = button { profileNumber = if (profileNumber >= 99) 1 else profileNumber + 1; refresh() }
        style(minus, "−", darkGrey, Color.WHITE)
        style(plus, "+", darkGrey, Color.WHITE)
        profileValue = text(0.1f, Color.WHITE, medium = true).apply { fontFeatureSettings = "tnum" }
        val numberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(minus, LinearLayout.LayoutParams(px(0.14f), px(0.14f)))
            addView(profileValue, LinearLayout.LayoutParams(px(0.3f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(plus, LinearLayout.LayoutParams(px(0.14f), px(0.14f)))
        }
        val numberCaption = text(0.04f, grey).apply { text = "participant" }

        leftButton = button { profileWrist = "left"; refresh() }
        rightButton = button { profileWrist = "right"; refresh() }
        val wristRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(leftButton, LinearLayout.LayoutParams(px(0.29f), px(0.12f)).apply { marginEnd = px(0.02f) })
            addView(rightButton, LinearLayout.LayoutParams(px(0.29f), px(0.12f)))
        }
        val wristCaption = text(0.04f, grey).apply { text = "wrist" }

        val save = button { saveProfile() }
        style(save, "Save", green, Color.BLACK)

        val device = text(0.036f, grey).apply { text = "watch ${Prefs.deviceId(this@MainActivity)}" }

        val c = column().apply {
            addView(heading)
            addView(numberRow, below(0.03f))
            addView(numberCaption, below(0.005f))
            addView(wristRow, below(0.03f))
            addView(wristCaption, below(0.01f))
            addView(save, buttonParams(0.035f))
            addView(device, below(0.025f))
        }
        return pageView(c)
    }

    private fun refreshProfile() {
        profileValue.text = String.format(Locale.US, "P%02d", profileNumber)
        style(leftButton, "Left", if (profileWrist == "left") blue else darkGrey,
            if (profileWrist == "left") Color.WHITE else grey)
        style(rightButton, "Right", if (profileWrist == "right") blue else darkGrey,
            if (profileWrist == "right") Color.WHITE else grey)
    }

    private fun saveProfile() {
        Labels.setProfile(this, String.format(Locale.US, "P%02d", profileNumber), profileWrist)
        show(if (profileThenPick) Page.PICK else Page.HOME)
    }

    // =====================================================================
    // PICK: the list of activities
    // =====================================================================

    private fun buildPick(): View {
        val c = column()
        c.addView(text(0.045f, grey).apply { text = "CHOOSE ACTIVITY"; letterSpacing = 0.08f })

        val recent = Prefs.recentActivities(this).toSet()
        for ((i, activity) in Labels.choices(this).withIndex()) {
            val b = button { startCountdown(activity) }
            style(b, Labels.pretty(activity), if (activity in recent) recentBlue else darkGrey, Color.WHITE)
            c.addView(b, LinearLayout.LayoutParams(px(0.68f), px(0.13f))
                .apply { topMargin = px(if (i == 0) 0.03f else 0.015f) })
        }

        val other = button { show(Page.TYPE) }
        style(other, "Other…", Color.BLACK, blue)
        (other.background as GradientDrawable).setStroke(px(0.006f), blue)
        c.addView(other, LinearLayout.LayoutParams(px(0.68f), px(0.13f)).apply { topMargin = px(0.015f) })

        val cancel = text(0.05f, grey).apply { text = "Cancel"; setOnClickListener { show(Page.HOME) } }
        c.addView(cancel, below(0.04f))

        return pageView(c, pad = 0.15f)       // extra space so the first/last buttons reach the middle
    }

    // =====================================================================
    // TYPE: a new activity name (the watch keyboard or voice opens when you tap the box)
    // =====================================================================

    private fun buildType(): View {
        val heading = text(0.045f, grey).apply { text = "NEW ACTIVITY"; letterSpacing = 0.08f }

        typeField = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, screen * 0.06f)
            setTextColor(Color.WHITE)
            setHintTextColor(grey)
            hint = "e.g. Jumping jacks"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = screen * 0.04f; setColor(darkGrey) }
            setPadding(px(0.03f), px(0.025f), px(0.03f), px(0.025f))
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { startTyped(); true } else false
            }
        }
        typeError = text(0.04f, red)

        val start = button { startTyped() }
        style(start, "Start", green, Color.BLACK)
        val cancel = text(0.05f, grey).apply { text = "Cancel"; setOnClickListener { show(Page.PICK) } }

        val c = column().apply {
            addView(heading)
            addView(typeField, LinearLayout.LayoutParams(px(0.7f), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(0.03f) })
            addView(typeError, below(0.012f))
            addView(start, buttonParams(0.02f))
            addView(cancel, below(0.035f))
        }

        ui.postDelayed({
            if (page == Page.TYPE) {
                typeField.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(typeField, 0)
            }
        }, 300)
        return pageView(c)
    }

    private fun startTyped() {
        val activity = Labels.slug(typeField.text.toString())
        if (activity.isEmpty()) {
            typeError.text = "Type a name first"
            return
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(typeField.windowToken, 0)
        startCountdown(activity)
    }

    // =====================================================================
    // COUNTDOWN: 3, 2, 1, buzz, go
    // =====================================================================

    private fun startCountdown(activity: String) {
        countActivity = activity
        // Start recording now if it's off: the 3 seconds give the sensors time to settle
        if (!HarRecorderService.isRunning) HarRecorderService.start(this)
        countLeft = 3
        show(Page.COUNTDOWN)
        buzz(40)
        ui.postDelayed(countdownStep, 1000)
    }

    private fun buildCountdown(): View {
        val name = text(0.07f, Color.WHITE, medium = true).apply {
            text = Labels.pretty(countActivity)
            maxLines = 2
        }
        val hint = text(0.045f, grey).apply { text = "get ready" }
        countNumber = text(0.3f, blue).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            text = countLeft.toString()
        }
        val cancel = button { cancelCountdown() }
        style(cancel, "Cancel", darkGrey, Color.WHITE)

        val c = column().apply {
            addView(name, LinearLayout.LayoutParams(px(0.75f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hint, below(0.01f))
            addView(countNumber, below(0.01f))
            addView(cancel, LinearLayout.LayoutParams(px(0.45f), px(0.12f)).apply { topMargin = px(0.02f) })
        }
        return pageView(c)
    }

    private fun cancelCountdown() {
        ui.removeCallbacks(countdownStep)
        show(Page.HOME)
    }

    private fun beginActivity() {
        Labels.start(this, countActivity)
        buzz(300)
        show(Page.ACTIVITY)
    }

    // =====================================================================
    // ACTIVITY: name, timer, Stop (two taps)
    // =====================================================================

    private fun buildActivity(): View {
        val a = Labels.active(this)

        actDot = dot().also { setDotColor(it, green) }
        val label = text(0.045f, green, medium = true).apply { text = "LABELING"; letterSpacing = 0.1f }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(actDot, LinearLayout.LayoutParams(px(0.028f), px(0.028f)).apply { marginEnd = px(0.02f) })
            addView(label)
        }

        val name = text(0.085f, Color.WHITE, medium = true).apply {
            text = a?.let { Labels.pretty(it.activity) } ?: ""
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        actTimer = text(0.13f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        val who = text(0.04f, grey).apply {
            text = "${Prefs.participant(this@MainActivity) ?: "?"} · ${Prefs.wrist(this@MainActivity)} wrist"
        }
        actWarning = text(0.042f, amber).apply { text = "⚠ Recording is off" }
        actStop = button { onStopActivity() }

        val c = column().apply {
            addView(statusRow)
            addView(name, LinearLayout.LayoutParams(px(0.78f), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(0.025f) })
            addView(actTimer, below(0.01f))
            addView(who, below(0.005f))
            addView(actWarning, below(0.015f))
            addView(actStop, buttonParams(0.03f))
        }
        return pageView(c)
    }

    private fun refreshActivity() {
        val a = Labels.active(this)
        if (a == null) { show(Page.HOME); return }
        val s = maxOf(0L, (System.currentTimeMillis() - a.startWallMs) / 1000)
        actTimer.text = hms(s)
        actWarning.visibility = if (HarRecorderService.isRunning) View.GONE else View.VISIBLE
        if (actStopArmed) style(actStop, "Tap again to stop", red, Color.WHITE)
        else style(actStop, "Stop activity", darkGrey, red)
        startPulse(actDot)
    }

    private fun onStopActivity() {
        if (!actStopArmed) {
            actStopArmed = true
            refresh()
            ui.postDelayed(disarmStop, 3000)
            return
        }
        ui.removeCallbacks(disarmStop)
        val a = Labels.stop(this)
        lastSaved = a
        lastSavedSeconds = a?.let { (System.currentTimeMillis() - it.startWallMs) / 1000 } ?: 0
        buzz(150)
        show(Page.SAVED)
    }

    // =====================================================================
    // SAVED: keep (default) or discard
    // =====================================================================

    private fun buildSaved(): View {
        val a = lastSaved
        val check = text(0.12f, green).apply { text = "✓" }
        val title = text(0.065f, Color.WHITE, medium = true).apply { text = "Saved" }
        val detail = text(0.048f, grey).apply {
            text = a?.let { "${Labels.pretty(it.activity)} · ${hms(lastSavedSeconds)}" } ?: ""
            maxLines = 2
        }
        val done = button { show(Page.HOME) }
        style(done, "Done", green, Color.BLACK)
        val discard = button {
            a?.let { Labels.discard(this, it.id) }
            Toast.makeText(this, "Discarded", Toast.LENGTH_SHORT).show()
            show(Page.HOME)
        }
        style(discard, "Discard", darkGrey, red)

        val c = column().apply {
            addView(check)
            addView(title, below(0.01f))
            addView(detail, LinearLayout.LayoutParams(px(0.75f), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(0.01f) })
            addView(done, buttonParams(0.035f))
            addView(discard, buttonParams(0.02f))
        }
        ui.postDelayed(savedTimeout, 15_000)      // nobody tapped: it stays saved, go home
        return pageView(c)
    }

    // =====================================================================
    // Helpers: everything is a fraction of the screen
    // =====================================================================

    private fun px(fraction: Float) = (screen * fraction).toInt()

    private fun text(fraction: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, screen * fraction)
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(onClick: () -> Unit) = text(0.056f, Color.WHITE, medium = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(px(0.03f), 0, px(0.03f), 0)
        setOnClickListener { onClick() }
    }

    private fun style(view: TextView, label: String, fill: Int, textColor: Int) {
        view.text = label
        view.setTextColor(textColor)
        view.background = GradientDrawable().apply {
            cornerRadius = screen * 0.07f
            setColor(fill)
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    /** A centered column that scrolls (finger or bezel) when it doesn't fit on the screen. */
    private fun pageView(column: LinearLayout, pad: Float = 0.1f): View {
        column.setPadding(0, px(pad), 0, px(pad))
        val frame = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.BLACK)
            addView(frame)
            // Turning the bezel scrolls the page
            isFocusable = true
            isFocusableInTouchMode = true
            setOnGenericMotionListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_SCROLL &&
                    ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                ) {
                    val delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) *
                            ViewConfiguration.get(this@MainActivity).scaledVerticalScrollFactor
                    (v as ScrollView).smoothScrollBy(0, delta.roundToInt())
                    true
                } else false
            }
            post { requestFocus() }
        }
    }

    private fun below(fraction: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px(fraction) }

    private fun buttonParams(top: Float) =
        LinearLayout.LayoutParams(px(0.6f), px(0.13f)).apply { topMargin = px(top) }

    private fun statColumn(caption: String): Pair<LinearLayout, TextView> {
        val value = text(0.07f, Color.WHITE, medium = true).apply { fontFeatureSettings = "tnum" }
        val label = text(0.042f, grey).apply { text = caption }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(value)
            addView(label, below(0.008f))
        }
        return col to value
    }

    private fun dot() = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(grey) }
    }

    private fun setDotColor(dot: View, color: Int) {
        (dot.background as GradientDrawable).setColor(color)
    }

    private fun startPulse(dot: View) {
        if (pulse != null) return
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun hms(s: Long) =
        String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)

    private fun buzz(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // No vibration is not a problem
        }
    }

    private fun batteryPct(): Int =
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
        }
    }
}