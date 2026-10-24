package com.mumflorence.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home screen: animated orb, live status, one-tap setup and the doors to every setting. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var orb: OrbView
    private lateinit var chip: TextView
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var sleepBtn: Button
    private lateinit var rows: List<TextView>
    private lateinit var info: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var wizard = false
    private val asked = HashSet<String>()
    private var tick = 0

    private val poll = object : Runnable {
        override fun run() {
            updateLive()
            if (tick++ % 3 == 0) refreshStatus()
            ui.postDelayed(this, 600)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        Theme.styleWindow(this)
        buildUi()
    }

    private fun dp(v: Int) = Theme.dp(this, v)

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(32)) }
        val animated = ArrayList<View>()
        fun add(v: View, top: Int = 12) { v.layoutParams = lp(top); root.addView(v); animated.add(v) }

        val avatar = ImageView(this).apply {
            val id = resources.getIdentifier("mom_photo", "drawable", packageName)
            if (id != 0) setImageResource(id)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
        }
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(3), Theme.ACCENT2) }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            addView(avatar, FrameLayout.LayoutParams(dp(98), dp(98)))
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) }
        }
        root.addView(ring); animated.add(ring)
        ValueAnimator.ofFloat(1f, 1.07f).apply {
            duration = 1500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { ring.scaleX = it.animatedValue as Float; ring.scaleY = it.animatedValue as Float }
            start()
        }

        title = TextView(this).apply {
            textSize = 30f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        add(title, 0)
        hint = TextView(this).apply { textSize = 14f; setTextColor(Theme.MUTED); gravity = Gravity.CENTER }
        add(hint, 4)

        orb = OrbView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)) }
        root.addView(orb); animated.add(orb)

        chip = TextView(this).apply {
            textSize = 14f; setTextColor(Theme.TEXT); gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8)); background = Theme.card(this@MainActivity, Theme.CARD2, 24)
        }
        add(chip, 0)
        (chip.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        chip.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }

        add(button("Set up everything", true) { wizard = true; asked.clear(); nextStep() }, 20)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sleepBtn = button("Sleep", false) {
            val s = FlorenceService.instance
            if (s == null) toast("She is not running yet. Tap Set up everything.")
            else { s.setSleeping(!s.isSleeping()); ui.postDelayed({ updateLive() }, 400) }
        }
        val stopBtn = button("Stop talking", false) { FlorenceService.instance?.stopNow() }
        for (b in listOf(sleepBtn, stopBtn)) b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        row.addView(sleepBtn); row.addView(stopBtn)
        add(row, 10)

        // checklist card
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = Theme.card(this@MainActivity) }
        rows = (0 until 5).map { TextView(this).apply { textSize = 14f; setTextColor(Theme.TEXT); setPadding(0, dp(4), 0, dp(4)); card.addView(this) } }
        info = TextView(this).apply { textSize = 13f; setTextColor(Theme.MUTED); setPadding(0, dp(8), 0, 0); card.addView(this) }
        add(card, 18)

        // door cards
        fun door(emoji: String, label: String, cls: Class<*>): View = door2(emoji, label) { startActivity(Intent(this@MainActivity, cls)) }
        fun door(emoji: String, label: String, onClick: () -> Unit): View = door2(emoji, label, onClick)
        fun doorRow(a: View, b: View): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in listOf(a, b)) { v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }; addView(v) }
        }
        add(doorRow(door("⚙️", "Settings", SettingsActivity::class.java), door("🧠", "What she knows", MemoryActivity::class.java)), 14)
        add(doorRow(door("✨", "My commands", CommandsActivity::class.java), door("🎙️", "Voices", VoicesActivity::class.java)), 0)
        add(doorRow(door("💡", "Ideas", IdeasActivity::class.java), door("📚", "Learn from my texts") {
            val sv = FlorenceService.instance
            if (sv == null) toast("She is not running yet.") else { sv.learnFromSmsNow(); toast("Learning from your texts...") }
        }), 0)

        val tips = TextView(this).apply {
            textSize = 13f; setTextColor(Theme.MUTED); setPadding(dp(4), dp(8), dp(4), 0)
            text = "Try: \"open WhatsApp\", \"call John\", \"text Mary saying I am coming\", \"read my notifications\", " +
                "\"take a picture\", \"search for football news\", \"scroll down\", \"learn this\", \"go to sleep\", " +
                "or \"take control and open YouTube\". Say \"stop\" to make her stop.\n\n" +
                "If a switch is greyed out (Android 13+): Settings > Apps > Mum Florence > ⋮ > Allow restricted settings."
        }
        add(tips, 14)

        val credit = TextView(this).apply {
            text = "✦  Designed by Godwin Akwabeng  ✦"; textSize = 14f; setTextColor(Theme.ACCENT2)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6))
        }
        add(credit, 10)

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })

        animated.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = dp(28).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 70L).setDuration(500).start()
        }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { onClick() }; Theme.styleButton(this, primary) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun door2(emoji: String, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(8), dp(18), dp(8), dp(18)); background = Theme.card(this@MainActivity)
        addView(TextView(this@MainActivity).apply { text = emoji; textSize = 28f; gravity = Gravity.CENTER })
        addView(TextView(this@MainActivity).apply { text = label; textSize = 14f; setTextColor(Theme.TEXT); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        setOnClickListener { onClick() }
        Theme.pressAnim(this)
    }

    // ---------------------------------------------------------------- live status
    private fun updateLive() {
        val name = store.get("assistant_name", "Mum Florence")
        title.text = name
        hint.text = "Say \"$name\" any time"
        val s = FlorenceService.instance
        val state = if (s == null) "off" else s.uiState()
        orb.state = state
        sleepBtn.text = if (s != null && s.isSleeping()) "Wake up" else "Sleep"
        chip.text = when (state) {
            "listening" -> "Listening for her name"
            "listening to you" -> "Listening to you…"
            "talking" -> "Speaking"
            "working" -> "Working on your phone"
            "thinking" -> "Thinking…"
            "sleeping" -> "Asleep — say “wake up”"
            else -> "Not running"
        }
    }

    // ---------------------------------------------------------------- permissions
    private fun runtimePerms(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun missingPerms() = runtimePerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun listenerOk() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
    private fun a11yOk() = Settings.Secure.getString(contentResolver, "enabled_accessibility_services")?.contains(packageName) == true

    private fun refreshStatus() {
        val items = listOf(
            missingPerms().isEmpty() to "Microphone, phone, SMS, contacts, location",
            overlayOk() to "Open apps from the background (display over other apps)",
            batteryOk() to "Stay awake in the background",
            listenerOk() to "Read and reply to notifications",
            a11yOk() to "Control the screen (read, tap, scroll, type)"
        )
        items.forEachIndexed { i, (ok, label) ->
            rows[i].text = (if (ok) "●  " else "○  ") + label
            rows[i].setTextColor(if (ok) Theme.GOOD else Theme.BAD)
        }
        val s = FlorenceService.instance
        info.text = "API key: ${if (store.get("api_key").isBlank()) "none (offline mode)" else Brain.lastStatus}\n" +
            "Voices saved: ${store.voices().size}" + (if (store.voices().isEmpty()) " (anybody can command her)" else "") + "\n" +
            "Things learned from our chats: ${store.qaCount()}\n" +
            "Last heard: ${s?.lastHeard?.ifBlank { "-" } ?: "-"}\n" +
            "Last reply: ${s?.lastReply?.take(80)?.ifBlank { "-" } ?: "-"}"
    }

    private fun step(name: String, ok: Boolean, message: String, launch: () -> Unit): Boolean {
        if (ok || !asked.add(name)) return false
        toast(message)
        launch()
        return true
    }

    private fun nextStep() {
        refreshStatus()
        val miss = missingPerms()
        if (miss.isNotEmpty() && asked.add("perms")) { requestPermissions(miss.toTypedArray(), 1); return }
        if (step("overlay", overlayOk(), "Switch ON for ${store.get("assistant_name", "Mum Florence")}, then press Back") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }) return
        if (step("battery", batteryOk(), "Tap Allow so I can keep listening") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }) return
        if (step("listener", listenerOk(), "Switch ON Mum Florence, then press Back") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) return
        if (step("a11y", a11yOk(), "Open Mum Florence in the list and switch it ON, then press Back") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) return
        wizard = false
        startFlorence()
        ui.postDelayed({ refreshStatus() }, 1500)
    }

    private fun startFlorence() {
        if (!micOk()) return
        try { startForegroundService(Intent(this, FlorenceService::class.java)) } catch (e: Exception) { toast("Could not start: ${e.message}") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (wizard) nextStep() else refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        updateLive()
        ui.post(poll)
        if (wizard) nextStep() else if (micOk() && FlorenceService.instance == null) startFlorence()
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }
}
