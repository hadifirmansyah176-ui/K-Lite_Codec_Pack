package com.example.player

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Seek bar bergaya pemutar desktop: isi hijau, penanda biru, garis tepi hitam. */
class SeekBarView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onSeek: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.rgb(242, 242, 242) }
    private val fill = Paint().apply { color = Color.rgb(127, 209, 139) }
    private val marker = Paint().apply { color = Color.rgb(30, 136, 229) }
    private val border = Paint().apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.5f * d
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(1f * d, 1f * d, width - 1f * d, height - 1f * d)
        c.drawRect(r, bg)
        val x = r.left + (r.width()) * progress
        c.drawRect(r.left, r.top, x, r.bottom, fill)
        c.drawRect(x - 1.5f * d, r.top, x + 1.5f * d, r.bottom, marker)
        c.drawRect(r, border)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                progress = e.x / width
                if (e.action == MotionEvent.ACTION_UP) { onSeek?.invoke(progress); performClick() }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
