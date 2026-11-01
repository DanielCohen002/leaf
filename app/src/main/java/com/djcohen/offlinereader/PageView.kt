package com.djcohen.offlinereader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import java.text.BreakIterator
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
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * density }

    private var text: CharSequence = ""
    private var layout: StaticLayout? = null
    private var pageStartLines = IntArray(0)
    private var page = 0
    private var fg = 0xFFFFFFFF.toInt()
    private var bg = 0xFF000000.toInt()
    private var spacingMult = 1.4f
    private var insetL = 0; private var insetT = 0; private var insetR = 0; private var insetB = 0
    private var pendingOffset = 0

    // --- continuous scroll mode (instead of turning pages) -------------------------------------
    private var scrollMode = false
    private var scrollPx = 0
    private val scroller = android.widget.OverScroller(context)
    private var scrolling = false
    private var lastY = 0f
    private var velocity: android.view.VelocityTracker? = null

    private fun maxScroll() = ((layout?.height ?: 0) - contentHeight).coerceAtLeast(0)

    /** Layout y that sits at the top of the text area. */
    private fun topY(): Int {
        val l = layout ?: return 0
        return if (scrollMode) scrollPx else l.getLineTop(pageStartLines[page])
    }

    private fun firstLine(): Int {
        val l = layout!!
        return if (scrollMode) l.getLineForVertical(scrollPx) else pageStartLines[page]
    }

    private fun lastLine(): Int {
        val l = layout!!
        return if (scrollMode) l.getLineForVertical(scrollPx + contentHeight).coerceAtMost(l.lineCount - 1)
        else (if (page + 1 < pageStartLines.size) pageStartLines[page + 1] else l.lineCount) - 1
    }

    /** Switch between paged and continuous-scroll reading, keeping the current position. */
    fun setScrollMode(enabled: Boolean) {
        if (enabled == scrollMode) return
        val keep = currentOffset
        scroller.forceFinished(true)
        scrollMode = enabled
        relayout(keep)
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currY.coerceIn(0, maxScroll())
            postInvalidateOnAnimation()
            invalidate()
        }
    }

    val pageCount get() = pageStartLines.size
    val pageIndex get() = page
    val contentWidth get() = (width - insetL - insetR - 2 * margin).coerceAtLeast(100)
    val contentHeight get() = (height - insetT - insetB - 2 * vMargin - footerHeight).coerceAtLeast(100)
    private val margin get() = (22 * density).toInt()
    private val vMargin get() = (14 * density).toInt()
    private val footerHeight get() = (footerPaint.textSize + 16 * density).toInt()

    /** Character offset at the top of the current page, used to save and restore the reading position. */
    val currentOffset: Int
        get() {
            val l = layout ?: return 0
            if (scrollMode) return l.getLineStart(l.getLineForVertical(scrollPx))
            return if (pageStartLines.isEmpty()) 0 else l.getLineStart(pageStartLines[page])
        }

    // --- selection -------------------------------------------------------------------------

    /** Called with the selected text, or null when the selection is cleared. */
    var onSelection: ((String?) -> Unit)? = null

    private var selStart = -1
    private var selEnd = -1
    private val selPath = Path()
    private val selPaint = Paint().apply { color = 0x66FFB300; style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFA000.toInt() }
    private val handleRadius get() = 9 * density
    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private var dragging = 0            // 0 none, 1 start handle, 2 end handle
    private var dragMinEnd = -1
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    val hasSelection get() = selStart >= 0

    /** Vertical screen position of the selection start, used to keep the action bar out of the way. */
    fun selectionY(): Float = if (selStart >= 0 && layout != null) handlePoint(selStart, false)[1] else 0f

    fun selectedText(): String? =
        if (selStart in 0 until selEnd && selEnd <= text.length) text.subSequence(selStart, selEnd).toString().replace('￼', ' ').trim().ifEmpty { null } else null

    fun clearSelection() {
        if (selStart < 0) return
        selStart = -1; selEnd = -1; dragging = 0
        onSelection?.invoke(null)
        invalidate()
    }

    private fun pageRange(): IntRange? {
        val l = layout ?: return null
        if (pageStartLines.isEmpty() || l.lineCount == 0) return null
        return l.getLineStart(firstLine())..l.getLineEnd(lastLine())
    }

    /** Character offset under screen point (x, y), restricted to the current page. */
    private fun offsetAt(x: Float, y: Float): Int {
        val l = layout ?: return 0
        val first = firstLine()
        val last = lastLine()
        val ly = y - (insetT + vMargin) + topY()
        val line = l.getLineForVertical(ly.toInt()).coerceIn(first, last)
        return l.getOffsetForHorizontal(line, x - (insetL + margin)).coerceIn(pageRange()!!)
    }

    private fun selectWordAt(x: Float, y: Float): Boolean {
        val range = pageRange() ?: return false
        if (text.isEmpty()) return false
        val o = offsetAt(x, y).coerceIn(range.first, (range.last - 1).coerceAtLeast(range.first))
        val bi = BreakIterator.getWordInstance()
        val str = text.toString()
        bi.setText(str)
        var start = if (bi.isBoundary(o)) o else bi.preceding(o)
        var end = bi.following(start).let { if (it == BreakIterator.DONE) str.length else it }
        // Landed on a space or punctuation: snap to the word just before it, else the one after.
        if (str.substring(start, end.coerceAtMost(str.length)).none { it.isLetterOrDigit() }) {
            val ps = bi.preceding(start)
            if (ps != BreakIterator.DONE && str.substring(ps, start).any { it.isLetterOrDigit() }) {
                end = start; start = ps
            } else if (end < str.length) {
                val ne = bi.following(end)
                if (ne != BreakIterator.DONE) { start = end; end = ne }
            }
        }
        val word = str.substring(start, end.coerceAtMost(str.length))
        if (word.none { it.isLetterOrDigit() }) return false
        selStart = start.coerceAtLeast(range.first); selEnd = end.coerceAtMost(range.last)
        dragMinEnd = selEnd
        return true
    }

    private fun handlePoint(offset: Int, isEnd: Boolean): FloatArray {
        val l = layout!!
        var line = l.getLineForOffset(offset)
        var x = l.getPrimaryHorizontal(offset)
        if (isEnd && line > l.getLineForOffset(selStart) && l.getLineStart(line) == offset) {
            line -= 1; x = l.getLineRight(line)
        }
        val y = l.getLineBaseline(line) + paint.descent().toInt() - topY() + insetT + vMargin
        return floatArrayOf(x + insetL + margin, y.toFloat())
    }

    private val longPress = Runnable {
        if (selectWordAt(downX, downY)) {
            longPressFired = true
            dragging = 2
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onSelection?.invoke(selectedText())
            invalidate()
        }
    }

    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        if (l == insetL && t == insetT && r == insetR && b == insetB) return
        insetL = l; insetT = t; insetR = r; insetB = b
        relayout(currentOffset)
    }

    fun setStyle(typeface: Typeface, sizeSp: Int, spacing: Float, fgColor: Int, bgColor: Int) {
        clearSelection()
        val keep = currentOffset
        paint.typeface = typeface
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(), resources.displayMetrics)
        paint.color = fgColor
        paint.linkColor = fgColor
        footerPaint.color = (fgColor and 0x00FFFFFF) or 0xBB000000.toInt()
        footerPaint.typeface = typeface
        footerPaint.textSize = maxOf(paint.textSize * 0.85f, 16 * density)
        fg = fgColor; bg = bgColor; spacingMult = spacing
        relayout(keep)
        invalidate()
    }

    /** Show [content], opening at the page containing [offset]; a negative offset opens on the last page. */
    fun setContent(content: Spanned, offset: Int) {
        clearSelection()
        text = content
        relayout(offset)
        invalidate()
    }

    private fun relayout(offset: Int) {
        clearSelection()
        if (width == 0 || height == 0) { pendingOffset = offset; return }
        val len = text.length
        layout = StaticLayout.Builder.obtain(text, 0, len, paint, contentWidth)
            .setLineSpacing(0f, spacingMult).setIncludePad(false).build()
        val l = layout!!
        if (scrollMode) {
            pageStartLines = intArrayOf(0)
            page = 0
            scrollPx = if (offset < 0) maxScroll()
            else l.getLineTop(l.getLineForOffset(offset.coerceAtMost(len))).coerceIn(0, maxScroll())
            return
        }
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

    /** In scroll mode this scrolls by most of a screen; returns false when already at the end. */
    fun nextPage(): Boolean {
        clearSelection()
        if (scrollMode) return scrollBy(+1)
        if (page + 1 >= pageCount) return false
        page++; invalidate(); return true
    }

    fun prevPage(): Boolean {
        clearSelection()
        if (scrollMode) return scrollBy(-1)
        if (page <= 0) return false
        page--; invalidate(); return true
    }

    private fun scrollBy(direction: Int): Boolean {
        val max = maxScroll()
        if (direction > 0 && scrollPx >= max - 2) return false
        if (direction < 0 && scrollPx <= 0) return false
        val target = (scrollPx + direction * (contentHeight * 0.9f).toInt()).coerceIn(0, max)
        scroller.forceFinished(true)
        scroller.startScroll(0, scrollPx, 0, target - scrollPx, 250)
        postInvalidateOnAnimation()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout(if (pageStartLines.isEmpty()) pendingOffset else currentOffset)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        val l = layout ?: return
        if (pageStartLines.isEmpty() || l.lineCount == 0) return
        val top = topY()
        val bottom = if (scrollMode) top + contentHeight else l.getLineBottom(lastLine())
        canvas.save()
        val originX = (insetL + margin).toFloat()
        val originY = (insetT + vMargin).toFloat()
        canvas.translate(originX, originY - top)
        canvas.clipRect(0f, top.toFloat(), contentWidth.toFloat(), bottom.toFloat())
        if (selStart in 0 until selEnd) {
            l.getSelectionPath(selStart, selEnd, selPath)
            canvas.drawPath(selPath, selPaint)
        }
        l.draw(canvas)
        canvas.restore()
        if (selStart in 0 until selEnd) {
            val a = handlePoint(selStart, false)
            val b = handlePoint(selEnd, true)
            canvas.drawCircle(a[0], a[1] + handleRadius, handleRadius, handlePaint)
            canvas.drawCircle(b[0], b[1] + handleRadius, handleRadius, handlePaint)
        }

        val y = height - insetB - 8 * density
        val x0 = (insetL + margin).toFloat()
        val x1 = (width - insetR - margin).toFloat()
        val count = if (scrollMode) "${(scrollPx * 100L / maxScroll().coerceAtLeast(1)).toInt()}%" else "${page + 1} / $pageCount"
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

    private fun nearHandle(x: Float, y: Float): Int {
        if (selStart !in 0 until selEnd) return 0
        val reach = 32 * density
        val a = handlePoint(selStart, false)
        val b = handlePoint(selEnd, true)
        val da = Math.hypot((x - a[0]).toDouble(), (y - a[1] - handleRadius).toDouble())
        val db = Math.hypot((x - b[0]).toDouble(), (y - b[1] - handleRadius).toDouble())
        return if (db <= reach && db <= da) 2 else if (da <= reach) 1 else 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; longPressFired = false
                scrolling = false
                if (scrollMode) {
                    scroller.forceFinished(true)
                    velocity?.recycle()
                    velocity = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                }
                dragging = nearHandle(e.x, e.y)
                dragMinEnd = -1
                if (dragging == 0) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != 0) {
                    // Aim a little above the finger so the handle stays visible.
                    val o = offsetAt(e.x, e.y - handleRadius * 2)
                    if (dragging == 2) selEnd = maxOf(o, selStart + 1, dragMinEnd)
                    else selStart = minOf(o, selEnd - 1)
                    onSelection?.invoke(selectedText())
                    invalidate()
                } else if (scrollMode && !longPressFired) {
                    velocity?.addMovement(e)
                    if (!scrolling && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                        scrolling = true
                        handler.removeCallbacks(longPress)
                        clearSelection()
                        lastY = e.y
                    }
                    if (scrolling) {
                        scrollPx = (scrollPx + (lastY - e.y).toInt()).coerceIn(0, maxScroll())
                        lastY = e.y
                        invalidate()
                    }
                } else if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                    handler.removeCallbacks(longPress)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress); dragging = 0; scrolling = false
                velocity?.recycle(); velocity = null
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val wasDragging = dragging != 0
                dragging = 0
                if (scrolling) {
                    velocity?.let {
                        it.addMovement(e)
                        it.computeCurrentVelocity(1000)
                        scroller.fling(0, scrollPx, 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll())
                        it.recycle()
                    }
                    velocity = null
                    scrolling = false
                    postInvalidateOnAnimation()
                    return true
                }
                velocity?.recycle(); velocity = null
                if (longPressFired || wasDragging) return true
                if (hasSelection) { clearSelection(); return true }   // a tap just dismisses the selection
                val dx = e.x - downX
                val dy = e.y - downY
                if (!scrollMode && abs(dx) > 70 * density && abs(dx) > abs(dy) * 1.5f) {
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
