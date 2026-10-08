package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/** The HUD orb: concentric rotating rings around a glowing core, colour and tempo follow the state. */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var state: OrbState = OrbState.IDLE
        set(v) { field = v; invalidate() }

    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    private fun colour(): Int = when (state) {
        OrbState.IDLE -> Color.parseColor("#2AA8FF")
        OrbState.LISTENING -> Color.parseColor("#3DFFB0")
        OrbState.THINKING -> Color.parseColor("#FFB02A")
        OrbState.SPEAKING -> Color.parseColor("#FF4D6D")
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f * 0.9f
        val c = colour()
        val speed = when (state) { OrbState.IDLE -> 1f; OrbState.LISTENING -> 2f; OrbState.THINKING -> 4f; OrbState.SPEAKING -> 2.5f }
        val pulse = 1f + 0.06f * Math.sin(Math.toRadians((phase * speed * 2).toDouble())).toFloat()

        core.shader = RadialGradient(cx, cy, base * 0.45f * pulse,
            intArrayOf(Color.argb(230, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, base * 0.45f * pulse, core)

        ring.color = c
        for (i in 0 until 3) {
            val r = base * (0.55f + i * 0.2f)
            val sweep = 90f + i * 40f
            val dir = if (i % 2 == 0) 1 else -1
            ring.alpha = 220 - i * 50
            canvas.save()
            canvas.rotate(dir * phase * speed * (1f + i * 0.4f), cx, cy)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 0f, sweep, false, ring)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, sweep, false, ring)
            canvas.restore()
        }
    }
}
