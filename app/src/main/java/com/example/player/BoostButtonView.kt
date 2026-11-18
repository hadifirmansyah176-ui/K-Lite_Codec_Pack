package com.example.player

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Tombol boost berbentuk lingkaran: putih = nonaktif, merah = aktif. */
class BoostButtonView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var active = false
        set(v) { field = v; invalidate() }
    var onToggle: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.5f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 10f * d
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 1.5f * d
        fill.color = if (active) Color.rgb(229, 28, 35) else Color.WHITE
        text.color = if (active) Color.WHITE else Color.BLACK
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, border)
        c.drawText("200%", cx, cy + text.textSize / 3f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            active = !active
            onToggle?.invoke(active)
            performClick()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
