package com.mumflorence.app

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.Button

/** Colours and shapes for the whole app (dark, glowing, rounded). */
object Theme {
    val BG1 = 0xFF080D1C.toInt()
    val BG2 = 0xFF1B1245.toInt()
    val CARD = 0xFF172038.toInt()
    val CARD2 = 0xFF222C50.toInt()
    val ACCENT = 0xFF7C5CFF.toInt()
    val ACCENT2 = 0xFF00D4FF.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val MUTED = 0xFFA9B2CC.toInt()
    val GOOD = 0xFF35D07F.toInt()
    val BAD = 0xFFFF5C7A.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun bg() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG1, BG2))

    fun card(ctx: Context, color: Int = CARD, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(ctx, radius).toFloat()
    }

    fun pill(ctx: Context) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2)).apply {
        cornerRadius = dp(ctx, 28).toFloat()
    }

    fun styleButton(b: Button, primary: Boolean = true) {
        b.background = if (primary) pill(b.context) else card(b.context, CARD2, 28)
        b.setTextColor(TEXT)
        b.isAllCaps = false
        b.textSize = 15f
        b.stateListAnimator = null
        b.setPadding(dp(b.context, 16), dp(b.context, 12), dp(b.context, 16), dp(b.context, 12))
        pressAnim(b)
    }

    fun styleWindow(a: Activity) {
        a.window.setBackgroundDrawable(bg())
        a.window.statusBarColor = BG1
        a.window.navigationBarColor = BG1
    }

    /** Little "squish" when you press something. */
    fun pressAnim(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }
}
