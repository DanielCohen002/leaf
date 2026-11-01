package com.djcohen.offlinereader

import android.text.Html
import android.text.Spanned
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * Kindle / Mobipocket books: .mobi, .azw, .azw3, .prc (DRM-free only).
 * Handles classic MOBI (PalmDOC compression) and KF8 (AZW3, via the skeleton/fragment indexes).
 * HUFF/CDIC-compressed books and DRM-protected books are rejected with a clear message.
 */
class MobiBook(file: File) : Book {
    private val data = file.readBytes()
    private val recOffsets: IntArray
    override var title: String = file.nameWithoutExtension.replace('_', ' ').trim()
        private set
    private val parts = ArrayList<String>()
    private val partTitles = ArrayList<String>()
    private var firstImage = -1
    private var charset: Charset = Charsets.UTF_8
    private var ncxIdx = -1
    private var ncx: List<Pair<Int, String>> = emptyList()   // (byte position in text, label)
    override val chapterTitles: List<String> get() = partTitles

    init {
        if (data.size < 80) throw IllegalArgumentException("Not a Kindle/Mobipocket file.")
        val n = u16(data, 76)
        if (n < 2) throw IllegalArgumentException("Not a Kindle/Mobipocket file.")
        recOffsets = IntArray(n + 1) { if (it < n) u32(data, 78 + 8 * it) else data.size }
        parse()
        if (parts.isEmpty()) throw IllegalArgumentException("This book has no readable text.")
    }

    // --- byte helpers ----------------------------------------------------------------------

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun rec(i: Int): ByteArray {
        if (i < 0 || i + 1 >= recOffsets.size) return ByteArray(0)
        val s = recOffsets[i]
        val e = recOffsets[i + 1]
        return if (s in 0..e && e <= data.size) data.copyOfRange(s, e) else ByteArray(0)
    }

    // --- parsing ---------------------------------------------------------------------------

    private fun parse() {
        val r0 = rec(0)
        if (r0.size < 16) throw IllegalArgumentException("Not a Kindle/Mobipocket file.")
        val compression = u16(r0, 0)
        val recCount = u16(r0, 8)
        val encryption = u16(r0, 12)
        if (encryption != 0) throw IllegalArgumentException("This book is DRM-protected and can't be opened.")
        if (compression != 1 && compression != 2) {
            throw IllegalArgumentException("This book uses HUFF/CDIC compression, which isn't supported.")
        }

        var extraFlags = 0
        var version = 0
        var kf8Skel = -1
        var kf8Frag = -1
        var coverOffset = -1
        val hasMobi = r0.size >= 132 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (hasMobi) {
            val hdrLen = u32(r0, 20)
            val encoding = u32(r0, 28)
            charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
            version = u32(r0, 36)
            firstImage = u32(r0, 108)
            if (firstImage == -1 || firstImage < 0) firstImage = -1
            val fullOff = u32(r0, 84)
            val fullLen = u32(r0, 88)
            if (fullOff in 0 until r0.size && fullLen > 0 && fullOff + fullLen <= r0.size) {
                title = String(r0, fullOff, fullLen, charset).trim().ifBlank { title }
            }
            if (hdrLen >= 0xE4 && r0.size >= 244) extraFlags = u32(r0, 240)
            if (version == 8 && r0.size >= 256) { kf8Frag = u32(r0, 248); kf8Skel = u32(r0, 252) }
            if (hdrLen >= 0xE8 && r0.size >= 248) ncxIdx = u32(r0, 244)

            val exthFlags = u32(r0, 128)
            val exthPos = 16 + hdrLen
            if ((exthFlags and 0x40) != 0 && exthPos + 12 <= r0.size && String(r0, exthPos, 4, Charsets.ISO_8859_1) == "EXTH") {
                val count = u32(r0, exthPos + 8)
                var p = exthPos + 12
                for (k in 0 until count) {
                    if (p + 8 > r0.size) break
                    val type = u32(r0, p)
                    val len = u32(r0, p + 4)
                    if (len < 8 || p + len > r0.size) break
                    when (type) {
                        503 -> title = String(r0, p + 8, len - 8, charset).trim().ifBlank { title }
                        201 -> if (len >= 12) coverOffset = u32(r0, p + 8)
                    }
                    p += len
                }
            }
        }

        // Concatenate and decompress the text records.
        val out = ByteArrayOutputStream()
        for (i in 1..recCount) {
            var raw = rec(i)
            if (raw.isEmpty()) continue
            raw = raw.copyOf((raw.size - trailingSize(raw, extraFlags)).coerceAtLeast(0))
            if (compression == 2) palmDecompress(raw, out) else out.write(raw)
        }
        val text = out.toByteArray()

        ncx = if (ncxIdx > 0) readNcx(ncxIdx) else emptyList()
        if (version == 8 && kf8Skel > 0 && kf8Frag > 0) buildKf8(text, kf8Skel, kf8Frag) else buildClassic(text)

        // Cover that isn't already the first thing shown.
        if (coverOffset >= 0 && firstImage >= 0 && parts.isNotEmpty() && !parts[0].contains("<img", ignoreCase = true)) {
            parts.add(0, "<img src=\"rec:${firstImage + coverOffset}\"/>")
            partTitles.add(0, "Cover")
        }
    }

