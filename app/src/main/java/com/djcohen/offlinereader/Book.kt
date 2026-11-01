package com.djcohen.offlinereader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Html
import android.text.Layout
import android.text.style.AlignmentSpan
import android.text.style.ImageSpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** A readable book: an ordered list of chapters, each loadable as styled text. */
interface Book {
    val title: String
    val chapterTitles: List<String>
    /** Build the text for chapter [index]. Images are scaled to fit [maxW] x [maxH] pixels. */
    fun load(index: Int, maxW: Int, maxH: Int): Spanned
    /** A cover picture for the library grid, or null if the book has none (the library draws a placeholder). */
    fun cover(maxW: Int, maxH: Int): Bitmap? = null
    /** Where an internal link (the href of a tapped link) leads, or null if it can't be worked out. */
    fun resolveLink(chapter: Int, href: String): LinkTarget? = null
    /** Character offset of the named spot [id] in [chapter]; known once that chapter has been loaded. */
    fun anchorOffset(chapter: Int, id: String): Int? = null
    fun close()
}

/** The destination of a link: a chapter plus (optionally) a named spot in it. [external] links go to the web. */
class LinkTarget(val chapter: Int, val anchor: String?, val external: Boolean = false)

/**
 * Link bookkeeping for books whose chapters are HTML strings held in memory (FB2, Markdown, MOBI): which chapter holds each
 * id, and (once a chapter has been loaded) at which character offset. href values are "#id" after the book has rewritten them.
 */
internal class LinkIndex {
    private val chapterOfId = HashMap<String, Int>()
    private val offsets = java.util.concurrent.ConcurrentHashMap<Int, Map<String, Int>>()
    private val idRegex = Regex("(?i)\\s(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']")

    /** Remember every id found in the HTML of [chapter]. */
    fun scan(chapter: Int, html: String) {
        for (m in idRegex.findAll(html)) chapterOfId.putIfAbsent(m.groupValues[1], chapter)
    }

    /** Pass to [HtmlText.page] when loading [chapter]; the offsets land here. */
    fun collector(chapter: Int): MutableMap<String, Int> = HashMap<String, Int>().also { offsets[chapter] = it }

    fun offset(chapter: Int, id: String): Int? = offsets[chapter]?.get(id)

    fun resolve(chapter: Int, href: String): LinkTarget? {
        val h = href.trim()
        if (h.isEmpty()) return null
        if (!h.startsWith("#")) {
            return if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(h) && !h.startsWith("kindle:")) LinkTarget(-1, null, external = true) else null
        }
        val id = Uri.decode(h.substring(1))
        val at = chapterOfId[id] ?: chapterOfId[h.substring(1)] ?: return null
        return LinkTarget(at, id)
    }
}

object BookLoader {
    val textExtensions = setOf("epub", "txt", "text", "html", "htm", "xhtml", "md", "markdown", "fb2", "mobi", "azw", "azw3", "prc")
    val supportedExtensions: Set<String> get() = textExtensions + ImageBooks.extensions

    fun open(file: File, ext: String): Book = when (ext.lowercase()) {
        "epub" -> EpubBook(file)
        "txt", "text" -> TxtBook(file)
        "html", "htm", "xhtml" -> HtmlBook(file)
        "md", "markdown" -> MarkdownBook(file)
        "fb2" -> Fb2Book(file)
        "mobi", "azw", "azw3", "prc" -> MobiBook(file)
        else -> throw IllegalArgumentException("Unsupported format: .$ext")
    }
}

// ---------------------------------------------------------------------------------------------
// Shared HTML -> Spanned conversion
// ---------------------------------------------------------------------------------------------

internal object HtmlText {
    private val head = Regex("(?is)<head\\b.*?</head>")
    private val style = Regex("(?is)<style\\b.*?</style>")
    private val script = Regex("(?is)<script\\b.*?</script>")
    private val svgImage = Regex("(?is)<image\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>")
    private val prolog = Regex("(?is)<\\?xml.*?\\?>|<!DOCTYPE[^>]*>")

