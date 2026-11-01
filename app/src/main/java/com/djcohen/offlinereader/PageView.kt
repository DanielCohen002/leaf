package com.djcohen.offlinereader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Draws one page of a chapter. Pagination is done by slicing a StaticLayout into screen-height runs of lines. */
class PageView(context: Context) : View(context) {
    enum class Zone { PREV, MENU, NEXT }

    var onZone: ((Zone) -> Unit)? = null
    var footerText: String = ""

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * density }

    private var text: CharSequence = ""
    private var layout: StaticLayout? = null
    private var pageStartLines = IntArray(0)
    private var page = 0
    private var fg = 0xFFFFFFFF.toInt()
    private var bg = 0xFF000000.toInt()
    private var spacingMult = 1.4f
    private var insetL = 0; private var insetT = 0; private var insetR = 0; private var insetB = 0
    private var pendingOffset = 0

    val pageCount get() = pageStartLines.size
    val pageIndex get() = page
    val contentWidth get() = (width - insetL - insetR - 2 * margin).coerceAtLeast(100)
    val contentHeight get() = (height - insetT - insetB - 2 * vMargin - footerHeight).coerceAtLeast(100)
    private val margin get() = (22 * density).toInt()
    private val vMargin get() = (14 * density).toInt()
    private val footerHeight get() = (30 * density).toInt()

    /** Character offset at the top of the current page, used to save and restore the reading position. */
    val currentOffset: Int
        get() {
            val l = layout ?: return 0
            return if (pageStartLines.isEmpty()) 0 else l.getLineStart(pageStartLines[page])
        }

    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        if (l == insetL && t == insetT && r == insetR && b == insetB) return
        insetL = l; insetT = t; insetR = r; insetB = b
        relayout(currentOffset)
    }

    fun setStyle(typeface: Typeface, sizeSp: Int, spacing: Float, fgColor: Int, bgColor: Int) {
        val keep = currentOffset
        paint.typeface = typeface
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(), resources.displayMetrics)
        paint.color = fgColor
        paint.linkColor = fgColor
        footerPaint.color = (fgColor and 0x00FFFFFF) or 0xBB000000.toInt()
        footerPaint.typeface = typeface
        fg = fgColor; bg = bgColor; spacingMult = spacing
        relayout(keep)
        invalidate()
    }

    /** Show [content], opening at the page containing [offset]; a negative offset opens on the last page. */
    fun setContent(content: Spanned, offset: Int) {
        text = content
        relayout(offset)
        invalidate()
    }

    private fun relayout(offset: Int) {
        if (width == 0 || height == 0) { pendingOffset = offset; return }
        val len = text.length
        layout = StaticLayout.Builder.obtain(text, 0, len, paint, contentWidth)
            .setLineSpacing(0f, spacingMult).setIncludePad(false).build()
        val l = layout!!
        val starts = ArrayList<Int>()
        val avail = contentHeight
        var line = 0
        while (line < l.lineCount) {
            starts += line
            val top = l.getLineTop(line)
            var end = line
            while (end + 1 < l.lineCount && l.getLineBottom(end + 1) - top <= avail) end++
            line = end + 1
        }
        if (starts.isEmpty()) starts += 0
        pageStartLines = starts.toIntArray()
        page = if (offset < 0) pageStartLines.size - 1 else pageForOffset(offset)
    }

    private fun pageForOffset(offset: Int): Int {
        val l = layout ?: return 0
        var p = 0
        for (i in pageStartLines.indices) {
            if (l.getLineStart(pageStartLines[i]) <= offset) p = i else break
        }
        return p
    }

    fun nextPage(): Boolean { if (page + 1 >= pageCount) return false; page++; invalidate(); return true }
    fun prevPage(): Boolean { if (page <= 0) return false; page--; invalidate(); return true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout(if (pageStartLines.isEmpty()) pendingOffset else currentOffset)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        val l = layout ?: return
        if (pageStartLines.isEmpty() || l.lineCount == 0) return
        val first = pageStartLines[page]
        val last = (if (page + 1 < pageStartLines.size) pageStartLines[page + 1] else l.lineCount) - 1
        val top = l.getLineTop(first)
        val bottom = l.getLineBottom(last)
        canvas.save()
        val originX = (insetL + margin).toFloat()
        val originY = (insetT + vMargin).toFloat()
        canvas.translate(originX, originY - top)
        canvas.clipRect(0f, top.toFloat(), contentWidth.toFloat(), bottom.toFloat())
        l.draw(canvas)
        canvas.restore()

        val y = height - insetB - 8 * density
        val x0 = (insetL + margin).toFloat()
        val x1 = (width - insetR - margin).toFloat()
        val count = "${page + 1} / $pageCount"
        footerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(count, x1, y, footerPaint)
        val room = (x1 - x0) - footerPaint.measureText(count) - 16 * density
        if (footerText.isNotEmpty() && room > 40 * density) {
            footerPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(TextUtils.ellipsize(footerText, footerPaint, room, TextUtils.TruncateAt.END).toString(), x0, y, footerPaint)
        }
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > 70 * density && abs(dx) > abs(dy) * 1.5f) {
                    onZone?.invoke(if (dx < 0) Zone.NEXT else Zone.PREV)
                } else if (abs(dx) < 20 * density && abs(dy) < 20 * density) {
                    val f = e.x / width
                    onZone?.invoke(if (f < 0.3f) Zone.PREV else if (f > 0.7f) Zone.NEXT else Zone.MENU)
                }
                return true
            }
        }
        return true
    }
}