    /** Size of the per-record trailing data (multibyte overlap, indexes) that must be stripped before decompressing. */
    private fun trailingSize(d: ByteArray, flags: Int): Int {
        fun entrySize(end: Int): Int {
            var bitpos = 0
            var result = 0
            var p = end
            while (true) {
                if (p <= 0) return result
                val v = d[p - 1].toInt() and 0xFF
                result = result or ((v and 0x7F) shl bitpos)
                bitpos += 7
                p--
                if ((v and 0x80) != 0 || bitpos >= 28 || p == 0) return result
            }
        }
        var num = 0
        var f = flags ushr 1
        while (f != 0) {
            if ((f and 1) != 0) num += entrySize(d.size - num)
            f = f ushr 1
        }
        if ((flags and 1) != 0 && d.size - num - 1 >= 0) num += (d[d.size - num - 1].toInt() and 0x3) + 1
        return num
    }

    private fun palmDecompress(inp: ByteArray, out: ByteArrayOutputStream) {
        val buf = ByteArrayOutputStream(inp.size * 3)
        var window = ByteArray(inp.size * 4 + 4096)
        var len = 0
        fun put(b: Int) {
            if (len == window.size) window = window.copyOf(window.size * 2)
            window[len++] = b.toByte()
        }
        var i = 0
        while (i < inp.size) {
            val c = inp[i++].toInt() and 0xFF
            when {
                c == 0 || c in 0x09..0x7F -> put(c)
                c in 1..8 -> { var k = 0; while (k < c && i < inp.size) { put(inp[i++].toInt() and 0xFF); k++ } }
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                else -> {
                    if (i >= inp.size) break
                    val pair = (c shl 8) or (inp[i++].toInt() and 0xFF)
                    val dist = (pair shr 3) and 0x7FF
                    val n = (pair and 7) + 3
                    if (dist == 0 || dist > len) continue
                    for (k in 0 until n) put(window[len - dist].toInt() and 0xFF)
                }
            }
        }
        buf.write(window, 0, len)
        buf.writeTo(out)
    }

    // --- classic MOBI ----------------------------------------------------------------------

    private fun fixImages(html: String): String =
        html.replace(Regex("(?is)<img[^>]*?recindex\\s*=\\s*[\"']?0*(\\d+)[\"']?[^>]*>")) {
            "<img src=\"rec:${firstImage + it.groupValues[1].toInt() - 1}\"/>"
        }

    private fun buildClassic(text: ByteArray) {
        // Work on a 1-char-per-byte view so that table-of-contents byte positions line up with string offsets.
        val raw = String(text, Charsets.ISO_8859_1)
        fun decode(a: Int, b: Int) = fixImages(String(raw.substring(a, b).toByteArray(Charsets.ISO_8859_1), charset))

        if (ncx.size >= 2) {
            val cuts = ncx.map { it.first.coerceIn(0, raw.length) }.distinct().sorted()
            val bounds = (listOf(0) + cuts + listOf(raw.length)).distinct()
            for (i in 0 until bounds.size - 1) {
                val html = decode(bounds[i], bounds[i + 1])
                val label = ncx.firstOrNull { it.first.coerceIn(0, raw.length) == bounds[i] }?.second
                val hasImage = Regex("(?i)<img\\b").containsMatchIn(html)
                if (plain(html).isEmpty() && !hasImage) continue
                parts += html
                partTitles += label ?: fallbackTitle(html, parts.size)
            }
            if (parts.isNotEmpty()) return
        }

        var pieces = raw.split(Regex("(?i)<mbp:pagebreak\\s*/?>"))
        if (pieces.size <= 1) pieces = raw.split(Regex("(?i)(?=<h[12][\\s>])"))
        if (pieces.size <= 1) pieces = splitLarge(raw, 60_000)
        addMerged(pieces.map { fixImages(String(it.toByteArray(Charsets.ISO_8859_1), charset)) })
    }

    // --- KF8 / AZW3 ------------------------------------------------------------------------

