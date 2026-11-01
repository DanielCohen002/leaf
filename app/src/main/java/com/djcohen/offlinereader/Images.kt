package com.djcohen.offlinereader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A book made of page images (PDF, comics) rather than reflowable text. */
interface ImageBook {
    val title: String
    val pageCount: Int
    /** Render page [index] to fit within [maxW] x [maxH] pixels. May be called from a background thread. */
    fun render(index: Int, maxW: Int, maxH: Int): Bitmap?
    /** Width / height of page [index] without rendering it (0.7 if unknown). */
    fun aspect(index: Int): Float
    fun close()
}

/** Aspect ratio of every page; used to lay out scrolling and two-page views. Slow-ish for huge books, so call off the UI thread. */
fun ImageBook.allAspects(): FloatArray = FloatArray(pageCount) { aspect(it) }

object ImageBooks {
    val extensions = setOf("pdf", "cbz", "cbr", "cb7", "cbt")

    fun isImageFormat(ext: String) = ext.lowercase() in extensions

    fun open(file: File, ext: String): ImageBook = when (ext.lowercase()) {
        "pdf" -> PdfBook(file)
        "cbz", "cbr" -> CbzBook(file)
        else -> throw IllegalArgumentException("Unsupported format: .$ext")
    }
}

object ImageUtil {
    /** Decode [bytes], sub-sampling large images; scale to fit [maxW] x [maxH] (enlarging too when [upscale]). */
    fun decode(bytes: ByteArray, maxW: Int, maxH: Int, upscale: Boolean = false, maxPixels: Long = 12_000_000L): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
            // Very tall strips (webtoons) can be huge: cap the decoded pixel count.
            while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height, if (upscale) Float.MAX_VALUE else 1f)
            if (scale != 1f) Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true,
            ) else bmp
        } catch (_: Throwable) {
            null
        }
    }
}

// ---------------------------------------------------------------------------------------------
// PDF (Android's built-in renderer)
// ---------------------------------------------------------------------------------------------

class PdfBook(file: File) : ImageBook {
    override val title: String = file.nameWithoutExtension.replace('_', ' ').trim()
    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (t: Throwable) {
        fd.close()
        throw IllegalArgumentException(
            if (t is SecurityException) "This PDF is password-protected." else "Not a readable PDF.",
        )
    }
    override val pageCount: Int = renderer.pageCount

    // PdfRenderer allows only one open page at a time.
    @Synchronized
    override fun render(index: Int, maxW: Int, maxH: Int): Bitmap? {
        if (index !in 0 until pageCount) return null
        return try {
            renderer.openPage(index).use { page ->
                val scale = minOf(maxW.toFloat() / page.width, maxH.toFloat() / page.height)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    override fun aspect(index: Int): Float {
        if (index !in 0 until pageCount) return 0.7f
        return try {
            renderer.openPage(index).use { it.width.toFloat() / it.height }
        } catch (_: Throwable) {
            0.7f
        }
    }

    @Synchronized
    override fun close() {
        renderer.close()
        fd.close()
    }
}

// ---------------------------------------------------------------------------------------------
// Comic book archives (CBZ; many ".cbr" files are really zips too)
// ---------------------------------------------------------------------------------------------

class CbzBook(file: File) : ImageBook {
    override val title: String = file.nameWithoutExtension.replace('_', ' ').trim()
    private val zip: ZipFile
    private val pages: List<ZipEntry>
    override val pageCount: Int get() = pages.size

    init {
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        if (magic[0] == 'R'.code.toByte() && magic[1] == 'a'.code.toByte()) {
            throw IllegalArgumentException("This comic is RAR-compressed (.cbr), which isn't supported. Convert it to .cbz first.")
        }
        zip = try { ZipFile(file) } catch (t: Throwable) { throw IllegalArgumentException("Not a readable comic archive.") }
        val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp")
        pages = zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { e ->
                val name = e.name.substringAfterLast('/')
                !e.name.contains("__MACOSX") && !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in imageExt
            }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .toList()
        if (pages.isEmpty()) {
            zip.close()
            throw IllegalArgumentException("No images found in this archive.")
        }
    }

    override fun render(index: Int, maxW: Int, maxH: Int): Bitmap? {
        val entry = pages.getOrNull(index) ?: return null
        return try {
            ImageUtil.decode(zip.getInputStream(entry).use { it.readBytes() }, maxW, maxH)
        } catch (_: Throwable) {
            null
        }
    }

    override fun aspect(index: Int): Float {
        val entry = pages.getOrNull(index) ?: return 0.7f
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0.7f
        } catch (_: Throwable) {
            0.7f
        }
    }

    override fun close() = zip.close()

    /** "page2.jpg" sorts before "page10.jpg". */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val x = a.lowercase()
        val y = b.lowercase()
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var ei = i; while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j; while (ej < y.length && y[ej].isDigit()) ej++
                val na = x.substring(i, ei).trimStart('0')
                val nb = y.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                if (x[i] != y[j]) return x[i] - y[j]
                i++; j++
            }
        }
        return (x.length - i) - (y.length - j)
    }
}
