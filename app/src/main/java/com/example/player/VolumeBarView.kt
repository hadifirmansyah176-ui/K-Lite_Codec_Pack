package com.example.player

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Bar volume 0-100: batang kecil di kiri -> besar di kanan, isi biru, garis tepi hitam, ada angka. */
class VolumeBarView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var level = 100
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    var onChange: ((Int) -> Unit)? = null

    private val bars = 10
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 120, 255) }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 215, 240) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.5f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 15f * d; isFakeBoldText = true
    }
    private val numberWidth = 44f * d

    override fun onDraw(c: Canvas) {
        c.drawText(level.toString(), 0f, height / 2f + text.textSize / 3f, text)
        val gap = 3f * d
        val barW = (width - numberWidth - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val h = height * (i + 1) / bars.toFloat()
            val l = numberWidth + i * (barW + gap)
            val r = RectF(l, height - h, l + barW, height.toFloat())
            val active = level >= (i + 1) * 100 / bars
            c.drawRect(r, if (active) fill else empty)
            c.drawRect(r, stroke)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val p = ((e.x - numberWidth) / (width - numberWidth) * 100).toInt()
            level = p
            onChange?.invoke(level)
            return true
        }
        return super.onTouchEvent(e)
    }
}