    private class IndexEntry(val key: String, val tags: Map<Int, List<Int>>)

    private fun buildKf8(text: ByteArray, skelIdx: Int, fragIdx: Int) {
        val skeletons = readIndex(skelIdx).first
        val fragments = readIndex(fragIdx).first
        val ranges = ArrayList<IntRange>()
        var fragPtr = 0
        val htmlParts = ArrayList<String>()
        for (sk in skeletons) {
            val count = sk.tags[1]?.firstOrNull() ?: 0
            val pos = sk.tags[6] ?: continue
            if (pos.size < 2) continue
            val start = pos[0]
            val length = pos[1]
            if (start < 0 || start + length > text.size) continue
            var skeleton = text.copyOfRange(start, start + length)
            var basePtr = start + length
            for (j in 0 until count) {
                val frag = fragments.getOrNull(fragPtr++) ?: break
                val insertPos = (frag.key.toIntOrNull() ?: 0) - start
                val flen = frag.tags[6]?.getOrNull(1) ?: 0
                if (basePtr + flen > text.size || flen < 0) break
                val slice = text.copyOfRange(basePtr, basePtr + flen)
                val at = insertPos.coerceIn(0, skeleton.size)
                skeleton = skeleton.copyOfRange(0, at) + slice + skeleton.copyOfRange(at, skeleton.size)
                basePtr += flen
            }
            htmlParts += fixImages(String(skeleton, charset))
            ranges += start until basePtr
        }
        if (htmlParts.isEmpty()) { buildClassic(text); return }
        for ((i, html) in htmlParts.withIndex()) {
            val hasImage = Regex("(?i)<img\\b").containsMatchIn(html)
            if (plain(html).isEmpty() && !hasImage) continue
            parts += html
            val label = ncx.firstOrNull { it.first in ranges[i] }?.second
            partTitles += label ?: fallbackTitle(html, parts.size)
        }
        if (parts.isEmpty()) addMerged(htmlParts)
    }

    /** Read a MOBI INDX index starting at record [start]; returns entries with their tag values. */
    private fun readIndex(start: Int): Pair<List<IndexEntry>, Map<Int, String>> {
        val none = emptyList<IndexEntry>() to emptyMap<Int, String>()
        val h = rec(start)
        if (h.size < 184 || String(h, 0, 4, Charsets.ISO_8859_1) != "INDX") return none
        val recordCount = u32(h, 24)
        val tagxOff = u32(h, 180)
        if (tagxOff <= 0 || tagxOff + 12 > h.size || String(h, tagxOff, 4, Charsets.ISO_8859_1) != "TAGX") return none
        val firstEntry = u32(h, tagxOff + 4)
        val controlBytes = u32(h, tagxOff + 8)
        class TagDef(val tag: Int, val values: Int, val mask: Int, val endOfGroup: Boolean)
        val defs = ArrayList<TagDef>()
        var p = tagxOff + 12
        while (p + 4 <= tagxOff + firstEntry && p + 4 <= h.size) {
            defs += TagDef(h[p].toInt() and 0xFF, h[p + 1].toInt() and 0xFF, h[p + 2].toInt() and 0xFF, (h[p + 3].toInt() and 0xFF) == 1)
            p += 4
        }

        val result = ArrayList<IndexEntry>()
        for (r in 1..recordCount) {
            val d = rec(start + r)
            if (d.size < 28 || String(d, 0, 4, Charsets.ISO_8859_1) != "INDX") continue
            val idxt = u32(d, 20)
            val entries = u32(d, 24)
            if (idxt <= 0 || idxt + 4 + entries * 2 > d.size) continue
            val offs = IntArray(entries + 1) { if (it < entries) u16(d, idxt + 4 + it * 2) else idxt }
            for (e in 0 until entries) {
                var q = offs[e]
                val end = offs[e + 1]
                if (q >= end || q >= d.size) continue
                val keyLen = d[q].toInt() and 0xFF
                val key = String(d, q + 1, keyLen, Charsets.ISO_8859_1)
                q += 1 + keyLen
                val cb = d.copyOfRange(q, (q + controlBytes).coerceAtMost(d.size))
                q += controlBytes
                val tags = HashMap<Int, List<Int>>()
                var cbIndex = 0
                for (def in defs) {
                    if (def.endOfGroup) { cbIndex++; continue }
                    if (cbIndex >= cb.size) break
                    val value = (cb[cbIndex].toInt() and 0xFF) and def.mask
                    if (value == 0) continue
                    var shift = 0
                    var m = def.mask
                    while (m and 1 == 0) { m = m ushr 1; shift++ }
                    val vals = ArrayList<Int>()
                    fun vwi(): Int {
                        var v = 0
                        while (q < d.size) {
                            val b = d[q++].toInt() and 0xFF
                            v = (v shl 7) or (b and 0x7F)
                            if ((b and 0x80) != 0) break
                        }
                        return v
                    }
                    if (value == def.mask && Integer.bitCount(def.mask) > 1) {
                        val totalBytes = vwi()
                        val stop = q + totalBytes
                        while (q < stop && q < d.size) vals += vwi()
                    } else {
                        val count = if (value == def.mask) 1 else value ushr shift
                        for (k in 0 until count * def.values) vals += vwi()
                    }
                    tags[def.tag] = vals
                }
                result += IndexEntry(key, tags)
            }
        }
        // CNCX string pool(s) follow the index records: length-prefixed (variable-width) strings.
        val cncx = HashMap<Int, String>()
        var base = 0
        for (c in 0 until u32(h, 52).coerceIn(0, 64)) {
            val d = rec(start + recordCount + 1 + c)
            var q = 0
            while (q < d.size) {
                var len = 0
                var consumed = 0
                while (q + consumed < d.size) {
                    val b = d[q + consumed].toInt() and 0xFF
                    len = (len shl 7) or (b and 0x7F)
                    consumed++
                    if ((b and 0x80) != 0) break
                }
                if (len > 0 && q + consumed + len <= d.size) cncx[base + q] = String(d, q + consumed, len, Charsets.UTF_8)
                q += consumed + len
            }
            base += 0x10000
        }
        return result to cncx
    }

