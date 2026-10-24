package com.mumflorence.app

import android.app.Activity
import android.os.Bundle

/** A list of things you can say to her. */
class IdeasActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val f = Form(this, Store(this))
        f.title("Ideas: what you can say")
        f.muted("Say her name first, or just keep talking after she answers. Add \"in text\" to get the answer on screen.")

        fun section(t: String, lines: List<String>) { f.title(t); f.text(lines.joinToString("\n") { "• $it" }) }

        section("Talk like friends", listOf("how are you", "what's up", "I'm fine / I'm cool / I'm tired", "tell me a joke", "tell me a fun fact", "motivate me", "tell me a story", "quiz me"))
        section("Teach her", listOf("learn this ... then say anything", "my name is ...", "I am seventeen years old", "remember my favourite food is ...", "when I say X, say Y", "learn from my texts"))
        section("Calls and messages", listOf("call Anita", "text Mary saying I am coming", "WhatsApp John saying hello", "send hi to Anita Church", "answer the call", "read my notifications"))
        section("Control the phone", listOf("open WhatsApp / TikTok / camera", "take a picture / take a selfie", "scroll down / scroll up", "go back / go home", "tap Send", "type hello", "take a screenshot", "turn on the flashlight", "volume up"))
        section("Everyday helpers", listOf("what is the time / the date / the weather", "set an alarm for seven thirty", "set a timer for ten minutes", "navigate to the market", "play Burna Boy on YouTube", "search for football news", "five times twelve", "flip a coin", "roll a dice"))
        section("Let her drive", listOf("take control and open YouTube and search for cooking videos", "take control and send my last photo to ..."))
        section("Rest and safety", listOf("go to sleep (say wake up to bring her back)", "stop (while she is talking)", "away mode on (she answers calls for you)"))
        f.muted("Designed by Godwin Akwabeng", 14f)
        setContentView(f.scroll())
    }
}
