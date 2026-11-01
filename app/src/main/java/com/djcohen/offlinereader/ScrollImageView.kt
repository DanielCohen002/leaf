package com.djcohen.offlinereader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller

/**
 * Continuous vertical scrolling through page images (webtoon-style). Pages are laid out at the view's width from
 * their aspect ratios, so the scroll height is known up front; bitmaps are fetched lazily through [provider].
 */
class ScrollImageView(context: Context) : View(context) {
    /** Returns the bitmap for a page if it is ready, or null (and the owner should start loading it). */
    var provider: ((Int) -> Bitmap?)? = null
    /** Tap zones: PREV = top third (scroll up a screen), NEXT = bottom third, MENU = middle. */
    var onZone: ((PageView.Zone) -> Unit)? = null
    /** Called when the page at the top of the screen changes. */
    var onPageChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint().apply { color = 0xFF1B1B1B.toInt() }
    private val scroller = OverScroller(context)

    private var aspects = FloatArray(0)
    private var tops = FloatArray(1)          // tops[i] = y of page i in content space; tops[n] = total height
    private var gap = 0f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastReported = -1
    private var pendingStart = -1

    /** Color shown for a page that hasn't loaded yet; should resemble the pages so loading is not jarring. */
    var placeholderColor: Int
        get() = placeholder.color
        set(v) { placeholder.color = v; invalidate() }

    var pageBackground = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }

    val pageCount get() = aspects.size
    private val contentW get() = width * zoom
    private val totalH get() = tops[aspects.size]
    private val maxY get() = (totalH - height).coerceAtLeast(0f)
    private val maxX get() = (contentW - width).coerceAtLeast(0f)

    fun setInvert(invert: Boolean) {
        paint.colorFilter = if (invert) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))) else null
        invalidate()
    }

    /** [gapDp] is the space between pages (0 for seamless comic strips). */
    fun setPages(pageAspects: FloatArray, gapDp: Int, startPage: Int) {
        aspects = pageAspects
        gap = gapDp * density
        panX = 0f
        panY = 0f
        scroller.forceFinished(true)
        if (width == 0) {            // not laid out yet: finish in onSizeChanged
            pendingStart = startPage
            tops = FloatArray(pageAspects.size + 1)   // keep sizes consistent until the real layout
            return
        }
        layoutPages()
        scrollToPage(startPage)
    }

    private fun layoutPages() {
        val n = aspects.size
        tops = FloatArray(n + 1)
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            y += contentW / aspects[i].coerceIn(0.05f, 20f) + gap
        }
        tops[n] = y
    }

    fun scrollToPage(i: Int) {
        if (aspects.isEmpty()) return
        scroller.forceFinished(true)
        panY = tops[i.coerceIn(0, aspects.size - 1)].coerceIn(0f, maxY)
        afterScroll()
    }

    /** Scroll by roughly a screen (used by tap zones and the volume keys). */
    fun scrollByScreen(forward: Boolean) {
        val dy = (height * 0.85f) * if (forward) 1 else -1
        val target = (panY + dy).coerceIn(0f, maxY)
        scroller.forceFinished(true)
        scroller.startScroll(panX.toInt(), panY.toInt(), 0, (target - panY).toInt(), 260)
        postInvalidateOnAnimation()
    }

    val atEnd get() = panY >= maxY - 1f

    /** Index of the page occupying the line [fraction] of the way down the screen. */
    fun pageAt(fraction: Float = 0f): Int {
        if (aspects.isEmpty()) return 0
        val y = panY + height * fraction
        var lo = 0
        var hi = aspects.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Pages currently on screen plus a margin, so the owner can skip loading pages the user already scrolled past. */
    fun visibleRange(margin: Int = 2): IntRange {
        if (aspects.isEmpty()) return IntRange.EMPTY
        val first = pageAt(0f)
        val last = pageAt(1f)
        return (first - margin).coerceAtLeast(0)..(last + margin).coerceAtMost(aspects.size - 1)
    }

    private fun afterScroll() {
        panY = panY.coerceIn(0f, maxY)
        panX = panX.coerceIn(0f, maxX)
        val p = pageAt(0.3f)
        if (p != lastReported) { lastReported = p; onPageChanged?.invoke(p) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (aspects.isEmpty()) return
        if (pendingStart >= 0) {
            val start = pendingStart
            pendingStart = -1
            layoutPages()
            scrollToPage(start)
            return
        }
        val page = pageAt(0f)
        val frac = if (aspects.isNotEmpty()) (panY - tops[page]) / (tops[page + 1] - tops[page]).coerceAtLeast(1f) else 0f
        layoutPages()
        panY = tops[page] + frac * (tops[page + 1] - tops[page])
        afterScroll()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            afterScroll()
            postInvalidateOnAnimation()
        }
    }

    /** Re-layout at a new zoom, keeping the content under ([fx], [fy]) in place. */
    private fun setZoom(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(1f, 4f)
        if (z == zoom) return
        val ratio = z / zoom
        val cx = panX + fx
        val cy = panY + fy
        zoom = z
        layoutPages()
        panX = cx * ratio - fx
        panY = cy * ratio - fy
        afterScroll()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setZoom(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX += dx
            panY += dy
            afterScroll()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(panX.toInt(), panY.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX.toInt(), 0, maxY.toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) setZoom(1f, e.x, e.y) else setZoom(2.2f, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val f = e.y / height
            onZone?.invoke(if (f < 0.3f) PageView.Zone.PREV else if (f > 0.7f) PageView.Zone.NEXT else PageView.Zone.MENU)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    private val dest = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pageBackground)
        val n = aspects.size
        if (n == 0) return
        val prov = provider
        var i = pageAt(0f)
        while (i < n) {
            val top = tops[i] - panY
            if (top > height) break
            val h = tops[i + 1] - tops[i] - gap
            dest.set(-panX, top, -panX + contentW, top + h)
            val bmp = prov?.invoke(i)
            if (bmp != null) canvas.drawBitmap(bmp, null, dest, paint) else canvas.drawRect(dest, placeholder)
            i++
        }
    }
}
