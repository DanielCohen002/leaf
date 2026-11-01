package com.djcohen.offlinereader

import android.text.Spanned
import java.io.File

/** Markdown (.md): a small, forgiving converter to HTML, split into chapters at top-level headings. */
class MarkdownBook(file: File) : Book {
    override val title: String
    private val parts = ArrayList<String>()
    private val partTitles = ArrayList<String>()
    override val chapterTitles: List<String> get() = partTitles

    init {
        val text = String(file.readBytes(), Charsets.UTF_8).removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        // Chapters begin at "# " headings, or at "## " if there are no level-1 headings.
        var level = 1
        var marks = lines.indices.filter { isHeading(lines, it, 1) }
        if (marks.size < 2) { level = 2; marks = lines.indices.filter { isHeading(lines, it, 2) } }
        val firstHeading = Regex("^#{1,6}\\s+(.*?)\\s*#*\\s*$").find(lines.firstOrNull { it.startsWith("#") } ?: "")?.groupValues?.get(1)
        title = firstHeading?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension.replace('_', ' ').trim()

        if (marks.size < 2) {
            parts += convert(lines)
            partTitles += title
        } else {
            if (marks.first() > 0 && lines.subList(0, marks.first()).any { it.isNotBlank() }) {
                parts += convert(lines.subList(0, marks.first())); partTitles += "Front matter"
            }
            for ((n, m) in marks.withIndex()) {
                val end = if (n + 1 < marks.size) marks[n + 1] else lines.size
                parts += convert(lines.subList(m, end))
                partTitles += lines[m].trimStart('#').trim().trimEnd('#').trim().ifEmpty { "Section ${n + 1}" }
            }
        }
        if (parts.isEmpty()) { parts += ""; partTitles += title }
    }

    private fun isHeading(lines: List<String>, i: Int, level: Int): Boolean {
        if (insideFence(lines, i)) return false
        return lines[i].startsWith("#".repeat(level) + " ")
    }

    private fun insideFence(lines: List<String>, upTo: Int): Boolean {
        var open = false
        for (k in 0 until upTo) if (lines[k].trimStart().startsWith("```")) open = !open
        return open
    }

    // --- block level -----------------------------------------------------------------------

    private fun convert(lines: List<String>): String {
        val out = StringBuilder()
        val para = ArrayList<String>()
        var list: String? = null
        var fence = false
        val code = StringBuilder()

        fun flushPara() {
            if (para.isNotEmpty()) { out.append("<p>").append(inline(para.joinToString(" "))).append("</p>"); para.clear() }
        }
        fun closeList() { list?.let { out.append("</").append(it).append(">") }; list = null }

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (fence) { out.append("<pre>").append(esc(code.toString().trimEnd())).append("</pre>"); code.clear() }
                else { flushPara(); closeList() }
                fence = !fence
                continue
            }
            if (fence) { code.append(raw).append('\n'); continue }
            if (line.isBlank()) { flushPara(); closeList(); continue }

            val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$").find(line)
            val bullet = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
            val numbered = Regex("^\\s*\\d+[.)]\\s+(.*)$").find(line)
            when {
                heading != null -> {
                    flushPara(); closeList()
                    val n = heading.groupValues[1].length
                    out.append("<h$n>").append(inline(heading.groupValues[2])).append("</h$n>")
                }
                Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$").matches(line) -> { flushPara(); closeList(); out.append("<p>* * *</p>") }
                line.startsWith(">") -> {
                    flushPara(); closeList()
                    out.append("<blockquote>").append(inline(line.trimStart('>').trim())).append("</blockquote>")
                }
                bullet != null || numbered != null -> {
                    flushPara()
                    val kind = if (bullet != null) "ul" else "ol"
                    if (list != kind) { closeList(); out.append("<").append(kind).append(">"); list = kind }
                    out.append("<li>").append(inline((bullet ?: numbered)!!.groupValues[1])).append("</li>")
                }
                else -> { closeList(); para += line.trim() }
            }
        }
        if (fence) out.append("<pre>").append(esc(code.toString().trimEnd())).append("</pre>")
        flushPara(); closeList()
        return out.toString()
    }

    // --- inline ----------------------------------------------------------------------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(text: String): String {
        var s = esc(text)
        s = s.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")                   // images -> alt text
        s = s.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")                    // links -> label
        s = s.replace(Regex("`([^`]+)`"), "<tt>$1</tt>")
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        s = s.replace(Regex("(?<![\\w])__(.+?)__(?![\\w])"), "<b>$1</b>")
        s = s.replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "<i>$1</i>")
        s = s.replace(Regex("(?<![\\w])_(?!\\s)(.+?)(?<!\\s)_(?![\\w])"), "<i>$1</i>")
        s = s.replace(Regex("~~(.+?)~~"), "<s>$1</s>")
        return s
    }

    override fun load(index: Int, maxW: Int, maxH: Int): Spanned = HtmlText.page(parts[index], maxW, maxH) { _, _, _, _ -> null }

    override fun close() {}
}