    private fun readNcx(idx: Int): List<Pair<Int, String>> = try {
        val (entries, cncx) = readIndex(idx)
        entries.mapNotNull { e ->
            val pos = e.tags[1]?.firstOrNull() ?: return@mapNotNull null
            val label = e.tags[3]?.firstOrNull()?.let { cncx[it] }?.trim()
            if (label.isNullOrEmpty()) null else pos to label
        }.sortedBy { it.first }
    } catch (_: Throwable) {
        emptyList()
    }

    // --- chapter assembly ------------------------------------------------------------------

    private fun splitLarge(html: String, size: Int): List<String> {
        if (html.length <= size) return listOf(html)
        val out = ArrayList<String>()
        var s = 0
        while (s < html.length) {
            var e = html.indexOf("</p>", s + size, ignoreCase = true)
            e = if (e < 0) html.indexOf("<br", s + size, ignoreCase = true) else e + 4
            if (e < 0) e = html.length
            out += html.substring(s, e)
            s = e
        }
        return out
    }

    private fun plain(html: String) = html.replace(Regex("(?is)<head\\b.*?</head>|<style\\b.*?</style>"), "")
        .replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    private fun fallbackTitle(html: String, n: Int): String {
        val heading = Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>").find(html)?.groupValues?.get(1)?.let { plain(it) }
        if (!heading.isNullOrBlank()) return heading.take(60)
        if (!Regex("(?i)<img\\b").containsMatchIn(html.take(400))) {
            val t = plain(html)
            if (t.isNotEmpty()) return t.take(50)
        }
        return "Section $n"
    }

    /** Drop empty pieces, fold tiny pieces (title pages) into the next one, and derive chapter titles. */
    private fun addMerged(pieces: List<String>) {
        val merged = ArrayList<String>()
        var carry = ""
        for (piece in pieces) {
            val combined = carry + piece
            val hasImage = Regex("(?i)<img\\b").containsMatchIn(combined)
            if (plain(combined).isEmpty() && !hasImage) { carry = ""; continue }
            if (plain(combined).length < 150 && !hasImage) { carry = combined; continue }
            merged += combined
            carry = ""
        }
        if (carry.isNotBlank() && plain(carry).isNotEmpty()) merged += carry
        for (html in merged) {
            parts += html
            partTitles += fallbackTitle(html, parts.size)
        }
    }

    // --- rendering -------------------------------------------------------------------------

    override fun load(index: Int, maxW: Int, maxH: Int): Spanned =
        HtmlText.page(parts[index], maxW, maxH) { src, w, h, up ->
            val record = when {
                src.startsWith("rec:") -> src.removePrefix("rec:").toIntOrNull()
                src.startsWith("kindle:embed:") -> {
                    val code = src.removePrefix("kindle:embed:").substringBefore('?')
                    code.toIntOrNull(32)?.let { firstImage + it - 1 }
                }
                else -> null
            }
            if (record == null || record < 0) null else ImageUtil.decode(rec(record), w, h, up)
        }

    override fun close() {}
}
