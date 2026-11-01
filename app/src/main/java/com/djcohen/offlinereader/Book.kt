package com.djcohen.offlinereader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Html
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
    fun close()
}

object BookLoader {
    val supportedExtensions = setOf("epub", "txt", "text", "html", "htm", "xhtml")

    fun open(file: File, ext: String): Book = when (ext.lowercase()) {
        "epub" -> EpubBook(file)
        "txt", "text" -> TxtBook(file)
        "html", "htm", "xhtml" -> HtmlBook(file)
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
    private val prolog = Regex("(?is)<\\?xml.*?\\?>|<!DOCTYPE[^>]*>")

    fun toSpanned(html: String, images: ((String, Int, Int) -> Bitmap?)?, maxW: Int, maxH: Int): Spanned {
        val cleaned = html.replace(prolog, "").replace(head, "").replace(style, "").replace(script, "")
        val getter = Html.ImageGetter { src ->
            val bmp = images?.invoke(src, maxW, maxH)
            if (bmp == null) null else BitmapDrawable(null, bmp).apply { setBounds(0, 0, bmp.width, bmp.height) }
        }
        val spanned = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT, getter, null)
        return tidy(SpannableStringBuilder(spanned))
    }

    /** Strip publisher colours/sizes (they break dark mode and font choice), trim, indent paragraphs. */
    fun tidy(sb: SpannableStringBuilder): Spanned {
        for (cls in arrayOf(ForegroundColorSpan::class.java, BackgroundColorSpan::class.java, AbsoluteSizeSpan::class.java)) {
            for (s in sb.getSpans(0, sb.length, cls)) sb.removeSpan(s)
        }
        // Normalise object-replacement / nbsp noise and trim.
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace()) end--
        sb.delete(end, sb.length)
        var start = 0
        while (start < sb.length && sb[start].isWhitespace()) start++
        sb.delete(0, start)
        indentParagraphs(sb)
        return sb
    }

    fun indentParagraphs(sb: SpannableStringBuilder) {
        val indent = 28
        var i = 0
        while (i < sb.length) {
            var j = sb.indexOf('\n', i)
            if (j < 0) j = sb.length
            if (j - i > 60) sb.setSpan(LeadingMarginSpan.Standard(indent, 0), i, j, Spanned.SPAN_PARAGRAPH)
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
        val sb = SpannableStringBuilder(chapters[index].second.trimEnd())
        HtmlText.indentParagraphs(sb)
        return sb
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

    init {
        val container = readText("META-INF/container.xml") ?: throw IllegalArgumentException("Not an EPUB")
        val opfPath = attrOf(container, "rootfile", "full-path") ?: throw IllegalArgumentException("EPUB has no package file")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = readText(opfPath) ?: throw IllegalArgumentException("EPUB package missing")
        parseOpf(opf, opfDir)
        if (spine.isEmpty()) throw IllegalArgumentException("EPUB has no readable chapters")
    }

    private fun parseOpf(opf: String, opfDir: String) {
        val manifest = HashMap<String, Triple<String, String, String>>() // id -> (path, mediaType, properties)
        val order = mutableListOf<String>()
        var tocId: String? = null
        val p = Xml.newPullParser()
        p.setInput(opf.reader())
        var inTitle = false
        var gotTitle = false
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
        val dir = path.substringBeforeLast('/', "")
        val html = readText(path) ?: ""
        return HtmlText.toSpanned(html, { src, w, h -> loadImage(resolve(dir, src), w, h) }, maxW, maxH)
    }

    private fun loadImage(path: String, maxW: Int, maxH: Int): Bitmap? {
        val e = zip.getEntry(path) ?: return null
        return try {
            val bytes = zip.getInputStream(e).use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height, 1f)
            if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            else bmp
        } catch (_: Throwable) { null }
    }

    override fun close() = zip.close()
}
