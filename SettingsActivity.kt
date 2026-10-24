package com.mumflorence.app

import android.app.Activity
import android.os.Bundle
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    private lateinit var form: Form

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val store = Store(this)
        form = Form(this, store)

        form.title("Her name and yours")
        form.edit("assistant_name", "Her name (what you call her)", "Mum Florence", "Use a common English name so she hears it well")
        val owner = form.plainEdit("Your name", "Your name", store.fact("name") ?: "")
        form.onSave { if (owner.text.isNotBlank()) store.setFact("name", owner.text.toString().trim()) }

        form.title("Online brain (Claude)")
        form.edit("api_key", "API key", "", "sk-ant-...", secret = true)
        val status = form.text("Status: ${Brain.lastStatus}")
        form.button("Test API key") {
            form.save()
            status.text = "Testing..."
            thread {
                val (ok, msg) = Brain.test(this, store.get("api_key"))
                runOnUiThread { status.text = (if (ok) "✓ " else "✗ ") + msg }
            }
        }
        form.text("Without a working key she still works offline: she uses everything you teach her.", 13f)

        form.title("Talking")
        form.choice("command_engine", "How she hears your commands", listOf("Google (more accurate)", "Offline (works without internet)"), "Google (more accurate)")
        form.text("Google hearing is much more accurate. It cannot be used together with the voice lock, so she then uses offline hearing.", 13f)
        form.toggle("learn_messages", "Learn from my text messages and chat notifications (stays on this phone)")
        form.toggle("convo_mode", "Keep chatting after I answer (friend mode)")
        form.toggle("text_mode", "Answer in text instead of voice", false)
        form.choice("notif_mode", "When a notification comes", listOf("ask first", "read directly", "silent"), "ask first")
        form.toggle("confirm_send", "Ask me before sending any message")
        form.seek("tts_pitch", "Voice pitch (higher = more feminine)", 80, 160, 115)
        form.seek("tts_rate", "Speaking speed", 60, 130, 95)

        form.title("Only my voice")
        form.toggle("voice_lock", "Only enrolled voices can command her")
        form.seek("voice_threshold", "Voice strictness (lower = stricter)", 20, 80, 45)
        form.text("Add and test voices on the Voices screen. Raise the number if she does not recognise you; lower it if others can command her.", 13f)

        form.title("Calls and messages")
        form.toggle("away_mode", "Away mode: she answers calls and tells the caller I'm not available", true)
        form.seek("away_delay", "Seconds the phone rings before she answers", 5, 90, 30)
        form.text("If the same person already called in the last 15 minutes and you missed it, she answers after about 6 seconds.", 13f)
        form.edit("away_message", "What she tells callers (leave empty for the default)", "", "Hello, this is ...")
        form.toggle("away_sms", "Also text the caller afterwards")
        form.toggle("autoreply", "Auto-reply to my saved contacts", false)
        form.edit("away_reply", "Auto-reply text when I have no online brain", "", "Sorry, I can't talk right now")
        form.edit("country_code", "Your country code", "", "+234")

        form.title("Safety")
        form.toggle("danger_alerts", "Alert me about dangerous weather and low battery")

        form.title("Your database")
        form.edit("db_url", "Web address she can ask questions to (optional)", "", "https://...")

        setContentView(form.scroll())
    }

    override fun onPause() {
        form.save()
        FlorenceService.instance?.reload()
        super.onPause()
    }
}
