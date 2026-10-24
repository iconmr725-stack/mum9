package com.mumflorence.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent

/** The "hands" of Mum Florence: reads any screen, taps, types, scrolls, goes back/home. */
class FlorenceAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: FlorenceAccessibility? = null

        fun currentPackage(): String = instance?.rootInActiveWindow?.packageName?.toString() ?: ""

        fun readScreen(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val seen = LinkedHashSet<String>()
            collect(root, seen)
            return seen.joinToString(". ").take(2500)
        }

        private fun collect(n: AccessibilityNodeInfo, out: MutableSet<String>) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
        }

        /** What is on screen, one element per line, marked [button] / [input]. */
        fun elements(): List<String> {
            val root = instance?.rootInActiveWindow ?: return emptyList()
            val out = ArrayList<String>()
            fun walk(n: AccessibilityNodeInfo) {
                if (out.size >= 70) return
                val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (label.isNotEmpty() || n.isEditable) {
                    val tag = if (n.isEditable) " [input]" else if (n.isClickable) " [button]" else ""
                    out.add(label.take(80) + tag)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
            }
            walk(root)
            return out
        }

        fun click(vararg labels: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            for (label in labels) {
                if (label.isBlank()) continue
                for (node in root.findAccessibilityNodeInfosByText(label)) {
                    var cur: AccessibilityNodeInfo? = node
                    while (cur != null) {
                        if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        cur = cur.parent
                    }
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (!r.isEmpty && tap(r.centerX().toFloat(), r.centerY().toFloat())) return true
                }
            }
            return false
        }

        private fun editable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> editable(c)?.let { return it } }
            return null
        }

        fun type(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: editable(root) ?: return false
            val b = Bundle()
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        }

        fun pressEnter(): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node != null && Build.VERSION.SDK_INT >= 30 &&
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
            return click("Search", "Go", "Send", "Enter")
        }

        fun global(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        fun lockScreen(): Boolean =
            Build.VERSION.SDK_INT >= 28 && global(GLOBAL_ACTION_LOCK_SCREEN)

        fun screenshot(): Boolean =
            Build.VERSION.SDK_INT >= 28 && global(GLOBAL_ACTION_TAKE_SCREENSHOT)

        private fun size(): Pair<Float, Float> {
            val m = instance?.resources?.displayMetrics ?: return 1080f to 2000f
            return m.widthPixels.toFloat() to m.heightPixels.toFloat()
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
            val inst = instance ?: return false
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
            return inst.dispatchGesture(g, null, null)
        }

        fun tap(x: Float, y: Float): Boolean {
            val inst = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
            return inst.dispatchGesture(g, null, null)
        }

        fun tapBottomCenter(): Boolean { val (w, h) = size(); return tap(w / 2, h * 0.88f) }

        fun scroll(down: Boolean): Boolean {
            val (w, h) = size()
            return if (down) swipe(w / 2, h * 0.72f, w / 2, h * 0.30f) else swipe(w / 2, h * 0.30f, w / 2, h * 0.72f)
        }

        fun swipeSide(left: Boolean): Boolean {
            val (w, h) = size()
            return if (left) swipe(w * 0.8f, h / 2, w * 0.2f, h / 2) else swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
