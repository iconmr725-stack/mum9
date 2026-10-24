package com.mumflorence.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.AlarmClock
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * The heart of the assistant. Runs all the time as a foreground service:
 * listens OFFLINE for her name, talks like a friend, controls the phone, remembers things.
 */
class FlorenceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var instance: FlorenceService? = null
        private val STOP_SET = setOf("stop", "stop it", "stop talking", "stop reading", "be quiet", "quiet", "enough", "please stop", "okay stop", "ok stop", "stop please", "that's enough")
        private const val AGENT_SYSTEM =
            "You control an Android phone for the user. Each turn you receive the TASK, the STEPS DONE so far and the " +
                "current SCREEN as a list of elements (text, marked [button] or [input]). Reply with ONLY one JSON object: " +
                "{\"action\":\"tap|type|enter|scroll_down|scroll_up|back|home|open_app|wait|done\",\"target\":\"element text or app name\"," +
                "\"text\":\"text to type\",\"say\":\"short spoken update or the final summary\"}. " +
                "Use done when the task is finished or impossible. Be efficient. " +
                "Never pay, buy, delete or send anything unless the task clearly asks for it."
    }

    private enum class Mode { NONE, WAKE, CMD, STOP, VOICE }

    private lateinit var store: Store
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newCachedThreadPool()

    private var model: Model? = null
    private var spkModel: SpeakerModel? = null
    private var speech: SpeechService? = null
    private var rec: Recognizer? = null
    private var mode = Mode.NONE

    private var busy = false
    private var sleeping = false
    private var convoActive = false
    private var followUp = false
    @Volatile private var agentOn = false
    private var next: ((String) -> Unit)? = null
    private val callbacks = HashMap<String, () -> Unit>()
    private val queue = LinkedList<() -> Unit>()
    private val seen = LinkedHashSet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing = false
    private var ringNumber: String? = null
    private var voiceJob: String? = null
    private val enrollVecs = ArrayList<DoubleArray>()
    private var lowBatteryAlerted = false
    private var lastWeatherAlert = 0L
    var lastHeard = ""
    var lastReply = ""
    @Volatile var voiceStatus = ""
    private var voiceTries = 0
    private var lastConvoReply: String? = null
    private var ringHadOffhook = false
    private var awayPending = false
    private var awayAnswered = false
    private var awayNumber: String? = null
    private var awayRunnable: Runnable? = null
    private val missedAt = HashMap<String, Long>()
    private var gsr: SpeechRecognizer? = null
    private var muted = false

    // ================================================================== lifecycle
    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        sleeping = store.on("sleeping", false)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("florence", "Mum Florence is listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("florence_text", "Mum Florence answers", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "florence")
            .setContentTitle("${myName()} is listening")
            .setContentText("Say \"${myName()}\" whenever you need me")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_media_pause, "Stop talking", actionIntent("STOP", 1))
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Sleep / Wake", actionIntent("SLEEP", 2))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "florence:listen")
            .apply { setReferenceCounted(false); acquire() }

        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(phoneReceiver, f)

        tts = TextToSpeech(this, this)
        bg.execute { prepareModels() }
        main.postDelayed({ safetyCheck() }, 60_000)
        bg.execute {
            try { Thread.sleep(25_000) } catch (e: Exception) {}
            if (store.on("learn_messages") && store.get("sms_learned").isEmpty()) {
                if (doLearnSms() >= 0) store.put("sms_learned", "done")
            }
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, FlorenceService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> main.post { onStopHeard() }
            "SLEEP" -> main.post { setSleeping(!sleeping) }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        stopListening()
        try { unregisterReceiver(phoneReceiver) } catch (e: Exception) {}
        try { tts.shutdown() } catch (e: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- called from the app screens
    fun reload() {
        main.post {
            applyVoice()
            sleeping = store.on("sleeping", false)
            if (mode == Mode.WAKE && !busy) startWake()
        }
    }

    fun setSleeping(on: Boolean) {
        main.post {
            sleeping = on
            store.put("sleeping", if (on) "on" else "off")
            convoActive = false
            if (!busy) startWake()
        }
    }

    fun isSleeping() = sleeping

    fun stopNow() { main.post { onStopHeard() } }

    fun uiState(): String = when {
        sleeping -> "sleeping"
        agentOn -> "working"
        ttsReady && tts.isSpeaking -> "talking"
        mode == Mode.CMD || mode == Mode.VOICE -> "listening to you"
        busy -> "thinking"
        model == null -> "off"
        else -> "listening"
    }

    fun diag(): String =
        "Listening brain: ${if (model != null) "ready" else "loading or missing"}\n" +
            "Voice-recognition model: ${if (spkModel != null) "ready" else "MISSING (rebuild the app)"}\n" +
            "Saved voices: ${store.voices().size}"

    fun voiceJob(job: String) {
        main.post {
            if (model == null) { voiceStatus = "My listening brain is still loading. Try again in a few seconds."; say("My listening brain is still loading. Please try again in a few seconds."); return@post }
            if (spkModel == null) { voiceStatus = "The voice-recognition model is missing from the app. Rebuild the app so it is included."; say("My voice recognition files are missing, so I cannot learn voices yet."); return@post }
            busy = true
            stopListening()
            voiceJob = job
            voiceTries = 0
            enrollVecs.clear()
            if (job == "test") {
                voiceStatus = "Say anything now..."
                say("Say anything, and I will check whose voice it is.") { startVoiceListen() }
            } else {
                val who = job.removePrefix("enroll:")
                voiceStatus = "Recording 1 of 3. Say: the quick brown fox jumps over the lazy dog"
                say("Okay. I will learn the voice of $who. Please say clearly: the quick brown fox jumps over the lazy dog.") { startVoiceListen() }
            }
        }
    }

    // ================================================================== names and grammars
    private fun myName() = store.get("assistant_name", "Mum Florence").ifBlank { "Mum Florence" }
    private fun lastWord() = myName().lowercase().trim().split(Regex("\\s+")).last()
    private fun ownerName() = store.fact("name")

    private fun wakeWords(): Set<String> {
        val parts = myName().lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val s = LinkedHashSet<String>()
        for (p in parts) if (p.length >= 3) s.add(p)
        if (parts.firstOrNull() == "mum" || parts.firstOrNull() == "mom") { s.add("mum"); s.add("mom") }
        return s
    }

    private fun isWakeToken(t: String, words: Set<String>): Boolean =
        t in words || (t.length >= 6 && words.any { it.length >= 6 && Contacts.lev(it, t) <= 1 })

    /** If her name was said: the command spoken with it ("" if only the name). Null if her name was not said. */
    private fun extractWake(text: String): String? {
        val words = wakeWords()
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val idx = tokens.indexOfFirst { isWakeToken(it, words) }
        if (idx < 0) return null
        if (idx > 2 && idx != tokens.size - 1) return null          // her name must be near the start or at the very end
        var end = idx
        while (end + 1 < tokens.size && isWakeToken(tokens[end + 1], words)) end++
        val filler = setOf("hey", "hi", "ok", "okay", "hello")
        val after = tokens.drop(end + 1)
        if (after.isNotEmpty()) return after.joinToString(" ")
        val before = tokens.take(idx).filter { it !in filler }
        return if (before.size >= 2) before.joinToString(" ") else ""
    }

    // ================================================================== voice out
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val locale = Locale.getDefault().let { if (tts.isLanguageAvailable(it) >= 0) it else Locale.US }
        tts.language = locale
        try {
            val hints = listOf("female", "-tpf-", "-sfg-", "-tpc-", "-gba-", "-afc-")
            tts.voices?.filter { it.locale.language == locale.language }
                ?.firstOrNull { v -> val n = v.name.lowercase(); !v.isNetworkConnectionRequired && hints.any { n.contains(it) } }
                ?.let { tts.voice = it }
        } catch (e: Exception) {}
        applyVoice()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { callbacks.remove(utteranceId)?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { callbacks.remove(utteranceId)?.invoke() } }
        })
        ttsReady = true
    }

    private fun applyVoice() {
        if (!::tts.isInitialized) return
        tts.setPitch((store.get("tts_pitch", "115").toIntOrNull() ?: 115) / 100f)
        tts.setSpeechRate((store.get("tts_rate", "95").toIntOrNull() ?: 95) / 100f)
    }

    private fun say(text: String, inCall: Boolean = false, then: (() -> Unit)? = null) {
        lastReply = text
        store.log("assistant", text)
        if (!ttsReady) { if (then != null) main.postDelayed(then, 500); return }
        if (!inCall && (text.length >= 90 || agentOn)) startStopListener()
        muteBeeps(false)
        val id = UUID.randomUUID().toString()
        if (then != null) {
            callbacks[id] = then
            main.postDelayed({ callbacks.remove(id)?.invoke() }, 6000L + text.length * 110L)
        }
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
    }

    private fun showText(t: String) {
        val n = Notification.Builder(this, "florence_text")
            .setContentTitle(myName())
            .setContentText(t)
            .setStyle(Notification.BigTextStyle().bigText(t))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(Random().nextInt(100000) + 10, n)
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }

    private fun reply(text: String, asText: Boolean = false) {
        if (asText) { lastReply = text; store.log("assistant", text); showText(text); finish() }
        else { busy = true; say(text) { finish() } }
    }

    /** Back to idle: run anything waiting, keep chatting if we are in a conversation, else listen for her name. */
    private fun finish() {
        next = null
        val job = queue.poll()
        if (job != null) { busy = true; stopListening(); job(); return }
        if (convoActive && !sleeping && store.on("convo_mode") && model != null) {
            busy = true; followUp = true
            startCommand(7000)
            return
        }
        busy = false; followUp = false
        startWake()
    }

    private fun later(job: () -> Unit) {
        if (busy) queue.add(job) else { busy = true; stopListening(); job() }
    }

    private fun ask(question: String, onAnswer: (String) -> Unit) {
        busy = true
        stopListening()
        say(question) { next = onAnswer; startCommand() }
    }

    // ================================================================== voice in (offline, Vosk)
    private fun prepareModels() {
        try {
            val dir = File(filesDir, "model")
            if (!File(dir, "am/final.mdl").exists()) copyAssets("model", dir)
            model = Model(dir.absolutePath)
            try {
                val sdir = File(filesDir, "spk")
                if (!File(sdir, "final.ext.raw").exists()) copyAssets("spk", sdir)
                spkModel = SpeakerModel(sdir.absolutePath)
            } catch (e: Throwable) {
                spkModel = null
            }
            main.post {
                if (sleeping) startWake()
                else { say("Hello${ownerName()?.let { " $it" } ?: ""}. ${myName()} is ready. Just say my name when you need me.") { finish() } }
            }
        } catch (e: Throwable) {
            main.post { say("Sorry, I could not load my listening brain.") }
        }
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: return
        if (list.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Exception) {}
            return
        }
        dest.mkdirs()
        for (n in list) copyAssets("$path/$n", File(dest, n))
    }

    private fun muteBeeps(on: Boolean) {
        if (muted == on) return
        muted = on
        val am = getSystemService(AudioManager::class.java)
        val dir = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try { am.adjustStreamVolume(s, dir, 0) } catch (e: Exception) {}
        }
    }

    private fun stopListening() {
        gsr?.let { try { it.cancel(); it.destroy() } catch (e: Exception) {} }
        gsr = null
        muteBeeps(false)
        speech?.let {
            try { it.stop() } catch (e: Exception) {}
            try { it.shutdown() } catch (e: Exception) {}   // releases the microphone
        }
        speech = null
        rec?.let { try { it.close() } catch (e: Exception) {} }
        rec = null
        mode = Mode.NONE
    }

    private fun startWake() {
        val m = model ?: return
        stopListening()
        try {
            val r = Recognizer(m, 16000f)
            if (!sleeping && store.on("voice_lock") && store.voices().isNotEmpty()) spkModel?.let { r.setSpeakerModel(it) }
            rec = r
            speech = SpeechService(r, 16000f).also { it.startListening(wakeListener) }
            mode = Mode.WAKE
        } catch (e: Exception) {
            stopListening()
            main.postDelayed({ if (!busy) startWake() }, 3000)
        }
    }

    /** While she reads something long (or works on the phone) we listen only for "stop". */
    private fun startStopListener() {
        val m = model ?: return
        if (mode == Mode.STOP) return
        stopListening()
        try {
            val r = Recognizer(m, 16000f)
            rec = r
            speech = SpeechService(r, 16000f).also { it.startListening(stopListener) }
            mode = Mode.STOP
        } catch (e: Exception) {
            stopListening()
        }
    }

    private fun useGoogle(): Boolean {
        if (store.get("command_engine", "Google (more accurate)").startsWith("Offline")) return false
        if (store.on("voice_lock") && store.voices().isNotEmpty()) return false     // the voice lock needs the offline engine
        return SpeechRecognizer.isRecognitionAvailable(this)
    }

    private fun startGoogle(timeout: Int) {
        stopListening()
        mode = Mode.CMD
        muteBeeps(true)
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        gsr = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (gsr !== r) return
                stopListening()
                cmdListener.onTimeout()
            }
            override fun onResults(results: Bundle?) {
                if (gsr !== r) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                stopListening()
                if (t.isBlank()) cmdListener.onTimeout()
                else cmdListener.onResult(JSONObject().put("text", t.lowercase().trim()).toString())
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !Brain.online(this))
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        try { r.startListening(i) } catch (e: Exception) { stopListening(); startOffline(timeout); return }
        main.postDelayed({ if (gsr === r) { stopListening(); cmdListener.onTimeout() } }, timeout.toLong() + 4000)
    }

    private fun startCommand(timeout: Int = 9000) {
        if (useGoogle()) startGoogle(timeout) else startOffline(timeout)
    }

    private fun startOffline(timeout: Int) {
        val m = model ?: return
        stopListening()
        try {
            val r = Recognizer(m, 16000f)
            if (store.voices().isNotEmpty()) spkModel?.let { r.setSpeakerModel(it) }
            rec = r
            speech = SpeechService(r, 16000f).also { it.startListening(cmdListener, timeout) }
            mode = Mode.CMD
        } catch (e: Exception) {
            stopListening()
            convoActive = false
            busy = false
            startWake()
        }
    }

    private fun startVoiceListen() {
        val m = model ?: return
        val sm = spkModel ?: return
        stopListening()
        try {
            val r = Recognizer(m, 16000f)
            r.setSpeakerModel(sm)
            rec = r
            speech = SpeechService(r, 16000f).also { it.startListening(voiceListener, 14000) }
            mode = Mode.VOICE
        } catch (e: Exception) {
            stopListening()
            voiceJob = null
            finish()
        }
    }

    private fun textOf(h: String?): String = try {
        val j = JSONObject(h ?: "")
        j.optString("text").ifEmpty { j.optString("partial") }
    } catch (e: Exception) { "" }

    private fun vectorOf(j: JSONObject): DoubleArray? {
        val a = j.optJSONArray("spk") ?: return null
        return DoubleArray(a.length()) { a.getDouble(it) }
    }

    /** Only enrolled voices may command her (when voice lock is on). */
    private fun speakerOk(h: String?): Boolean {
        if (!store.on("voice_lock")) return true
        val voices = store.voices()
        if (voices.isEmpty()) return true
        val v = try { vectorOf(JSONObject(h ?: "")) } catch (e: Exception) { null } ?: return false
        val thr = (store.get("voice_threshold", "45").toIntOrNull() ?: 45) / 100.0
        return voices.values.any { cosDist(it, v) < thr }
    }

    private val wakeListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = check(hypothesis)
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { main.postDelayed({ if (!busy) startWake() }, 3000) }
        override fun onTimeout() { if (!busy) startWake() }
        private fun check(h: String?) {
            if (busy) return
            val t = textOf(h).lowercase().trim()
            if (t.isBlank()) return
            if (sleeping) { if (Regex("\\bwake up\\b").containsMatchIn(t)) onWakeUp(); return }
            val cmd = extractWake(t) ?: return
            if (cmd.isNotBlank() && !speakerOk(h)) return
            onWake(cmd.ifBlank { null })
        }
    }

    private val stopListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) { if (textOf(hypothesis).trim().lowercase() in STOP_SET) onStopHeard() }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {}
        override fun onTimeout() {}
    }

    private val cmdListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {
            val t = textOf(hypothesis)
            if (t.isBlank()) return
            stopListening()
            if (!speakerOk(hypothesis)) {
                if (followUp) { convoActive = false; finish() }
                else { convoActive = false; say("Sorry, I only take commands from my owners' voices.") { finish() } }
                return
            }
            followUp = false
            lastHeard = t
            store.log("user", t)
            val n = next
            next = null
            if (n != null) n(t) else handle(t)
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { stopListening(); convoActive = false; say("Sorry, something went wrong.") { finish() } }
        override fun onTimeout() {
            stopListening()
            val n = next
            next = null
            when {
                n != null -> n("")
                followUp -> { followUp = false; convoActive = false; finish() }
                else -> { convoActive = false; say("I didn't hear anything.") { finish() } }
            }
        }
    }

    private val voiceListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {
            val j = try { JSONObject(hypothesis ?: "") } catch (e: Exception) { return }
            val text = j.optString("text")
            if (text.isBlank()) return
            val vec = vectorOf(j)
            val frames = j.optInt("spk_frames", 0)
            stopListening()
            val job = voiceJob
            if (job == null) { finish(); return }
            voiceStatus = "Heard: \"$text\" (voice data: ${vec?.size ?: 0} values, $frames frames)"
            if (vec == null || frames in 1..14) {
                voiceTries++
                if (voiceTries >= 4) {
                    voiceJob = null
                    voiceStatus = "The speaker model returned no voice data. $voiceStatus"
                    say("Sorry, I could not get your voice print. Please try again in a quiet place.") { finish() }
                } else say("That didn't work. Please speak a little longer and try again.") { startVoiceListen() }
                return
            }
            if (job == "test") {
                voiceJob = null
                val voices = store.voices()
                if (voices.isEmpty()) { voiceStatus = "No voices are saved yet."; say("No voices are saved yet.") { finish() }; return }
                val best = voices.entries.minByOrNull { cosDist(it.value, vec) }!!
                val d = cosDist(best.value, vec)
                val thr = (store.get("voice_threshold", "45").toIntOrNull() ?: 45) / 100.0
                val verdict = if (d < thr) "I accept this voice." else "I would not accept this voice."
                voiceStatus = "Closest: ${best.key}. Distance ${"%.2f".format(d)} (limit ${"%.2f".format(thr)}). $verdict"
                say("Closest voice is ${best.key}. Distance ${"%.2f".format(d)}, limit ${"%.2f".format(thr)}. $verdict") { finish() }
                return
            }
            enrollVecs.add(vec)
            val prompts = listOf("", "Good. Now say: I am asking my assistant to open WhatsApp.", "Last one. Say: please call my friend and send a message.")
            if (enrollVecs.size < 3) {
                voiceStatus = "Recording ${enrollVecs.size + 1} of 3..."
                say(prompts[enrollVecs.size]) { startVoiceListen() }
            } else {
                val name = job.removePrefix("enroll:")
                val avg = DoubleArray(enrollVecs[0].size)
                for (v in enrollVecs) for (i in avg.indices) avg[i] += v[i] / enrollVecs.size
                store.saveVoice(name, avg)
                voiceJob = null
                voiceStatus = "Saved the voice of $name ✓"
                say("Done. I now know the voice of $name.") { finish() }
            }
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { stopListening(); voiceJob = null; voiceStatus = "Microphone error: ${exception?.message}"; finish() }
        override fun onTimeout() {
            stopListening()
            voiceTries++
            if (voiceJob != null && voiceTries < 3) {
                voiceStatus = "I didn't hear you. Trying again..."
                say("I didn't hear you. Please try again.") { startVoiceListen() }
            } else {
                voiceJob = null
                voiceStatus = "I didn't hear you. Please try again in a quiet place."
                say("I didn't hear you. Let's try again later.") { finish() }
            }
        }
    }

    private fun onStopHeard() {
        if (agentOn) {
            agentOn = false
            try { tts.stop() } catch (e: Exception) {}
            callbacks.clear(); queue.clear()
            return                       // the agent loop wraps up and finishes
        }
        stopListening()
        try { tts.stop() } catch (e: Exception) {}
        callbacks.clear(); queue.clear()
        next = null; convoActive = false; awayPending = false
        main.postDelayed({ finish() }, 200)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "florence:screen")
                .acquire(20000)
        } catch (e: Exception) {}
    }

    /** [inline] = a command said together with her name ("mum florence open whatsapp"). */
    private fun onWake(inline: String?) {
        busy = true
        stopListening()
        wakeScreen()
        convoActive = true
        if (inline != null) {
            lastHeard = inline
            store.log("user", inline)
            handle(inline)
            return
        }
        val greet = listOf("Yes?", "I'm here, what's up?", "Hey, I'm listening.", "Yes dear, go ahead.", "Mm-hmm?")
        say(greet.random()) { startCommand() }
    }

    private fun onWakeUp() {
        sleeping = false
        store.put("sleeping", "off")
        busy = true
        stopListening()
        wakeScreen()
        convoActive = true
        say("I'm awake! What's up?") { startCommand() }
    }

    // ================================================================== things that happen to the phone
    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.') }

    fun onNotification(pkg: String, title: String, text: String, isCall: Boolean) {
        main.post {
            if (sleeping) return@post
            if (isCall) {
                later {
                    ask("WhatsApp call from $title. Should I answer?") { a ->
                        if (yes(a)) answerCall() else if (no(a)) declineCall()
                        finish()
                    }
                }
                return@post
            }
            if (!seen.add("$pkg|$title|$text")) return@post
            while (seen.size > 200) seen.remove(seen.first())
            val app = appLabel(pkg)
            val nm = store.get("notif_mode", "ask first")
            if (!nm.startsWith("silent")) {
                later {
                    if (nm.startsWith("read")) {
                        say("$app. $title says: $text") { offerReply(pkg, title) }
                    } else {
                        val who = if (title.isBlank()) "a notification" else "a message from $title"
                        ask("You have $who on $app. Should I read it?") { a ->
                            if (yes(a)) say("$title says: $text") { offerReply(pkg, title) } else reply("Okay, I'll leave it.")
                        }
                    }
                }
            }
            if (store.on("autoreply", false) && pkg in FlorenceNotificationListener.WHATSAPP &&
                Replies.find(title) != null && Contacts.all(this, store).containsKey(title.lowercase())
            ) {
                bg.execute {
                    val msg = draft(title, text)
                    main.post { Replies.send(this, title, msg) }
                }
            }
        }
    }

    private fun offerReply(pkg: String, title: String) {
        if (Replies.find(title) == null) { finish(); return }
        ask("Do you want to reply?") { a ->
            if (!yes(a)) { finish(); return@ask }
            ask("What should I say?") { msg ->
                if (msg.isBlank()) reply("Okay, never mind.")
                else confirm("I will reply: $msg. Should I send it?") {
                    reply(if (Replies.send(this, title, msg)) "Sent." else "Sorry, I could not send it.")
                }
            }
        }
    }

    fun onSms(from: String, body: String) {
        main.post {
            if (sleeping) return@post
            val name = Contacts.nameFor(this, from)
            val who = name ?: from
            val nm = store.get("notif_mode", "ask first")
            if (!nm.startsWith("silent")) {
                later {
                    val readIt = {
                        say("$who says: $body") {
                            ask("Do you want to reply?") { a ->
                                if (!yes(a)) { finish(); return@ask }
                                ask("What should I say?") { msg ->
                                    if (msg.isBlank()) reply("Okay, never mind.")
                                    else confirm("I will text $who: $msg. Should I send it?") {
                                        try { sendSms(Contacts.clean(store, from), msg); reply("Sent.") } catch (e: Exception) { reply("Sorry, I could not send it.") }
                                    }
                                }
                            }
                        }
                    }
                    if (nm.startsWith("read")) readIt()
                    else ask("You have a text message from $who. Should I read it?") { a -> if (yes(a)) readIt() else reply("Okay.") }
                }
            }
            if (store.on("autoreply", false) && name != null) {
                bg.execute {
                    val msg = draft(name, body)
                    main.post { try { sendSms(Contacts.clean(store, from), msg) } catch (e: Exception) {} }
                }
            }
        }
    }

    private fun cancelAway() { awayRunnable?.let { main.removeCallbacks(it) }; awayRunnable = null }

    private val phoneReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getStringExtra(TelephonyManager.EXTRA_STATE)
            i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let { ringNumber = it }
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> if (!ringing) {
                    ringing = true; ringHadOffhook = false
                    if (!sleeping) main.postDelayed({ onRing() }, 1200)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    ringHadOffhook = true; ringing = false; cancelAway()
                    if (awayPending) { awayPending = false; awayAnswered = true; main.postDelayed({ speakToCaller() }, 1500) }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (ringing && !ringHadOffhook) ringNumber?.let { missedAt[it] = System.currentTimeMillis() }
                    ringing = false; cancelAway(); ringNumber = null
                    if (awayPending) { awayPending = false; finish() }
                    awayAnswered = false
                }
            }
        }
    }

    /** A call is ringing. In away mode she waits (or answers sooner if the same person already called), otherwise she asks you. */
    private fun onRing() {
        if (!ringing || sleeping) return
        val n = ringNumber
        if (store.on("away_mode", true)) {
            val repeat = n != null && (System.currentTimeMillis() - (missedAt[n] ?: 0L)) < 15 * 60_000L
            val secs = if (repeat) 6 else (store.get("away_delay", "30").toIntOrNull() ?: 30)
            val r = Runnable { awayAnswer(n) }
            awayRunnable = r
            main.postDelayed(r, max(secs * 1000L - 1200L, 2000L))
        } else {
            val who = n?.let { Contacts.nameFor(this, it) ?: it } ?: "Someone"
            later {
                ask("$who is calling. Should I answer?") { a ->
                    if (yes(a)) answerCall() else if (no(a)) declineCall()
                    finish()
                }
            }
        }
    }

    private fun awayAnswer(n: String?) {
        awayRunnable = null
        if (!ringing) return
        awayNumber = n
        awayPending = true
        busy = true
        stopListening(); queue.clear()
        answerCall()
        main.postDelayed({
            if (awayPending) {
                awayPending = false
                showText("I tried to answer a call for you but could not. Please check my Phone permission.")
                finish()
            }
        }, 7000)
    }

    /** The call is connected: switch to loudspeaker at full volume and speak, so the caller hears her through your microphone. */
    private fun speakToCaller() {
        val who = awayNumber?.let { Contacts.nameFor(this, it) ?: it }
        val owner = ownerName() ?: "the owner of this phone"
        val msg = store.get("away_message").ifBlank {
            "Hello. This is ${myName()}, the personal assistant of $owner. $owner is not available right now. Please call again later, or send a text message."
        }
        val am = getSystemService(AudioManager::class.java)
        try {
            am.isSpeakerphoneOn = true
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (e: Exception) {}
        if (!am.isSpeakerphoneOn) FlorenceAccessibility.click("Speaker", "Speaker phone", "Loudspeaker")
        main.postDelayed({
            say(msg, true) {
                main.postDelayed({
                    say(msg, true) {
                        main.postDelayed({
                            declineCall()
                            try { am.isSpeakerphoneOn = false } catch (e: Exception) {}
                            val num = awayNumber
                            if (num != null && store.on("away_sms")) {
                                try { sendSms(Contacts.clean(store, num), "Hi, this is ${myName()}. $owner is not available right now and will get back to you.") } catch (e: Exception) {}
                            }
                            val summary = "I answered a call from ${who ?: "an unknown number"} for you and told them you are away."
                            store.log("assistant", summary)
                            showText(summary)
                            awayAnswered = false; awayNumber = null
                            finish()
                        }, 1200)
                    }
                }, 700)
            }
        }, 900)
    }

    private fun draft(sender: String, text: String): String {
        val prompt = "$sender sent me this message: \"$text\". Write a short, friendly reply from me. Reply with the message text only."
        return Brain.ask(this, store, prompt, "You write short replies on behalf of the user.", false)
            ?: LocalMind.best(this, store, text, LocalMind.WEAK, setOf("sms", "chat"))?.first
            ?: store.get("away_reply").ifBlank { "Sorry, I can't talk right now. I will get back to you soon." }
    }

    // ================================================================== safety alerts
    private fun safetyCheck() {
        main.postDelayed({ safetyCheck() }, 30 * 60 * 1000L)
        if (!store.on("danger_alerts") || sleeping) return
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 1..14 && !bm.isCharging && !lowBatteryAlerted) {
            lowBatteryAlerted = true
            later { say("Heads up. Your battery is at $pct percent. Please charge your phone.") { finish() } }
        } else if (pct >= 30) lowBatteryAlerted = false
        if (!Brain.online(this) || System.currentTimeMillis() - lastWeatherAlert < 3 * 3600_000L) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        bg.execute {
            try {
                val lm = getSystemService(LocationManager::class.java)
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) ?: return@execute
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=weather_code,wind_gusts_10m&timezone=auto"
                val cur = JSONObject(Brain.get(url) ?: return@execute).getJSONObject("current")
                val code = cur.optInt("weather_code")
                val gust = cur.optDouble("wind_gusts_10m", 0.0)
                val what = when {
                    code in listOf(95, 96, 99) -> "a thunderstorm"
                    code in listOf(65, 67, 82) -> "very heavy rain"
                    code in listOf(75, 86) -> "heavy snow"
                    gust >= 70 -> "dangerously strong wind"
                    else -> null
                }
                if (what != null) {
                    lastWeatherAlert = System.currentTimeMillis()
                    main.post { later { say("Please be careful. There is $what in your area right now.") { finish() } } }
                }
            } catch (e: Exception) {}
        }
    }

    // ================================================================== actions
    private fun yes(a: String) = Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|send|go ahead|answer|do it|please|read it)\\b").containsMatchIn(a)
    private fun no(a: String) = Regex("\\b(no|nope|decline|reject|cancel|don't|dont|hang|leave it)\\b").containsMatchIn(a)

    private fun answerCall() {
        try { getSystemService(TelecomManager::class.java).acceptRingingCall() } catch (e: Exception) {}
        try { Replies.callAnswer?.actionIntent?.send() } catch (e: Exception) {}
    }

    private fun declineCall() {
        try { if (Build.VERSION.SDK_INT >= 28) getSystemService(TelecomManager::class.java).endCall() } catch (e: Exception) {}
        try { Replies.callDecline?.actionIntent?.send() } catch (e: Exception) {}
    }

    private fun sendSms(number: String, msg: String) {
        val sm = getSystemService(SmsManager::class.java)
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(msg), null, null)
    }

    private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun appIntent(name: String): Intent? {
        val pm = packageManager
        val q = letters(name)
        if (q.isEmpty()) return null
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: android.content.pm.ResolveInfo? = null
        var bestD = 99
        for (ri in apps) {
            val l = letters(ri.loadLabel(pm).toString())
            val d = when {
                l == q -> 0
                l.length >= 4 && (l.contains(q) || q.contains(l)) -> 1
                else -> Contacts.lev(l, q) + 1
            }
            if (d < bestD) { bestD = d; best = ri }
        }
        if (best == null || bestD > 3) return null
        return pm.getLaunchIntentForPackage(best.activityInfo.packageName)
    }

    /** Start an activity from the background: through the accessibility service first (Android allows it), then normally. */
    private fun launch(i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = FlorenceAccessibility.instance ?: this
        return try { ctx.startActivity(i); true } catch (e: Exception) {
            try { startActivity(i); true } catch (e2: Exception) { false }
        }
    }

    private fun openApp(name: String): Boolean {
        val i = appIntent(name) ?: return false
        return launch(i)
    }

    private fun openAppReport(name: String, asText: Boolean) {
        val i = appIntent(name)
        if (i == null) { reply("I couldn't find an app called $name.", asText); return }
        val pkg = i.component?.packageName ?: i.getPackage()
        launch(i)
        busy = true
        main.postDelayed({
            val now = FlorenceAccessibility.currentPackage()
            if (pkg != null && FlorenceAccessibility.instance != null && now.isNotEmpty() && now != pkg)
                reply("I tried to open $name but Android blocked me. Please switch on Display over other apps for me in my app.", asText)
            else reply("Opening $name.", asText)
        }, 1800)
    }

    private fun openUrl(url: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.setPackage("com.android.chrome")
        if (launch(i)) return true
        i.setPackage(null)
        return launch(i)
    }

    private fun confirm(question: String, onYes: () -> Unit) {
        if (!store.on("confirm_send")) { onYes(); return }
        ask(question) { a -> if (yes(a)) onYes() else reply("Okay, I did not send it.") }
    }

    private fun flashlight(on: Boolean): Boolean = try {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id != null) { cm.setTorchMode(id, on); true } else false
    } catch (e: Exception) { false }

    private fun volume(up: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
    }

    private fun takePicture(selfie: Boolean, asText: Boolean) {
        try { if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) throw IllegalStateException("no camera") }
        catch (e: Exception) { if (!openApp("camera")) { reply("I couldn't open the camera.", asText); return } }
        busy = true
        bg.execute {
            Thread.sleep(2500)
            if (selfie) {
                FlorenceAccessibility.click("Switch cameras", "Switch to front camera", "Flip camera", "Front camera", "Rotate camera")
                Thread.sleep(1500)
            }
            main.post { say("Smile! Three, two, one.") }
            Thread.sleep(4500)
            val ok = FlorenceAccessibility.click("Take picture", "Shutter", "Capture", "Take photo") || FlorenceAccessibility.tapBottomCenter()
            Thread.sleep(1200)
            main.post { reply(if (ok) "Done, I took the picture." else "I couldn't find the shutter button.", asText) }
        }
    }

    private fun readScreenAloud(asText: Boolean) {
        val t = FlorenceAccessibility.readScreen()
        reply(if (t.isBlank()) "I couldn't read anything. Please turn on screen reading for me in the app." else t, asText)
    }

    private fun readChatFrom(name: String, asText: Boolean) {
        val l = FlorenceNotificationListener.instance
        val hit = l?.activeNotifications?.firstOrNull {
            !FlorenceNotificationListener.skipped(it.packageName) &&
                (it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase()?.contains(name) == true)
        }
        if (hit != null) {
            val title = hit.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: name
            reply("$title says: ${Replies.fullText(hit.notification)}", asText)
            return
        }
        if (!openApp("whatsapp")) { reply("I couldn't find a message from $name.", asText); return }
        busy = true
        bg.execute {
            Thread.sleep(3500)
            FlorenceAccessibility.click(name)
            Thread.sleep(2500)
            main.post { readScreenAloud(asText) }
        }
    }

    // ================================================================== memory
    private val stop = setOf("what", "whats", "the", "is", "my", "do", "you", "know", "about", "me", "tell", "was", "are", "who", "when", "where", "how", "did", "i", "a", "an", "of", "to", "and", "your", "have", "remember")

    private fun learn(t: String): String {
        Regex("^(?:that )?my name is (.+)$").find(t)?.let {
            store.setFact("name", it.groupValues[1]); return "Nice to meet you, ${it.groupValues[1]}. I will remember your name."
        }
        Regex("^(?:that )?(?:i am|i'm) (.+?) years?(?: old)?$").find(t)?.let {
            store.setFact("age", it.groupValues[1]); return "Got it, you are ${it.groupValues[1]} years old."
        }
        Regex("^(?:that )?my (.+?) (?:is|are) (.+)$").find(t)?.let {
            store.setFact(it.groupValues[1], it.groupValues[2]); return "Okay, I will remember that your ${it.groupValues[1]} is ${it.groupValues[2]}."
        }
        store.addNote(t)
        return "Okay, I have saved that."
    }

    private fun memoryAnswer(c: String): String? {
        if (Regex("what do you know about me|tell me about myself").containsMatchIn(c)) {
            val f = store.allFacts()
            return if (f.isEmpty() && store.notes(1).isEmpty()) "I don't know much yet. Tell me about yourself."
            else "Here is what I know. " + f.entries.joinToString(". ") { "Your ${it.key} is ${it.value}" } +
                (if (store.notes(3).isNotEmpty()) ". You also told me: " + store.notes(3).joinToString(". ") else "")
        }
        Regex("^how old am i$").find(c)?.let { return store.fact("age")?.let { "You told me you are $it years old." } }
        Regex("^who am i$|^what is my name$|^what's my name$").find(c)?.let { return store.fact("name")?.let { "Your name is $it." } }
        Regex("^what(?:'s| is) my (.+)$").find(c)?.let { m -> store.fact(m.groupValues[1])?.let { return "Your ${m.groupValues[1]} is $it." } }
        val words = c.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }.toSet()
        if (words.isEmpty()) return null
        var best: String? = null; var bestScore = 0
        for ((k, v) in store.allFacts()) {
            val s = words.count { (k + " " + v).lowercase().contains(it) }
            if (s > bestScore) { bestScore = s; best = "Your $k is $v." }
        }
        for (n in store.notes(100)) {
            val s = words.count { n.lowercase().contains(it) }
            if (s > bestScore) { bestScore = s; best = "You told me: $n" }
        }
        return if (bestScore >= 1) best else null
    }

    private fun smallTalk(c: String): String? {
        val n = ownerName()?.let { " $it" } ?: ""
        return when {
            Regex("^(hi|hello|hey|good (morning|afternoon|evening))\\b").containsMatchIn(c) ->
                listOf("Hey$n! How is your day going?", "Hello$n! Good to hear you. What's new?", "Hi$n! I was hoping you'd call me. How are you?").random()
            c.contains("how are you") -> listOf("I'm doing well, thank you for asking. And you?", "Feeling great! How about you?").random()
            Regex("\\b(thank you|thanks)\\b").containsMatchIn(c) -> "You're welcome$n!"
            c.contains("good night") -> "Good night$n! Sleep well."
            c.contains("i love you") -> "Aww, I care about you too$n."
            c.contains("who are you") || c.contains("your name") -> "I'm ${myName()}, your personal assistant and friend. You can change my name in my settings."
            c.contains("what can you do") -> "I can call and text people, read your notifications, open apps, take pictures, control your phone, and remember whatever you teach me."
            else -> null
        }
    }

    private fun offlineAnswer(c: String): String? = when {
        Regex("\\bwhat('s| is)? the time\\b|\\bwhat time\\b").containsMatchIn(c) ->
            "It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        Regex("\\bwhat('s| is)? (the )?(date|day)( today)?\\b|\\btoday's date\\b").containsMatchIn(c) ->
            "Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date())
        c.contains("battery") ->
            "Your battery is at " + getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent."
        else -> null
    }

    // ================================================================== phone-control agent (needs the API key)
    private fun parseJson(s: String): JSONObject? = try {
        JSONObject(s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1))
    } catch (e: Exception) { null }

    private fun askAndWait(q: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post { ask(q) { a -> ok = yes(a); latch.countDown() } }
        latch.await(40, TimeUnit.SECONDS)
        main.post { next = null; startStopListener() }
        return ok
    }

    private fun runAgent(task: String, asText: Boolean) {
        if (store.get("api_key").isBlank() || !Brain.online(this)) {
            reply("I need internet and my API key to take control of the phone for you.", asText); return
        }
        agentOn = true; busy = true; convoActive = false
        say("Okay, I'm on it. Say stop any time.")
        bg.execute {
            val trail = ArrayList<String>()
            var summary = "I stopped."
            var step = 0
            while (agentOn && step < 15) {
                step++
                val screen = FlorenceAccessibility.elements().joinToString("\n").ifBlank { "(screen not readable)" }
                val prompt = "TASK: $task\nSTEPS DONE:\n${trail.takeLast(8).joinToString("\n").ifBlank { "(none)" }}\nSCREEN:\n$screen"
                val j = Brain.ask(this, store, prompt, AGENT_SYSTEM, false)?.let { parseJson(it) }
                if (j == null) { summary = "I couldn't work out the next step."; break }
                val action = j.optString("action")
                val target = j.optString("target")
                val text = j.optString("text")
                if (action == "done") { summary = j.optString("say").ifBlank { "All done." }; break }
                if (Regex("(?i)\\b(pay|buy|purchase|place order|send|delete|remove|transfer|submit|post|confirm)\\b").containsMatchIn("$action $target") &&
                    !askAndWait("I am about to $action $target. Is that okay?")) {
                    summary = "Okay, I did not do that."; break
                }
                if (!agentOn) break
                val ok = when (action) {
                    "tap" -> FlorenceAccessibility.click(target)
                    "type" -> FlorenceAccessibility.type(text)
                    "enter" -> FlorenceAccessibility.pressEnter()
                    "scroll_down" -> FlorenceAccessibility.scroll(true)
                    "scroll_up" -> FlorenceAccessibility.scroll(false)
                    "back" -> FlorenceAccessibility.global(AccessibilityService.GLOBAL_ACTION_BACK)
                    "home" -> FlorenceAccessibility.global(AccessibilityService.GLOBAL_ACTION_HOME)
                    "open_app" -> {
                        var r = false
                        val l = CountDownLatch(1)
                        main.post { r = openApp(target); l.countDown() }
                        l.await(3, TimeUnit.SECONDS)
                        r
                    }
                    "wait" -> true
                    else -> false
                }
                trail.add("$action $target $text -> ${if (ok) "ok" else "failed"}")
                Thread.sleep(1600)
            }
            main.post { agentOn = false; say(summary) { finish() } }
        }
    }

    // ================================================================== custom commands from the app
    private fun runCustom(kind: String, payload: String, asText: Boolean) {
        when (kind) {
            "Say" -> reply(payload, asText)
            "Open app" -> reply(if (openApp(payload)) "Opening $payload." else "I couldn't find $payload.", asText)
            "Open website" -> { openUrl(if (payload.startsWith("http")) payload else "https://$payload"); reply("Opening it.", asText) }
            "Call" -> {
                val hit = Contacts.find(this, store, payload)
                if (hit == null) reply("I couldn't find $payload.", asText)
                else try { getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", hit.second, null), Bundle()); reply("Calling ${hit.first}.", asText) }
                catch (e: Exception) { reply("I couldn't place the call.", asText) }
            }
            "Text" -> {
                val parts = payload.split(":", limit = 2)
                val hit = Contacts.find(this, store, parts[0])
                if (hit == null || parts.size < 2) reply("That command needs the form: name: message.", asText)
                else confirm("I will text ${hit.first}: ${parts[1].trim()}. Should I send it?") {
                    try { sendSms(hit.second, parts[1].trim()); reply("Sent.", asText) } catch (e: Exception) { reply("I couldn't send it.", asText) }
                }
            }
            "Phone task" -> runAgent(payload, asText)
            else -> reply(payload, asText)
        }
    }

    // ================================================================== everyday helpers
    private val ones = mapOf("zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    private fun parseNum(s: String): Double? {
        val t = s.trim().lowercase()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it }
        var total = 0; var cur = 0; var any = false
        for (w in t.split(Regex("[\\s-]+"))) {
            when {
                w == "and" -> {}
                ones.containsKey(w) -> { cur += ones.getValue(w); any = true }
                tens.containsKey(w) -> { cur += tens.getValue(w); any = true }
                w == "hundred" -> { cur = (if (cur == 0) 1 else cur) * 100; any = true }
                w == "thousand" -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0; any = true }
                else -> return null
            }
        }
        return if (any) (total + cur).toDouble() else null
    }

    private fun calc(expr0: String): String? {
        var e = " " + expr0.lowercase() + " "
        e = e.replace("multiplied by", "*").replace("divided by", "/").replace(" times ", " * ").replace(" plus ", " + ")
            .replace(" minus ", " - ").replace(" over ", " / ").replace(" into ", " * ").replace(" x ", " * ")
        e = e.replace(Regex("(?<=[\\s\\d])([+*/-])(?=[\\s\\d])"), " $1 ")
        val toks = e.trim().split(Regex("\\s+"))
        val nums = ArrayList<Double>(); val ops = ArrayList<Char>()
        val buf = ArrayList<String>()
        fun flush(): Boolean { val n = parseNum(buf.joinToString(" ")) ?: return false; nums.add(n); buf.clear(); return true }
        for (t in toks) {
            if (t.length == 1 && t[0] in "+-*/") { if (!flush()) return null; ops.add(t[0]) } else buf.add(t)
        }
        if (!flush() || ops.isEmpty() || ops.size != nums.size - 1) return null
        // multiply and divide first
        var i = 0
        while (i < ops.size) {
            if (ops[i] == '*' || ops[i] == '/') {
                if (ops[i] == '/' && nums[i + 1] == 0.0) return "I can't divide by zero."
                nums[i] = if (ops[i] == '*') nums[i] * nums[i + 1] else nums[i] / nums[i + 1]
                nums.removeAt(i + 1); ops.removeAt(i)
            } else i++
        }
        var r = nums[0]
        for (k in ops.indices) r = if (ops[k] == '+') r + nums[k + 1] else r - nums[k + 1]
        val shown = if (r == Math.floor(r) && Math.abs(r) < 1e12) r.toLong().toString() else "%.4f".format(r).trimEnd('0').trimEnd('.')
        return "The answer is $shown."
    }

    /** "seven thirty pm", "7:30", "6 am" -> hour, minute */
    private fun parseClock(s0: String): Pair<Int, Int>? {
        var s = s0.lowercase().replace("o'clock", "").replace("oclock", "")
        val pm = Regex("\\bp\\.? ?m\\b|\\bpm\\b").containsMatchIn(s)
        val am = Regex("\\ba\\.? ?m\\b|\\bam\\b").containsMatchIn(s)
        s = s.replace(Regex("\\b[ap]\\.? ?m\\.?\\b"), "").trim()
        var h: Int; var m = 0
        Regex("^(\\d{1,2})(?::|\\s)?(\\d{2})?$").find(s)?.let { r ->
            h = r.groupValues[1].toInt(); m = r.groupValues[2].toIntOrNull() ?: 0
            return fixClock(h, m, am, pm)
        }
        val parts = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        h = parseNum(parts[0])?.toInt() ?: return null
        if (parts.size > 1) m = parseNum(parts.drop(1).joinToString(" "))?.toInt() ?: 0
        return fixClock(h, m, am, pm)
    }

    private fun fixClock(h0: Int, m: Int, am: Boolean, pm: Boolean): Pair<Int, Int>? {
        var h = h0
        if (h > 24 || m > 59) return null
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        return h to m
    }

    private val jokes = listOf(
        "Why did the phone go to school? To improve its connection.",
        "Why don't scientists trust atoms? Because they make up everything.",
        "What do you call a sleeping bull? A bulldozer.",
        "Why was the math book sad? It had too many problems.",
        "I told my computer I needed a break, and now it won't stop sending me coffee ads.",
        "Why did the student eat his homework? Because the teacher said it was a piece of cake.",
        "What has hands but cannot clap? A clock.",
        "Why can't a bicycle stand on its own? Because it's two tired.",
        "What do you call fake spaghetti? An impasta.",
        "Why did the tomato blush? Because it saw the salad dressing."
    )
    private val quotes = listOf(
        "The secret of getting ahead is getting started.",
        "It always seems impossible until it's done.",
        "Small steps every day become big results.",
        "Believe you can, and you're halfway there.",
        "Don't watch the clock. Do what it does, keep going.",
        "You are braver than you believe and stronger than you seem.",
        "Success is the sum of small efforts repeated day after day.",
        "Your only limit is you."
    )

    private fun lastLoc(): android.location.Location? {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(LocationManager::class.java)
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (e: Exception) { null }
    }

    private fun weatherWord(code: Int) = when (code) {
        0 -> "clear"; 1, 2 -> "mostly clear"; 3 -> "cloudy"; 45, 48 -> "foggy"; in 51..57 -> "drizzly"; in 61..67 -> "rainy"
        in 71..77 -> "snowy"; in 80..82 -> "showery"; 95, 96, 99 -> "stormy"; else -> "mixed"
    }

    private fun weatherReport(asText: Boolean) {
        busy = true
        bg.execute {
            val loc = lastLoc()
            val msg = if (loc == null) "I can't find your location. Please allow location and turn it on." else try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=temperature_2m,weather_code,wind_speed_10m&timezone=auto"
                val cur = JSONObject(Brain.get(url) ?: throw Exception("offline")).getJSONObject("current")
                "It is ${cur.optDouble("temperature_2m").toInt()} degrees and ${weatherWord(cur.optInt("weather_code"))} where you are, with wind of ${cur.optDouble("wind_speed_10m").toInt()} kilometres per hour."
            } catch (e: Exception) { "I need internet to check the weather." }
            main.post { reply(msg, asText) }
        }
    }

    /** How people answer each other in your text messages: she learns it (stays on your phone). */
    private fun doLearnSms(): Int {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return -1
        var n = 0
        val pending = HashMap<String, Pair<String, Long>>()
        try {
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "date ASC"
            )?.use { c ->
                while (c.moveToNext() && n < 1500) {
                    val addr = c.getString(0) ?: continue
                    val body = (c.getString(1) ?: "").trim()
                    val date = c.getLong(2)
                    val type = c.getInt(3)
                    if (body.isEmpty() || body.length > 160) continue
                    if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) pending[addr] = body to date
                    else if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                        val p = pending.remove(addr)
                        if (p != null && date - p.second < 3_600_000L) { store.addQA(p.first, body, "sms"); n++ }
                    }
                }
            }
        } catch (e: Exception) {}
        return n
    }

    fun learnFromSmsNow() {
        bg.execute {
            val n = doLearnSms()
            main.post { later { say(if (n < 0) "I need SMS permission first." else "Done. I learned $n ways you reply to people from your texts.") { finish() } } }
        }
    }

    // ================================================================== texting helpers
    private fun doText(who: String, msg: String?, asText: Boolean) {
        val hit = Contacts.find(this, store, who)
        if (hit == null) { reply("I couldn't find $who in your contacts.", asText); return }
        val send = { m: String ->
            confirm("I will text ${hit.first}: $m. Should I send it?") {
                try { sendSms(hit.second, m); reply("Done. I sent your message to ${hit.first}.", asText) }
                catch (e: Exception) { reply("Sorry, I could not send the message.", asText) }
            }
        }
        if (msg == null) ask("What should I say to ${hit.first}?") { t -> if (t.isBlank()) reply("Okay, never mind.", asText) else send(t) }
        else send(msg)
    }

    private fun doWhatsapp(who: String, msg: String, asText: Boolean) {
        if (Replies.find(who) != null) {
            confirm("I will reply to $who on WhatsApp: $msg. Should I send it?") {
                reply(if (Replies.send(this, who, msg)) "Done. I replied to $who." else "Sorry, I could not send it.", asText)
            }
            return
        }
        val hit = Contacts.find(this, store, who)
        if (hit == null) { reply("I couldn't find $who in your contacts.", asText); return }
        confirm("I will WhatsApp ${hit.first}: $msg. Should I send it?") {
            openUrl("https://wa.me/${hit.second.removePrefix("+")}?text=${Uri.encode(msg)}")
            busy = true
            main.postDelayed({
                reply(if (FlorenceAccessibility.click("Send")) "Done. I sent your WhatsApp to ${hit.first}." else "I opened the chat with ${hit.first}. Please tap send.", asText)
            }, 4500)
        }
    }

    /** Fix the things the offline recogniser mishears and drop polite filler. */
    private fun normalizeSpeech(s0: String): String {
        var s = s0
        s = s.replace(Regex("\\b(what'?s ?app|whats ?app|what sap|watsapp|what's up app)\\b"), "whatsapp")
        s = s.replace(Regex("\\b(tick ?tock|tic ?tac|tik tok)\\b"), "tiktok")
        s = s.replace(Regex("\\b(you tube|u tube)\\b"), "youtube")
        s = s.replace(Regex("\\bface book\\b"), "facebook")
        var prev: String
        do {
            prev = s
            s = s.replace(Regex("^(?:hey |hi |ok |okay |please |can you |could you |would you |will you |i want you to |i need you to |go ahead and |kindly |mum |mom )"), "")
        } while (s != prev)
        s = s.replace(Regex("^(?:open|launch|start) (?:the )?(?:messages?|messaging|sms)(?: app)?(?: and| then)? (?=send|text|message)"), "")
        s = s.replace(Regex(" for me$"), "")
        return s.trim()
    }

    private fun tokensOf(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }.toSet()

    /** Answers from what we said to each other before (works without internet). */
    private fun learnedAnswer(c: String): String? {
        val q = tokensOf(c)
        if (q.isEmpty()) return null
        var best: String? = null
        var bestScore = 0.0
        for ((p, r, _) in store.qaAll(1500)) {
            val pt = tokensOf(p)
            if (pt.isEmpty()) continue
            val inter = q.count { it in pt }
            val score = inter.toDouble() / (q.size + pt.size - inter)
            if (score > bestScore) { bestScore = score; best = r }
        }
        return if (bestScore >= 0.6) best else null
    }

    // ================================================================== the command brain
    private fun handle(raw: String) {
        val c0 = raw.trim().lowercase().trim('.', ',', '!', '?')
        val asText = Regex("\\b(in text|as text|show me|write it)\\b").containsMatchIn(c0) || store.on("text_mode", false)
        val c = normalizeSpeech(c0.replace(Regex("\\b(in text|as text|write it)\\b"), "").trim().replace(Regex("\\s+"), " "))
        val prevConvo = lastConvoReply
        lastConvoReply = null
        fun out(t: String) = reply(t, asText)

        // ---- your own commands first
        for ((phrase, kind, payload) in store.allCommands()) {
            if (phrase.isNotBlank() && c.contains(phrase)) { runCustom(kind, payload, asText); return }
        }

        // ---- sleep / goodbye / shut down
        if (Regex("^(go to sleep|sleep mode|sleep now|go to sleep mode|sleep)$").matches(c)) {
            convoActive = false
            sleeping = true; store.put("sleeping", "on")
            busy = true
            say("Okay, going to sleep. Say wake up when you need me.") { finish() }
            return
        }
        if (Regex("^(goodbye|bye|bye bye|that's all|that is all|stop listening)$").matches(c)) {
            convoActive = false
            out("Okay, talk to you soon."); return
        }
        if (c == "shut down" || c == "turn yourself off") { convoActive = false; say("Shutting down. Open the app to wake me again.") { stopSelf() }; return }

        // ---- toggles
        Regex("^(auto ?reply|text mode|away mode|voice lock|conversation mode) (on|off)$").find(c)?.let { m ->
            val key = when (m.groupValues[1].replace(" ", "")) {
                "autoreply" -> "autoreply"; "textmode" -> "text_mode"; "awaymode" -> "away_mode"
                "voicelock" -> "voice_lock"; else -> "convo_mode"
            }
            store.put(key, m.groupValues[2]); out("Okay, ${m.groupValues[1]} is ${m.groupValues[2]}."); return
        }
        if (c.contains("stop asking before sending")) { store.put("confirm_send", "off"); out("Okay, I will send without asking."); return }
        if (c.contains("ask before sending")) { store.put("confirm_send", "on"); out("Okay, I will ask before sending."); return }

        // ---- memory: learn, forget
        if (Regex("^(?:learn|remember)(?: this| that| something| something new| what i say| everything)?$").matches(c)) {
            ask("Okay, I'm ready to learn it. Go ahead.") { t -> if (t.isBlank()) out("I didn't hear anything.") else out(learn(t)) }
            return
        }
        Regex("^(?:learn|remember|note|save|store|memorize)(?: this| that)?[:,]? ?(.+)$").find(c)?.let { m ->
            if (!m.groupValues[1].startsWith("contact")) { out(learn(m.groupValues[1])); return }
        }
        Regex("^(my name is .+|(?:i am|i'm) .+ years?(?: old)?)$").find(c)?.let { out(learn(it.groupValues[1])); return }
        Regex("^forget (?:my |that )?(.+)$").find(c)?.let { m ->
            store.deleteFact(m.groupValues[1]); out("Okay, I forgot your ${m.groupValues[1]}."); return
        }
        Regex("^save contact\\s+(.+?)\\s+(?:as|number|is)\\s+(.+)$").find(c)?.let { m ->
            val num = Contacts.spokenDigits(m.groupValues[2])
            if (num == null) { out("I didn't catch the number. Please try again."); return }
            store.saveContact(m.groupValues[1], num); out("Saved ${m.groupValues[1]}."); return
        }
        Regex("^(?:when i say|if i say)\\s+(.+?),?\\s+(?:you say|say|reply|answer)\\s+(.+)$").find(c)?.let { m ->
            store.teach(m.groupValues[1], m.groupValues[2]); out("Learned. I will remember that."); return
        }
        Regex("^(?:add|new|record) (?:a )?voice$").find(c)?.let { out("Please add voices from the Voices screen in my app."); return }

        // ---- control the phone
        if (Regex("^(?:go )?back$").matches(c)) { FlorenceAccessibility.global(AccessibilityService.GLOBAL_ACTION_BACK); out("Done."); return }
        if (Regex("^(?:go )?home$|^go to (?:the )?home screen$").matches(c)) { FlorenceAccessibility.global(AccessibilityService.GLOBAL_ACTION_HOME); out("Done."); return }
        if (Regex("recent apps|show recents|app switcher").containsMatchIn(c)) { FlorenceAccessibility.global(AccessibilityService.GLOBAL_ACTION_RECENTS); out("Done."); return }
        if (Regex("(pull down|open|show) (the )?notification(s)? (panel|bar|shade)|pull down notifications").containsMatchIn(c)) { FlorenceAccessibility.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); out("Done."); return }
        if (c.contains("quick settings")) { FlorenceAccessibility.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); out("Done."); return }
        if (c.contains("screenshot")) { out(if (FlorenceAccessibility.screenshot()) "Screenshot taken." else "I couldn't take a screenshot."); return }
        if (Regex("^lock (?:the |my )?(?:phone|screen)$").matches(c)) { out(if (FlorenceAccessibility.lockScreen()) "Locking the phone." else "I couldn't lock the phone."); return }
        Regex("^scroll (down|up)$").find(c)?.let { m -> FlorenceAccessibility.scroll(m.groupValues[1] == "down"); out("Done."); return }
        Regex("^swipe (left|right|up|down)$").find(c)?.let { m ->
            when (m.groupValues[1]) { "left" -> FlorenceAccessibility.swipeSide(true); "right" -> FlorenceAccessibility.swipeSide(false)
                "up" -> FlorenceAccessibility.scroll(true); else -> FlorenceAccessibility.scroll(false) }
            out("Done."); return
        }
        Regex("^(?:tap|click|press|touch|select) (?:on )?(.+)$").find(c)?.let { m ->
            if (m.groupValues[1] == "enter") { FlorenceAccessibility.pressEnter(); out("Done."); return }
            out(if (FlorenceAccessibility.click(m.groupValues[1])) "Done." else "I couldn't find ${m.groupValues[1]} on the screen."); return
        }
        Regex("^(?:type|write) (.+)$").find(c)?.let { m -> out(if (FlorenceAccessibility.type(m.groupValues[1])) "Typed." else "I couldn't find a place to type."); return }
        if (Regex("^(?:press enter|submit|search it)$").matches(c)) { FlorenceAccessibility.pressEnter(); out("Done."); return }
        Regex("^(?:turn |switch )?(on|off) (?:the )?(?:flashlight|torch)$").find(c)?.let { m -> out(if (flashlight(m.groupValues[1] == "on")) "Done." else "I couldn't reach the flashlight."); return }
        Regex("^(?:turn |make )?(?:the )?volume (up|down)$|^(?:turn )?(up|down) the volume$").find(c)?.let { m ->
            volume((m.groupValues[1].ifEmpty { m.groupValues[2] }) == "up"); out("Done."); return
        }
        Regex("^take (?:a )?(picture|photo|pic|selfie)").find(c)?.let { m -> takePicture(m.groupValues[1] == "selfie", asText); return }
        Regex("^(?:open |go to )?(?:chrome|google)(?: and)? (?:search for|search|look for|find|google) (.+)$|^(?:search|google|look up) (?:for )?(.+)$").find(c)?.let { m ->
            val q = m.groupValues[1].ifEmpty { m.groupValues[2] }
            openUrl("https://www.google.com/search?q=" + Uri.encode(q)); out("Searching for $q."); return
        }
        Regex("^(?:go to|open) (?:the )?(?:website )?(\\S+\\.(?:com|org|net|ng|io|edu|gov|co)\\S*)$").find(c)?.let { m ->
            openUrl("https://" + m.groupValues[1]); out("Opening ${m.groupValues[1]}."); return
        }
        Regex("^(?:take control|control my phone|operate my phone|autopilot|do this for me|handle this)[,:]? ?(?:and |to )?(.+)$").find(c)?.let { m ->
            runAgent(m.groupValues[1], asText); return
        }

        // ---- calls, texts, WhatsApp
        Regex("^(?:whatsapp|send (?:a )?whatsapp(?: message)? to)\\s+(.+?)\\s+(?:saying|that says|say|that|and say|send|with)\\s+(.+)$").find(c)?.let { m ->
            doWhatsapp(m.groupValues[1], m.groupValues[2], asText); return
        }
        Regex("^(?:send (?:a )?(?:text|sms|message)(?: message)? to|text|sms|message|tell)\\s+(.+?)\\s+(?:saying|that says|say|that|and say|send|with|to say)\\s+(.+)$").find(c)?.let { m ->
            doText(m.groupValues[1], m.groupValues[2], asText); return
        }
        Regex("^send (.+?) to (.+)$").find(c)?.let { m ->
            val body = m.groupValues[1]
            val generic = listOf("message", "a message", "a text", "text", "sms", "a text message", "text message")
            doText(m.groupValues[2], if (body in generic) null else body, asText); return
        }
        Regex("^(?:send (?:a )?(?:text|sms|message)(?: message)? to|text|sms|message)\\s+(.+)$").find(c)?.let { m ->
            doText(m.groupValues[1], null, asText); return
        }
                Regex("^(?:call|phone|ring|dial)\\s+(.+)$").find(c)?.let { m ->
            val hit = Contacts.find(this, store, m.groupValues[1])
            if (hit == null) { out("I couldn't find ${m.groupValues[1]} in your contacts."); return }
            try {
                getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", hit.second, null), Bundle())
                out("Calling ${hit.first}.")
            } catch (e: Exception) { out("Sorry, I could not place the call.") }
            return
        }
        if (Regex("\\b(answer|pick up|accept)\\b.*\\bcall\\b|^answer$").containsMatchIn(c)) { answerCall(); out("Answering the call."); return }
        if (Regex("\\b(reject|decline|end|hang up|cut)\\b.*\\bcall\\b|^hang up$").containsMatchIn(c)) { declineCall(); out("Done."); return }

        // ---- reading
        if (Regex("^read (?:my )?notifications?$").matches(c)) {
            val l = FlorenceNotificationListener.instance
            if (l == null) { out("Please give me notification access in the app first."); return }
            val items = l.activeNotifications.filter { !FlorenceNotificationListener.skipped(it.packageName) && it.packageName != packageName }
            if (items.isEmpty()) { out("You have no new notifications."); return }
            val lines = items.take(8).map { n ->
                "${appLabel(n.packageName)}. ${n.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""}. ${Replies.fullText(n.notification)}"
            }
            out("You have ${items.size} notifications. " + lines.joinToString(" Next. ")); return
        }
        Regex("^read (?:the |my )?(?:whatsapp |text )?(?:message|messages|chat) (?:from|of) (.+)$").find(c)?.let { m -> readChatFrom(m.groupValues[1], asText); return }
        if (Regex("^read (?:it|this|that)(?: to me| for me| out| out loud)?$|^read (?:what is on|what's on) (?:my |the )?screen$").containsMatchIn(c)) { readScreenAloud(asText); return }
        if (Regex("^read what (?:i|we) (?:searched|googled|searched for)").containsMatchIn(c)) {
            if (FlorenceAccessibility.currentPackage().contains("chrome")) readScreenAloud(asText)
            else {
                openApp("chrome"); busy = true
                main.postDelayed({ readScreenAloud(asText) }, 3000)
            }
            return
        }
        Regex("^(?:open|launch|start|go to)\\s+(.+)$").find(c)?.let { m -> openAppReport(m.groupValues[1], asText); return }
        Regex("^read (?:my |the )?(.+?)(?: for me)?$").find(c)?.let { m ->
            val target = m.groupValues[1]
            if (Regex("screen|this|page").containsMatchIn(target)) { readScreenAloud(asText); return }
            if (!openApp(target)) { out("I couldn't find $target."); return }
            busy = true
            main.postDelayed({ readScreenAloud(asText) }, 3500)
            return
        }

        // ---- everyday helpers
        if (Regex("^(?:tell me )?(?:a )?joke$|^make me laugh$|^tell me something funny$").matches(c)) { out(jokes.random()); return }
        if (Regex("^(?:motivate me|inspire me|give me a quote|quote of the day)$").matches(c)) { out(quotes.random()); return }
        if (Regex("^flip a coin$|^toss a coin$").matches(c)) { out(if (Random().nextBoolean()) "It's heads." else "It's tails."); return }
        if (Regex("^roll a dice$|^roll a die$|^roll the dice$").matches(c)) { out("You rolled a ${Random().nextInt(6) + 1}."); return }
        if (Regex("^(?:what(?:'s| is) the weather(?: like)?(?: today| now)?|weather|how is the weather|will it rain today)$").matches(c)) { weatherReport(asText); return }
        Regex("^(?:set (?:a )?)?timer for (.+?) (second|minute|hour)s?$").find(c)?.let { m ->
            val n = parseNum(m.groupValues[1])
            if (n == null) { out("I didn't catch how long."); return }
            val secs = (n * when (m.groupValues[2]) { "hour" -> 3600 else -> if (m.groupValues[2] == "minute") 60 else 1 }).toInt()
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_MESSAGE, "Mum Florence").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            out(if (launch(i)) "Timer set for ${m.groupValues[1]} ${m.groupValues[2]}s." else "I couldn't set the timer."); return
        }
        Regex("^(?:set (?:an )?alarm|wake me up|alarm)(?: for| at)? (.+)$").find(c)?.let { m ->
            val t = parseClock(m.groupValues[1])
            if (t == null) { out("I didn't catch the time."); return }
            val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.first).putExtra(AlarmClock.EXTRA_MINUTES, t.second).putExtra(AlarmClock.EXTRA_MESSAGE, "Mum Florence").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            out(if (launch(i)) "Alarm set for ${t.first}:${"%02d".format(t.second)}." else "I couldn't set the alarm."); return
        }
        Regex("^(?:navigate|directions|route|take me|drive me|how do i get)(?: to)? (.+)$").find(c)?.let { m ->
            val place = m.groupValues[1]
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps")
            if (!launch(nav)) launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
            out("Getting directions to $place."); return
        }
        Regex("^play (.+?)(?: on youtube)?$").find(c)?.let { m ->
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(m.groupValues[1]))))
            out("Here are the results for ${m.groupValues[1]}."); return
        }
        if (Regex("^(?:ideas?|give me ideas|what can i say|show (?:me )?ideas|open ideas)$").matches(c)) {
            launch(Intent(this, IdeasActivity::class.java)); out("Here are some ideas of what you can ask me."); return
        }
        if (Regex("^learn from my (?:texts|messages|sms)$").matches(c)) {
            busy = true
            bg.execute { val n = doLearnSms(); main.post { out(if (n < 0) "I need SMS permission first." else "Done. I learned $n ways you reply to people from your texts.") } }
            return
        }
        if (Regex("\\b(plus|minus|times|multiplied|divided|over|into)\\b|[0-9] ?[+*/x-] ?[0-9]").containsMatchIn(c)) {
            calc(c.replace(Regex("^(?:calculate |compute |what is |what's |how much is |work out )"), ""))?.let { out(it); return }
        }

        // ---- taught answers, then what I know about you
        store.taught(c)?.let { out(it); return }
        offlineAnswer(c)?.let { out(it); return }
        memoryAnswer(c)?.let { out(it); return }
        Regex("^(?:check|ask|search) (?:my )?database (?:for |about )?(.+)$").find(c)?.let { m ->
            val url = store.get("db_url")
            if (url.isBlank()) { out("Please add your database address in my settings first."); return }
            busy = true
            bg.execute { val a = Brain.askDatabase(url, m.groupValues[1]); main.post { out(a ?: "I couldn't reach your database.") } }
            return
        }

        // ---- conversation: online brain, else what we have learned together
        busy = true
        bg.execute {
            val strong = LocalMind.best(this, store, c, LocalMind.STRONG)?.first
            val ans = strong ?: Brain.ask(this, store, c)
            val weak = if (ans == null) LocalMind.best(this, store, c, LocalMind.WEAK)?.first else null
            main.post {
                val learned = weak ?: (if (ans == null) learnedAnswer(c) else null)
                val small = if (ans == null && learned == null) smallTalk(c) else null
                val spoken = ans ?: learned ?: small
                if (spoken != null) {
                    if (prevConvo != null) store.addQA(prevConvo, c, "you")
                    if (strong == null) store.addQA(c, spoken, "me")
                    lastConvoReply = spoken
                    out(spoken)
                } else {
                    out(
                        when {
                            store.get("api_key").isBlank() -> "I don't know that yet. Teach me by saying, learn this, and then tell me, or add my key in settings."
                            !Brain.online(this) -> "I'm offline right now, so I can only use what we talked about before, and help with your phone."
                            else -> "Sorry, I couldn't think of an answer. Check my key in settings."
                        }
                    )
                }
            }
        }
    }
}
