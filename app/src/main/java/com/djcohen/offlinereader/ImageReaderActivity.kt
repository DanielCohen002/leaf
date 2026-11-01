package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Reader for page-image books (PDF and comic archives) with three layouts:
 * one page at a time, two pages side by side (forces landscape), or continuous vertical scrolling.
 */
class ImageReaderActivity : Activity() {
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var entry: Entry
    private var book: ImageBook? = null
    private lateinit var view: ImagePageView
    private lateinit var scrollView: ScrollImageView
    private lateinit var topBar: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var pageLabel: TextView

    private var layoutMode = 0              // 0 single, 1 two-page, 2 scroll
    private var page = 0                    // current page (first page of the spread in two-page mode)
    private var aspects: FloatArray? = null
    private var spreadStarts = IntArray(0)
    private var overlayVisible = false
    private var opening = false
    private var renderW = 0
    private var renderH = 0

    private val io = Executors.newSingleThreadExecutor()
    // Page rendering runs on its own threads so that preloading never makes the page you asked for wait in line.
    private val loader = Executors.newFixedThreadPool(2)
    private val inFlight = HashMap<Int, FutureTask<Bitmap?>>()   // UI thread only
    @Volatile private var generation = 0                          // bumped whenever cached pages become invalid
    private val ui = Handler(Looper.getMainLooper())
    private val budget = (Runtime.getRuntime().maxMemory() / 8).toInt()
    private val cache = object : LruCache<Int, Bitmap>(budget) {          // single pages / spreads, keyed by first page
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val scrollCache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 3).toInt()) {    // pages for continuous scrolling
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val requested = HashSet<Int>()                                // scroll pages being loaded (UI thread only)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        val found = library.entries().firstOrNull { it.id == intent.getStringExtra(ReaderActivity.EXTRA_ID) }
        if (found == null) { finish(); return }
        entry = found
        prefs = Prefs(this, entry.ext)

        val root = FrameLayout(this)
        view = ImagePageView(this).apply {
            onZone = { z ->
                when (z) {
                    PageView.Zone.MENU -> setOverlay(!overlayVisible)
                    // Tapping the left edge goes "forward" in right-to-left (manga) mode.
                    PageView.Zone.NEXT -> turn(!prefs.rightToLeft)
                    PageView.Zone.PREV -> turn(prefs.rightToLeft)
                }
            }
            onSwipe = { towardsLeft -> turn(towardsLeft != prefs.rightToLeft) }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        scrollView = ScrollImageView(this).apply {
            visibility = View.GONE
            provider = { i -> scrollCache.get(i) ?: run { requestScrollPage(i); null } }
            onZone = { z ->
                when (z) {
                    PageView.Zone.MENU -> setOverlay(!overlayVisible)
                    PageView.Zone.NEXT -> turn(true)
                    PageView.Zone.PREV -> turn(false)
                }
            }
            onPageChanged = { p ->
                if (layoutMode == 2) {
                    page = p
                    updateLabels()
                    // Load the pages just ahead (and one behind) so they are ready before they scroll into view.
                    for (i in (p - 1)..(p + 4)) {
                        if (i in 0 until (book?.pageCount ?: 0) && scrollCache.get(i) == null) requestScrollPage(i)
                    }
                }
            }
        }
        root.addView(scrollView, FrameLayout.LayoutParams(-1, -1))

        topBar = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xE6202020.toInt()); textSize = 16f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6202020.toInt()); visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) pageLabel.text = "Page ${p + 1} / ${book?.pageCount ?: 0}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (layoutMode == 2) scrollView.scrollToPage(progress) else show(progress)
                }
            })
        }
        bottomBar.addView(seek)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pageLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        row.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "Settings"; isAllCaps = false; setOnClickListener { showSettings() } })
        bottomBar.addView(row)
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topBar.setPadding(dp(16) + b.left, dp(12) + b.top, dp(16) + b.right, dp(12))
            bottomBar.setPadding(dp(16) + b.left, dp(8), dp(16) + b.right, dp(8) + b.bottom)
            view.setBottomInset(b.bottom)
            insets
        }

        applyPrefs()
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Pick the render resolution once the view size is known, and again on rotation.
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (w != orr - ol || h != ob - ot)) {
                renderW = (w * 1.5f).toInt(); renderH = (h * 1.5f).toInt()
                resetCaches()
                if (book != null) reload() else openBook()
            }
        }
    }

    // --- opening ---------------------------------------------------------------------------

    private fun openBook() {
        if (opening) return
        opening = true
        io.execute {
            try {
                val b = ImageBooks.open(library.file(entry), entry.ext)
                val start = library.position(entry.id).second.coerceIn(0, b.pageCount - 1)
                ui.post {
                    book = b
                    topBar.text = entry.title
                    seek.max = (b.pageCount - 1).coerceAtLeast(0)
                    page = start
                    applyLayout()
                }
            } catch (t: Throwable) {
                ui.post {
                    Toast.makeText(this, t.message ?: "Couldn't open book", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun applyPrefs() {
        view.setInvert(prefs.invertImages)
        scrollView.setInvert(prefs.invertImages)
        scrollView.placeholderColor = when {
            entry.ext != "pdf" -> 0xFF1B1B1B.toInt()
            prefs.invertImages -> 0xFF141414.toInt()
            else -> 0xFFEFEFEF.toInt()
        }
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Switch to the layout chosen in settings (also sets landscape for two-page mode). */
    private fun applyLayout() {
        layoutMode = prefs.imageLayout.coerceIn(0, 2)
        requestedOrientation = if (layoutMode == 1) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        view.visibility = if (layoutMode == 2) View.GONE else View.VISIBLE
        scrollView.visibility = if (layoutMode == 2) View.VISIBLE else View.GONE
        resetCaches()
        reload()
    }

    /** Show the current page again in the current layout (after a mode or size change). */
    private fun reload() {
        val b = book ?: return
        page = page.coerceIn(0, b.pageCount - 1)
        when (layoutMode) {
            0 -> show(page)
            1 -> withAspects { computeSpreads(); show(page) }
            else -> withAspects {
                scrollView.setPages(it, if (entry.ext == "pdf") 6 else 0, page)
                updateLabels()
            }
        }
    }

    /** Page shapes are needed to lay out spreads and the scroll view; read them once, off the UI thread. */
    private fun withAspects(then: (FloatArray) -> Unit) {
        aspects?.let { then(it); return }
        val b = book ?: return
        Toast.makeText(this, "Preparing pages…", Toast.LENGTH_SHORT).show()
        io.execute {
            val a = b.allAspects()
            ui.post { if (!isDestroyed) { aspects = a; then(a) } }
        }
    }

    private fun setOverlay(show: Boolean) {
        overlayVisible = show
        val v = if (show) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
    }

    // --- two-page spreads ------------------------------------------------------------------

    private fun isWide(i: Int) = (aspects?.getOrNull(i) ?: 0.7f) > 1.15f

    /** First page of every spread: a wide page stands alone, otherwise pages pair up (cover optionally alone). */
    private fun computeSpreads() {
        val n = book?.pageCount ?: return
        val list = ArrayList<Int>()
        var i = 0
        while (i < n) {
            list += i
            i += when {
                isWide(i) -> 1
                i == 0 && prefs.coverAlone -> 1
                i + 1 < n && !isWide(i + 1) -> 2
                else -> 1
            }
        }
        spreadStarts = list.toIntArray()
    }

    private fun spreadIndex(p: Int): Int {
        var idx = 0
        for (k in spreadStarts.indices) if (spreadStarts[k] <= p) idx = k else break
        return idx
    }

    /** Draw the one or two pages of the spread starting at [start] into a single bitmap (right-to-left aware). */
    private fun renderSpread(b: ImageBook, start: Int): Bitmap? {
        val idx = spreadIndex(start)
        val end = if (idx + 1 < spreadStarts.size) spreadStarts[idx + 1] else b.pageCount
        if (end - start < 2) return b.render(start, renderW, renderH)
        val a = aspects ?: return b.render(start, renderW, renderH)
        val second = start + 1
        val ht = minOf(renderH.toFloat(), renderW / (a[start] + a[second]))
        val left = if (prefs.rightToLeft) second else start
        val right = if (prefs.rightToLeft) start else second
        val bl = b.render(left, (ht * a[left]).toInt().coerceAtLeast(1), ht.toInt().coerceAtLeast(1)) ?: return null
        val br = b.render(right, (ht * a[right]).toInt().coerceAtLeast(1), ht.toInt().coerceAtLeast(1)) ?: return bl
        val out = Bitmap.createBitmap(bl.width + br.width, maxOf(bl.height, br.height), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(bl, 0f, (out.height - bl.height) / 2f, null)
        c.drawBitmap(br, bl.width.toFloat(), (out.height - br.height) / 2f, null)
        return out
    }

    // --- paging (single page and two-page) -------------------------------------------------

    private fun updateLabels() {
        val b = book ?: return
        val n = b.pageCount
        val text = if (layoutMode == 1 && spreadStarts.isNotEmpty()) {
            val idx = spreadIndex(page)
            val end = (if (idx + 1 < spreadStarts.size) spreadStarts[idx + 1] else n)
            if (end - page >= 2) "Pages ${page + 1}–$end / $n" else "Page ${page + 1} / $n"
        } else "Page ${page + 1} / $n"
        pageLabel.text = text
        view.label = text.removePrefix("Pages ").removePrefix("Page ")
        seek.progress = page
    }

    /** Forget every rendered page (after a layout, size or direction change). Running renders are ignored when they finish. */
    private fun resetCaches() {
        generation++
        inFlight.clear()
        cache.evictAll()
        scrollCache.evictAll()
    }

    /** Start rendering [key] in the background, or join the render already running for it. UI thread only. */
    private fun pageTask(b: ImageBook, key: Int): FutureTask<Bitmap?> {
        inFlight[key]?.let { return it }
        val mode = layoutMode
        val w = renderW
        val h = renderH
        val gen = generation
        val task = object : FutureTask<Bitmap?>({
            val bmp = if (mode == 1) renderSpread(b, key) else b.render(key, w, h)
            if (bmp != null && gen == generation) cache.put(key, bmp)
            bmp
        }) {
            override fun done() {
                ui.post { if (inFlight[key] === this) inFlight.remove(key) }
            }
        }
        inFlight[key] = task
        loader.execute(task)
        return task
    }

    private fun show(index: Int) {
        val b = book ?: return
        page = if (layoutMode == 1 && spreadStarts.isNotEmpty()) spreadStarts[spreadIndex(index)] else index.coerceIn(0, b.pageCount - 1)
        val shown = page
        updateLabels()
        val cached = cache.get(shown)
        if (cached != null) {
            view.setBitmap(cached)
            prefetch(shown)
            return
        }
        val task = pageTask(b, shown)
        io.execute {
            if (page != shown) return@execute        // already moved on; the render still lands in the cache
            val bmp = try { task.get() } catch (_: Throwable) { null }
            ui.post {
                if (page == shown && !isDestroyed) {
                    if (bmp != null) view.setBitmap(bmp)
                    else Toast.makeText(this, "Couldn't render this page", Toast.LENGTH_SHORT).show()
                    prefetch(shown)
                }
            }
        }
    }

    /** Pages worth having ready: two ahead and one behind. */
    private fun neighbors(from: Int): List<Int> {
        val b = book ?: return emptyList()
        return if (layoutMode == 1 && spreadStarts.isNotEmpty()) {
            val i = spreadIndex(from)
            listOfNotNull(spreadStarts.getOrNull(i + 1), spreadStarts.getOrNull(i + 2), spreadStarts.getOrNull(i - 1))
        } else listOf(from + 1, from + 2, from - 1).filter { it in 0 until b.pageCount }
    }

    private fun prefetch(from: Int) {
        val b = book ?: return
        for (i in neighbors(from)) {
            if (cache.get(i) == null) pageTask(b, i)
        }
    }

    private fun turn(forward: Boolean) {
        val b = book ?: return
        if (overlayVisible) setOverlay(false)
        when (layoutMode) {
            2 -> {
                if (forward && scrollView.atEnd) Toast.makeText(this, "End of book", Toast.LENGTH_SHORT).show()
                else scrollView.scrollByScreen(forward)
            }
            1 -> {
                val i = spreadIndex(page) + if (forward) 1 else -1
                if (i !in spreadStarts.indices) {
                    Toast.makeText(this, if (forward) "End of book" else "First page", Toast.LENGTH_SHORT).show()
                    return
                }
                show(spreadStarts[i])
            }
            else -> {
                val next = page + if (forward) 1 else -1
                if (next !in 0 until b.pageCount) {
                    Toast.makeText(this, if (forward) "End of book" else "First page", Toast.LENGTH_SHORT).show()
                    return
                }
                show(next)
            }
        }
    }

    // --- continuous scroll ------------------------------------------------------------------

    /** Load page [i] for the scroll view in the background; pages the user has already scrolled past are skipped. */
    private fun requestScrollPage(i: Int) {
        val b = book ?: return
        if (!requested.add(i)) return
        val w = (scrollView.width * 1.5f).toInt().coerceIn(600, 1800)
        io.execute {
            val wanted = try { i in scrollView.visibleRange(5) } catch (_: Throwable) { true }
            val bmp = if (wanted && !isDestroyed) b.render(i, w, w * 4) else null
            ui.post {
                requested.remove(i)
                if (bmp != null) { scrollCache.put(i, bmp); scrollView.invalidate() }
            }
        }
    }

    // --- settings --------------------------------------------------------------------------

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, dp(4)) }.also { box.addView(it) }
        fun check(text: String, value: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text; isChecked = value; setOnCheckedChangeListener { _, c -> set(c) }
        }.also { box.addView(it) }

        label("Layout")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("One page at a time", "Two pages side by side (landscape)", "Continuous scroll").forEachIndexed { i, t ->
            group.addView(RadioButton(this).apply { text = t; id = 2000 + i })
        }
        group.check(2000 + prefs.imageLayout.coerceIn(0, 2))
        group.setOnCheckedChangeListener { _, checked ->
            val mode = checked - 2000
            if (mode != prefs.imageLayout) { prefs.imageLayout = mode; applyLayout() }
        }
        box.addView(group)

        check("Show the first page (cover) on its own in two-page mode", prefs.coverAlone) {
            prefs.coverAlone = it
            if (layoutMode == 1) { computeSpreads(); resetCaches(); show(page) }
        }
        check("Right-to-left reading (manga)", prefs.rightToLeft) {
            prefs.rightToLeft = it
            if (layoutMode == 1) { resetCaches(); show(page) }
        }
        check("Invert colors", prefs.invertImages) { prefs.invertImages = it; applyPrefs() }
        check("Volume keys turn pages", prefs.volumeKeys) { prefs.volumeKeys = it }
        check("Swap volume keys (volume up = next)", prefs.swapVolume) { prefs.swapVolume = it }
        check("Keep screen on", prefs.keepScreenOn) { prefs.keepScreenOn = it; applyPrefs() }
        box.addView(TextView(this).apply {
            text = "Double-tap to zoom, pinch to zoom further, drag to pan."
            alpha = 0.6f; textSize = 13f; setPadding(0, dp(12), 0, 0)
        })
        AlertDialog.Builder(this).setTitle("Reading settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).showLeaf()
    }

    // --- hardware keys ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) turn((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != prefs.swapVolume)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    // --- lifecycle -------------------------------------------------------------------------

    override fun onPause() {
        if (book != null) library.savePosition(entry.id, 0, page)
        super.onPause()
    }

    override fun onDestroy() {
        io.execute { book?.close() }
        io.shutdown()
        loader.shutdownNow()
        super.onDestroy()
    }
}
