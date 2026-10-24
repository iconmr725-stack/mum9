package com.mumflorence.app

import android.app.Activity
import android.os.Bundle

class CommandsActivity : Activity() {
    private lateinit var store: Store

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        render()
    }

    private fun render() {
        val f = Form(this, store)
        f.title("My own commands")
        f.text("Add new things she can do. When what you say contains the phrase, she runs the action.", 13f)
        val cmds = store.allCommands()
        f.text(if (cmds.isEmpty()) "No commands yet." else cmds.joinToString("\n") { "• \"${it.first}\"  →  ${it.second}: ${it.third}" })

        f.title("Add a command")
        val phrase = f.plainEdit("When I say", "for example: good morning routine")
        val kind = f.plainChoice("She should", listOf("Say", "Open app", "Open website", "Call", "Text", "Phone task"))
        val payload = f.plainEdit("Details", "Say: the words. Open app: app name. Website: address. Call: name or number. Text: name: message. Phone task: what to do on the phone")
        f.button("Save command") {
            if (phrase.text.isNotBlank() && payload.text.isNotBlank()) {
                store.saveCommand(phrase.text.toString(), kind.selectedItem.toString(), payload.text.toString().trim())
                render()
            }
        }
        val del = f.plainEdit("Delete a command (type its phrase)")
        f.button("Delete") { if (del.text.isNotBlank()) { store.deleteCommand(del.text.toString()); render() } }
        setContentView(f.scroll())
    }
}
