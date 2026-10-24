package com.mumflorence.app

import android.app.Activity
import android.os.Bundle

class MemoryActivity : Activity() {
    private lateinit var store: Store

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        render()
    }

    private fun render() {
        val f = Form(this, store)
        f.title("What I know about you")
        val facts = store.allFacts()
        f.text(if (facts.isEmpty()) "Nothing yet. Say: remember my name is ..." else facts.entries.joinToString("\n") { "• ${it.key}: ${it.value}" })

        f.title("Teach me a fact")
        val k = f.plainEdit("About (for example: name, age, favourite food)")
        val v = f.plainEdit("Value")
        f.button("Save fact") {
            if (k.text.isNotBlank() && v.text.isNotBlank()) { store.setFact(k.text.toString(), v.text.toString()); render() }
        }
        val del = f.plainEdit("Forget a fact (type its name)")
        f.button("Forget it") { if (del.text.isNotBlank()) { store.deleteFact(del.text.toString()); render() } }

        f.title("Notes you asked me to remember")
        val notes = store.notes(30)
        f.text(if (notes.isEmpty()) "None yet." else notes.joinToString("\n") { "• $it" })
        val n = f.plainEdit("Add a note")
        f.button("Save note") { if (n.text.isNotBlank()) { store.addNote(n.text.toString()); render() } }
        f.button("Clear all notes") { store.clearNotes(); render() }

        f.title("What I learned from talking")
        f.text("${store.qaCount()} things learned from our chats, your texts and chat notifications.")
        f.button("Learn from my text messages now") {
            val sv = FlorenceService.instance
            if (sv == null) android.widget.Toast.makeText(this, "She is not running yet.", android.widget.Toast.LENGTH_SHORT).show()
            else { sv.learnFromSmsNow(); android.widget.Toast.makeText(this, "Learning...", android.widget.Toast.LENGTH_SHORT).show() }
        }
        f.button("Forget everything I learned from chats") { store.clearQA(); render() }

        f.title("Our conversations")
        val log = store.recentConvo(40)
        f.text(if (log.isEmpty()) "Nothing yet." else log.joinToString("\n") { (if (it.first == "user") "You: " else "Me: ") + it.second }, 13f)
        f.button("Clear conversation history") { store.clearConvo(); render() }

        setContentView(f.scroll())
    }
}
