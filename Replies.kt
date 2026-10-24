package com.mumflorence.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Remembers the "Reply" / "Answer" buttons of recent notifications so Florence can press them. */
object Replies {
    val byName = HashMap<String, Notification.Action>()
    @Volatile var callAnswer: Notification.Action? = null
    @Volatile var callDecline: Notification.Action? = null

    fun find(name: String): Notification.Action? {
        val n = name.lowercase().trim()
        if (n.isEmpty()) return null
        return byName[n] ?: byName.entries.firstOrNull { it.key.contains(n) || n.contains(it.key) }?.value
    }

    fun send(ctx: Context, name: String, text: String): Boolean {
        val action = find(name) ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (i in inputs) bundle.putCharSequence(i.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** All the lines of a chat notification (not just the last message). */
    @Suppress("DEPRECATION")
    fun fullText(n: Notification): String {
        val ex = n.extras
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val lines = msgs.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
            if (lines.isNotEmpty()) return lines.takeLast(5).joinToString(". ")
        }
        return (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
    }
}

class FlorenceNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: FlorenceNotificationListener? = null
        val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        val SKIP = listOf("com.termux", "android", "com.android.systemui", "com.google.android.gms")
        fun skipped(pkg: String) = pkg == "android" || SKIP.any { pkg.startsWith(it) }
    }

    private val store by lazy { Store(applicationContext) }
    private val learnedKeys = LinkedHashSet<String>()

    /** Chat notifications hold several messages from different people: learn how people answer each other. */
    @Suppress("DEPRECATION")
    private fun learnFromChat(n: Notification) {
        if (!store.on("learn_messages")) return
        val arr = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return
        var prevText: String? = null
        var prevSender: String? = null
        for (p in arr) {
            val b = p as? Bundle ?: continue
            val text = b.getCharSequence("text")?.toString()?.trim() ?: continue
            val sender = b.getCharSequence("sender")?.toString() ?: "?"
            val pt = prevText
            if (pt != null && prevSender != sender && text.length <= 160 && pt.length <= 160) {
                if (learnedKeys.add("$pt>>$text")) store.addQA(pt, text, "chat")
            }
            prevText = text; prevSender = sender
        }
        while (learnedKeys.size > 500) learnedKeys.remove(learnedKeys.first())
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = Replies.fullText(n)
        val isCall = n.category == Notification.CATEGORY_CALL && sbn.packageName in WHATSAPP

        n.actions?.let { actions ->
            if (isCall) {
                for (a in actions) {
                    val t = a.title.toString().lowercase()
                    if (t.contains("answer") || t.contains("accept")) Replies.callAnswer = a
                    if (t.contains("decline") || t.contains("reject")) Replies.callDecline = a
                }
            }
            actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }?.let {
                Replies.byName[title.lowercase()] = it
            }
        }
        if (!skipped(sbn.packageName)) { try { learnFromChat(n) } catch (e: Exception) {} }
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && !isCall) return
        if (skipped(sbn.packageName)) return
        FlorenceService.instance?.onNotification(sbn.packageName, title, text, isCall)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_CALL && sbn.packageName in WHATSAPP) {
            Replies.callAnswer = null
            Replies.callDecline = null
        }
    }
}
