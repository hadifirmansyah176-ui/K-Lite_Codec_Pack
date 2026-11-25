package com.example.player

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Tombol boost berbentuk lingkaran.
 * Putih = mati, merah = aktif. Setiap disentuh: mati -> 200% -> 400% -> mati.
 */
class BoostButtonView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** 0 = mati, 200, atau 400 */
    var level = 0
        set(v) { field = v; invalidate() }
    var onLevel: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.5f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 1.5f * d
        val on = level > 0
        fill.color = if (on) Color.rgb(229, 28, 35) else Color.WHITE
        text.color = if (on) Color.WHITE else Color.BLACK
        text.textSize = (if (on) 10f else 8f) * d
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, border)
        c.drawText(if (on) "$level%" else "BOOST", cx, cy + text.textSize / 3f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            level = when (level) { 0 -> 200; 200 -> 400; else -> 0 }
            onLevel?.invoke(level)
            performClick()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
