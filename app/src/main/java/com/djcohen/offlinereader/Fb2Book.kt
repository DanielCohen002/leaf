package com.djcohen.offlinereader

import android.graphics.Bitmap
import android.util.Base64
import android.text.Spanned
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** FictionBook 2 (.fb2): XML with the text in <section>s and pictures as base64 <binary> blocks. */
class Fb2Book(file: File) : Book {
    override var title: String = file.nameWithoutExtension.replace('_', ' ').trim()
        private set
    private val binaries = HashMap<String, String>()
    private var coverBinaryId: String? = null
    private val parts = ArrayList<String>()
    private val partTitles = ArrayList<String>()
    private val links = LinkIndex()
    override val chapterTitles: List<String> get() = partTitles

    init {
        val doc = try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        } catch (t: Throwable) {
            throw IllegalArgumentException("Not a valid FB2 file.")
        }
        val root = doc.documentElement
        if (root == null || !root.tagName.endsWith("FictionBook")) throw IllegalArgumentException("Not a valid FB2 file.")

        for (b in root.children("binary")) b.getAttribute("id").takeIf { it.isNotEmpty() }?.let { binaries[it] = b.textContent }

        val info = root.children("description").firstOrNull()?.children("title-info")?.firstOrNull()
        info?.children("book-title")?.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { title = it }
        val coverId = info?.children("coverpage")?.firstOrNull()?.children("image")?.firstOrNull()?.let { href(it) }
        if (coverId != null && binaries.containsKey(coverId.removePrefix("#"))) {
            coverBinaryId = coverId.removePrefix("#")
            parts += "<img src=\"$coverId\"/>"; partTitles += "Cover"
        }

        val bodies = root.children("body")
        val main = bodies.firstOrNull { it.getAttribute("name").isEmpty() } ?: bodies.firstOrNull()
            ?: throw IllegalArgumentException("This FB2 file has no text.")
        addBody(main)
        for (b in bodies) if (b !== main) addBody(b, fallbackTitle = b.getAttribute("name").ifEmpty { "Notes" }.replaceFirstChar { it.uppercase() })
        if (parts.isEmpty()) throw IllegalArgumentException("This FB2 file has no text.")
        parts.forEachIndexed { i, html -> links.scan(i, html) }
    }

    private fun Element.children(name: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.tagName == name) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun href(e: Element): String? {
        val attrs = e.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName.endsWith("href")) return a.nodeValue
        }
        return null
    }

    private fun titleOf(e: Element): String? =
        e.children("title").firstOrNull()?.textContent?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun addBody(body: Element, fallbackTitle: String? = null) {
        var sections = body.children("section")
        // A single wrapper section containing the real chapters: descend.
        while (sections.size == 1 && sections[0].children("section").size > 1) sections = sections[0].children("section")
        if (sections.isEmpty()) {
            parts += toHtml(body, 2)
            partTitles += fallbackTitle ?: titleOf(body) ?: title
            return
        }
        // Material in the body before the first section (title page, epigraph, image).
        if (sections.size > 1 || sections[0].parentNode === body) {
            val intro = StringBuilder()
            var n: Node? = body.firstChild
            while (n != null) {
                if (n is Element && n.tagName != "section") convert(n, intro, 2)
                n = n.nextSibling
            }
            if (intro.isNotBlank() && sections[0].parentNode === body) {
                parts += intro.toString(); partTitles += titleOf(body) ?: fallbackTitle ?: "Front matter"
            }
        }
        for ((i, s) in sections.withIndex()) {
            val html = toHtml(s, 2)
            parts += html
            partTitles += titleOf(s) ?: fallbackTitle?.let { "$it ${i + 1}" } ?: openingWords(html) ?: "Section ${i + 1}"
        }
    }

    /** Untitled section: use its first few words so the chapter list is still navigable. */
    private fun openingWords(html: String): String? {
        val text = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.length < 3) return null
        return if (text.length <= 48) text else text.take(48).substringBeforeLast(' ') + "…"
    }

    private fun toHtml(e: Element, headingLevel: Int): String = StringBuilder().also { convert(e, it, headingLevel) }.toString()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun convert(n: Node, sb: StringBuilder, h: Int) {
        if (n.nodeType == Node.TEXT_NODE) { sb.append(esc(n.nodeValue)); return }
        if (n !is Element) return
        // Targets of links (notes, contents entries): an empty named anchor in front of the element.
        n.getAttribute("id").takeIf { it.isNotEmpty() && n.tagName != "binary" }?.let { sb.append("<a id=\"").append(it.replace("\"", "")).append("\"></a>") }
        fun wrap(tag: String, level: Int = h) { sb.append('<').append(tag).append('>'); kids(n, sb, level); sb.append("</").append(tag).append('>') }
        when (n.tagName) {
            "section" -> kids(n, sb, minOf(h + 1, 4))
            "title" -> { val t = "h${minOf(h, 4)}"; sb.append('<').append(t).append('>'); kids(n, sb, h, joinWith = " "); sb.append("</").append(t).append('>') }
            "subtitle" -> wrap("h4")
            "p", "v" -> wrap("p")
            "text-author" -> { sb.append("<p><i>"); kids(n, sb, h); sb.append("</i></p>") }
            "empty-line" -> if (!sb.endsWith("<br/><br/>")) sb.append("<br/>")   // collapse long runs of blank lines
            "emphasis" -> wrap("i")
            "strong" -> wrap("b")
            "strikethrough" -> wrap("s")
            "sub" -> wrap("sub")
            "sup" -> wrap("sup")
            "code" -> wrap("tt")
            "poem", "stanza", "cite", "epigraph" -> wrap("blockquote")
            "image" -> href(n)?.let { sb.append("<img src=\"").append(it).append("\"/>") }
            "a" -> {
                val target = href(n)
                if (target == null) kids(n, sb, h)
                else { sb.append("<a href=\"").append(target.replace("\"", "%22")).append("\">"); kids(n, sb, h); sb.append("</a>") }
            }
            "binary" -> {}
            else -> kids(n, sb, h)
        }
    }

    private fun kids(e: Element, sb: StringBuilder, h: Int, joinWith: String = "") {
        var c: Node? = e.firstChild
        var first = true
        while (c != null) {
            if (!first && joinWith.isNotEmpty() && c is Element) sb.append(joinWith)
            convert(c, sb, h)
            first = false
            c = c.nextSibling
        }
    }

    override fun load(index: Int, maxW: Int, maxH: Int): Spanned =
        HtmlText.page(parts[index], maxW, maxH, links.collector(index)) { src, w, hh, up ->
            binaries[src.removePrefix("#")]?.let { b64 ->
                try { ImageUtil.decode(Base64.decode(b64, Base64.DEFAULT), w, hh, up) } catch (_: Throwable) { null }
            }
        }

    override fun resolveLink(chapter: Int, href: String) = links.resolve(chapter, href)
    override fun anchorOffset(chapter: Int, id: String) = links.offset(chapter, id)

    override fun cover(maxW: Int, maxH: Int): Bitmap? {
        val b64 = coverBinaryId?.let { binaries[it] } ?: return null
        return try { ImageUtil.decode(Base64.decode(b64, Base64.DEFAULT), maxW, maxH) } catch (_: Throwable) { null }
    }

    override fun close() {}
}
