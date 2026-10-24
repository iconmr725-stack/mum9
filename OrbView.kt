package com.mumflorence.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The glowing, pulsing orb that shows what she is doing. */
class OrbView(ctx: Context) : View(ctx) {
    var state: String = "off"
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedFraction; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun colors(): Pair<Int, Int> = when (state) {
        "listening to you" -> 0xFF35D07F.toInt() to 0xFF00B3A4.toInt()
        "talking" -> 0xFFFF5CA8.toInt() to 0xFFFF9A3C.toInt()
        "working", "thinking" -> 0xFFFFC83C.toInt() to 0xFFFF7A3C.toInt()
        "sleeping" -> 0xFF5C6BC0.toInt() to 0xFF283593.toInt()
        "off" -> 0xFF6B7280.toInt() to 0xFF374151.toInt()
        else -> 0xFF7C5CFF.toInt() to 0xFF00D4FF.toInt()
    }

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.24f
        val (c1, c2) = colors()
        val speed = when (state) { "talking" -> 3 ; "listening to you" -> 2; "working", "thinking" -> 2; "sleeping" -> 1; else -> 1 }
        val t = (phase * speed) % 1f

        // expanding rings
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        for (i in 0..2) {
            val f = (t + i / 3f) % 1f
            paint.color = alpha(c1, ((1f - f) * 140).toInt())
            canvas.drawCircle(cx, cy, base * (1.05f + f * 1.0f), paint)
        }

        // soft glow
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, base * 2.2f, intArrayOf(alpha(c1, 90), alpha(c2, 0)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, base * 2.2f, paint)

        // the orb
        val pulse = 1f + (if (state == "sleeping") 0.03f else 0.07f) * sin(phase * 2f * PI.toFloat() * speed)
        paint.shader = RadialGradient(
            cx - base * 0.35f, cy - base * 0.4f, base * pulse * 1.5f,
            intArrayOf(0xFFFFFFFF.toInt(), c1, c2), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, base * pulse, paint)
        paint.shader = null
    }
}