    // Start tags, and the id / name attributes that make a spot in the text linkable.
    private val startTag = Regex("<([A-Za-z][A-Za-z0-9:]*)(\\s[^>]*)?>")
    private val idAttr = Regex("\\s(id|name)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val noMarkerTags = setOf("table", "tbody", "thead", "tfoot", "tr", "ul", "ol", "dl", "select", "html")

    /**
     * The HTML converter discards id attributes, so each one is replaced by an empty marker element (<lfid7></lfid7>)
     * that the tag handler turns into a character offset. [ids] receives the ids in marker order.
     */
    private fun markAnchors(html: String, ids: MutableList<String>): String =
        startTag.replace(html) { m ->
            val tag = m.groupValues[1].lowercase()
            if (tag in noMarkerTags) return@replace m.value
            var id: String? = null
            for (a in idAttr.findAll(m.groupValues[2])) {
                val isName = a.groupValues[1].equals("name", ignoreCase = true)
                if (isName && tag != "a") continue          // name= only means an anchor on <a>
                id = a.groupValues[2].ifEmpty { a.groupValues[3] }
                if (!isName) break
            }
            if (id.isNullOrEmpty()) m.value else {
                ids += id
                m.value + "<lfid${ids.size - 1}></lfid${ids.size - 1}>"
            }
        }

    /** [anchors], if given, receives the character offset of every id in the produced text. */
    fun toSpanned(
        html: String, images: ((String, Int, Int) -> Bitmap?)?, maxW: Int, maxH: Int,
        anchors: MutableMap<String, Int>? = null,
    ): Spanned {
        var cleaned = html.replace(prolog, "").replace(svgImage, "<img src=\"$1\"/>").replace(head, "").replace(style, "").replace(script, "")
        val ids = ArrayList<String>()
        if (anchors != null) cleaned = markAnchors(cleaned, ids)
        val getter = Html.ImageGetter { src ->
            val bmp = images?.invoke(src, maxW, maxH)
            if (bmp == null) null else BitmapDrawable(null, bmp).apply { setBounds(0, 0, bmp.width, bmp.height) }
        }
        val tagHandler = if (anchors == null) null else Html.TagHandler { opening, tag, output, _ ->
            if (opening && tag.startsWith("lfid")) {
                val id = tag.substring(4).toIntOrNull()?.let { ids.getOrNull(it) }
                if (id != null && id !in anchors) anchors[id] = output.length
            }
        }
        val spanned = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT, getter, tagHandler)
        // tidy() trims leading whitespace, which moves every offset; shift the anchors to match.
        val lead = spanned.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) spanned.length else it }
        val result = tidy(SpannableStringBuilder(spanned))
        if (anchors != null) {
            for (k in anchors.keys.toList()) anchors[k] = ((anchors[k] ?: 0) - lead).coerceIn(0, result.length)
        }
        return result
    }

    /**
     * Render one HTML page. A page that is just a single picture (a cover) is enlarged to fill the page and centered;
     * [loader] fetches an image by its source string, scaled to the box, enlarging when the last argument is true.
     */
    fun page(
        html: String, maxW: Int, maxH: Int,
        anchors: MutableMap<String, Int>? = null,
        loader: (String, Int, Int, Boolean) -> Bitmap?,
    ): Spanned {
        val imageOnly = Regex("(?i)<(img|image)\\b").findAll(html).count() == 1 &&
            html.replace(Regex("(?is)<head\\b.*?</head>|<style\\b.*?</style>|<[^>]*>"), "").trim().length < 80
        val h = if (imageOnly) (maxH * 1.08f).toInt() else maxH
        val text = toSpanned(html, { src, w, hh -> loader(src, w, hh, imageOnly) }, maxW, h, anchors)
        if (!imageOnly) return text
        return SpannableStringBuilder(text).also {
            it.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, it.length, Spanned.SPAN_PARAGRAPH)
        }
    }

    /** Strip publisher colors/sizes (they break dark mode and font choice), trim, indent paragraphs. */
    fun tidy(sb: SpannableStringBuilder): Spanned {
        for (cls in arrayOf(ForegroundColorSpan::class.java, BackgroundColorSpan::class.java, AbsoluteSizeSpan::class.java, android.text.style.TypefaceSpan::class.java)) {
            for (s in sb.getSpans(0, sb.length, cls)) {
                // Publisher fonts override the reader's font choice; only code (monospace) keeps its look.
                if (s is android.text.style.TypefaceSpan && s.family == "monospace") continue
                sb.removeSpan(s)
            }
        }
        // Normalise object-replacement / nbsp noise and trim.
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace()) end--
        sb.delete(end, sb.length)
        var start = 0
        while (start < sb.length && sb[start].isWhitespace()) start++
        sb.delete(0, start)
        return sb
    }

    fun indentParagraphs(sb: SpannableStringBuilder) {
        val indent = 28
        var i = 0
        while (i < sb.length) {
            var j = sb.indexOf('\n', i)
            if (j < 0) j = sb.length
            if (j - i > 60) sb.setSpan(LeadingMarginSpan.Standard(indent, 0), i, minOf(j + 1, sb.length), Spanned.SPAN_PARAGRAPH)
            i = j + 1
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Plain text
// ---------------------------------------------------------------------------------------------

class TxtBook(file: File) : Book {
    override val title: String = file.nameWithoutExtension.replace('_', ' ').trim()
    private val chapters = mutableListOf<Pair<String, String>>()
    override val chapterTitles: List<String>

    init {
        val text = decode(file.readBytes()).replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = reflow(text)
        splitChapters(paragraphs)
        chapterTitles = chapters.map { it.first }
    }

    private fun decode(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            String(b, Charsets.ISO_8859_1)
        }
    }

    /** Hard-wrapped text (Gutenberg style) gets unwrapped; one-line-per-paragraph text is kept. */
    private fun reflow(text: String): List<String> {
        val blocks = text.split(Regex("\n[ \t]*\n+"))
        val lines = text.count { it == '\n' } + 1
        return if (blocks.size * 6 < lines) {
            blocks.map { it.replace(Regex("[ \t]*\n[ \t]*"), " ").trim() }.filter { it.isNotEmpty() }
        } else {
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private val heading = Regex("^(chapter|part|book|prologue|epilogue|section)\\b.{0,70}$", RegexOption.IGNORE_CASE)

    private fun splitChapters(paragraphs: List<String>) {
        val marks = paragraphs.indices.filter { heading.matches(paragraphs[it]) && paragraphs[it].length < 80 }
        if (marks.size >= 2) {
            val first = marks.first()
            if (first > 0) chapters += "Front matter" to paragraphs.subList(0, first).joinToString("\n")
            for ((n, m) in marks.withIndex()) {
                val endIdx = if (n + 1 < marks.size) marks[n + 1] else paragraphs.size
                chapters += paragraphs[m] to paragraphs.subList(m, endIdx).joinToString("\n")
            }
        } else {
            val sb = StringBuilder()
            var part = 1
            for (p in paragraphs) {
                sb.append(p).append('\n')
                if (sb.length > 30_000) {
                    chapters += "Part $part" to sb.toString(); part++; sb.clear()
                }
            }
            if (sb.isNotEmpty() || chapters.isEmpty()) chapters += (if (part == 1) title else "Part $part") to sb.toString()
        }
    }

    override fun load(index: Int, maxW: Int, maxH: Int): Spanned {
        return SpannableStringBuilder(chapters[index].second.trimEnd())
    }

    override fun close() {}
}

// ---------------------------------------------------------------------------------------------
// Single HTML file
// ---------------------------------------------------------------------------------------------

class HtmlBook(private val file: File) : Book {
    override val title: String = file.nameWithoutExtension.replace('_', ' ').trim()
    override val chapterTitles: List<String> = listOf(title)

    override fun load(index: Int, maxW: Int, maxH: Int): Spanned =
        HtmlText.toSpanned(String(file.readBytes(), Charsets.UTF_8), null, maxW, maxH)

    override fun close() {}
}

// ---------------------------------------------------------------------------------------------
// EPUB
// ---------------------------------------------------------------------------------------------

class EpubBook(file: File) : Book {
    private val zip = ZipFile(file)
    private val spine = mutableListOf<String>()      // zip entry paths in reading order
    private val titles = mutableListOf<String>()
    override var title: String = file.nameWithoutExtension
        private set
    override val chapterTitles: List<String> get() = titles
    private var coverPath: String? = null

    init {
        val container = readText("META-INF/container.xml") ?: throw IllegalArgumentException("Not an EPUB")
        val opfPath = attrOf(container, "rootfile", "full-path") ?: throw IllegalArgumentException("EPUB has no package file")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = readText(opfPath) ?: throw IllegalArgumentException("EPUB package missing")
        parseOpf(opf, opfDir)
        if (spine.isEmpty()) throw IllegalArgumentException("EPUB has no readable chapters")
        // Cover only in the manifest (not shown by the first page)? Add it as its own first page.
        coverPath?.let { cover ->
            val name = cover.substringAfterLast('/')
            if (zip.getEntry(cover) != null && readText(spine[0])?.contains(name) != true) {
                spine.add(0, cover); titles.add(0, "Cover")
            }
        }
    }

    private fun parseOpf(opf: String, opfDir: String) {
        val manifest = HashMap<String, Triple<String, String, String>>() // id -> (path, mediaType, properties)
        val order = mutableListOf<String>()
        var tocId: String? = null
        val p = Xml.newPullParser()
        p.setInput(opf.reader())
        var inTitle = false
        var gotTitle = false
        var coverMetaId: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name.substringAfter(':')) {
                    "title" -> if (!gotTitle) inTitle = true
                    "item" -> {
                        val id = p.getAttributeValue(null, "id") ?: ""
                        val href = p.getAttributeValue(null, "href") ?: ""
                        manifest[id] = Triple(resolve(opfDir, href), p.getAttributeValue(null, "media-type") ?: "",
                            p.getAttributeValue(null, "properties") ?: "")
                    }
                    "meta" -> if (p.getAttributeValue(null, "name") == "cover") coverMetaId = p.getAttributeValue(null, "content")
                    "spine" -> tocId = p.getAttributeValue(null, "toc")
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { order += it }
                }
            } else if (ev == XmlPullParser.TEXT && inTitle) {
                val t = p.text.trim()
                if (t.isNotEmpty()) { title = t; gotTitle = true; inTitle = false }
            } else if (ev == XmlPullParser.END_TAG && p.name.substringAfter(':') == "title") {
                inTitle = false
            }
            ev = p.next()
        }
        for (id in order) {
            val item = manifest[id] ?: continue
            if (item.second.contains("html") || item.second.contains("xml") || item.first.endsWith("html")) spine += item.first
        }

        // Table of contents: EPUB3 nav document, else EPUB2 NCX.
        val labels = HashMap<String, String>()
        val nav = manifest.values.firstOrNull { it.third.split(' ').contains("nav") }
        val ncx = tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }
        try {
            if (nav != null) readNav(nav.first, labels) else if (ncx != null) readNcx(ncx.first, labels)
        } catch (_: Exception) { /* fall back to generic titles */ }
        coverPath = manifest.values.firstOrNull { it.third.split(' ').contains("cover-image") }?.first
            ?: coverMetaId?.let { manifest[it]?.first }
        for ((i, path) in spine.withIndex()) titles += labels[path]?.takeIf { it.isNotBlank() } ?: "Section ${i + 1}"
    }

    private fun readNav(path: String, out: MutableMap<String, String>) {
        val dir = path.substringBeforeLast('/', "")
        val html = readText(path) ?: return
        Regex("(?is)<a\\b[^>]*href\\s*=\\s*[\"']([^\"'#]*)[^\"']*[\"'][^>]*>(.*?)</a>").findAll(html).forEach { m ->
            val label = Html.fromHtml(m.groupValues[2], Html.FROM_HTML_MODE_COMPACT).toString().trim().replace(Regex("\\s+"), " ")
            val target = resolve(dir, m.groupValues[1])
            if (label.isNotEmpty() && target !in out) out[target] = label
        }
    }

    private fun readNcx(path: String, out: MutableMap<String, String>) {
        val dir = path.substringBeforeLast('/', "")
        val xml = readText(path) ?: return
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        var label: String? = null
        var inLabel = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "navLabel" -> inLabel = true
                    "content" -> {
                        val src = p.getAttributeValue(null, "src")?.substringBefore('#')
                        if (src != null && label != null) {
                            val t = resolve(dir, src)
                            if (t !in out) out[t] = label!!
                        }
                        label = null
                    }
                }
            } else if (ev == XmlPullParser.TEXT && inLabel) {
                label = (label ?: "") + p.text.trim()
            } else if (ev == XmlPullParser.END_TAG && p.name == "navLabel") {
                inLabel = false
            }
            ev = p.next()
        }
    }

    private fun attrOf(xml: String, tag: String, attr: String): String? {
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == tag) return p.getAttributeValue(null, attr)
            ev = p.next()
        }
        return null
    }

    private fun readText(path: String): String? {
        val e = zip.getEntry(path) ?: return null
        return zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8).removePrefix("\uFEFF") }
    }

    private fun resolve(dir: String, href: String): String {
        val decoded = Uri.decode(href.substringBefore('#'))
        if (decoded.isEmpty()) return ""
        val parts = ArrayDeque<String>()
        if (!decoded.startsWith("/") && dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in decoded.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(seg)
        }
        return parts.joinToString("/")
    }

    override fun load(index: Int, maxW: Int, maxH: Int): Spanned {
        val path = spine[index]
        if (path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")) {
            val sb = SpannableStringBuilder("￼")
            loadImage(path, maxW, (maxH * 1.08f).toInt(), upscale = true)?.let { bmp ->
                val d = BitmapDrawable(null, bmp).apply { setBounds(0, 0, bmp.width, bmp.height) }
                sb.setSpan(ImageSpan(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, 1, Spanned.SPAN_PARAGRAPH)
            return sb
        }
        val dir = path.substringBeforeLast('/', "")
        val html = readText(path) ?: ""
        val anchors = HashMap<String, Int>()
        val text = HtmlText.page(html, maxW, maxH, anchors) { src, w, h, up -> loadImage(resolve(dir, src), w, h, up) }
        chapterAnchors[index] = anchors
        return text
    }

    // --- internal links (contents page, footnotes, cross-references) ------------------------

    private val chapterAnchors = java.util.concurrent.ConcurrentHashMap<Int, Map<String, Int>>()

    override fun anchorOffset(chapter: Int, id: String): Int? {
        val map = chapterAnchors[chapter] ?: return null
        return map[id] ?: map[Uri.decode(id)]
    }

    override fun resolveLink(chapter: Int, href: String): LinkTarget? {
        val h = href.trim()
        if (h.isEmpty()) return null
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(h)) return LinkTarget(-1, null, external = true)   // http:, mailto:, ...
        val fragment = if ('#' in h) Uri.decode(h.substringAfter('#')) else null
        val pathPart = h.substringBefore('#')
        if (pathPart.isEmpty()) return LinkTarget(chapter, fragment)                  // "#note1": same chapter
        val dir = spine.getOrNull(chapter)?.substringBeforeLast('/', "") ?: ""
        val target = resolve(dir, pathPart)
        val index = spine.indexOf(target)
        if (index >= 0) return LinkTarget(index, fragment)
        // Fall back to matching just the file name (odd relative paths, different letter case).
        val name = target.substringAfterLast('/').lowercase()
        val byName = spine.indexOfFirst { it.substringAfterLast('/').lowercase() == name }
        return if (byName >= 0) LinkTarget(byName, fragment) else null
    }

    private fun loadImage(path: String, maxW: Int, maxH: Int, upscale: Boolean = false): Bitmap? {
        val e = zip.getEntry(path) ?: return null
        return try {
            ImageUtil.decode(zip.getInputStream(e).use { it.readBytes() }, maxW, maxH, upscale)
        } catch (_: Throwable) { null }
    }

    override fun cover(maxW: Int, maxH: Int): Bitmap? {
        coverPath?.let { p -> loadImage(p, maxW, maxH)?.let { return it } }
        // No declared cover: use the first picture on the first page (many EPUBs have a cover page, not a cover entry).
        val first = spine.firstOrNull() ?: return null
        if (first.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")) return loadImage(first, maxW, maxH)
        val html = readText(first) ?: return null
        val match = Regex("(?i)(?:src|href)\\s*=\\s*[\"']([^\"']+\\.(?:jpe?g|png|gif|webp))[\"']").find(html) ?: return null
        return loadImage(resolve(first.substringBeforeLast('/', ""), match.groupValues[1]), maxW, maxH)
    }

    override fun close() = zip.close()
}
