package com.bringtech.ft600

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Conta-giros redondo estilo painel. */
class GaugeView(context: Context) : View(context) {

    private var value = 0f
    private val max = 8000f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF1B2028.toInt()
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cores.GRAY
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setValue(v: Float) {
        value = v.coerceIn(0f, max)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        val stroke = size * 0.07f
        val cx = w / 2f
        val cy = h / 2f
        val r = size / 2f - stroke
        track.strokeWidth = stroke
        bar.strokeWidth = stroke
        rect.set(cx - r, cy - r, cx + r, cy + r)

        c.drawArc(rect, 135f, 270f, false, track)
        val frac = value / max
        if (frac > 0.003f) {
            bar.color = if (frac > 0.85f) Cores.RED else Cores.ORANGE
            c.drawArc(rect, 135f, 270f * frac, false, bar)
        }

        small.textSize = size * 0.055f
        val steps = (max / 1000f).toInt()
        for (i in 0..steps) {
            val ang = Math.toRadians(135.0 + 270.0 * i / steps)
            val rr = r - stroke * 1.7f
            val x = cx + cos(ang).toFloat() * rr
            val y = cy + sin(ang).toFloat() * rr
            c.drawText(i.toString(), x, y + small.textSize / 3f, small)
        }

        big.textSize = size * 0.2f
        c.drawText(value.toInt().toString(), cx, cy + size * 0.05f, big)
        small.textSize = size * 0.07f
        c.drawText("RPM", cx, cy + size * 0.17f, small)
    }
}
