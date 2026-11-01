package com.djcohen.offlinereader

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

/**
 * Turns RAR (.cbr, including RAR5), 7z (.cb7) and tar (.cbt) comics into a plain zip of the page images, once,
 * at import time. Decoding is done by libarchive, which is a local library: it has no network code.
 */
object ComicConverter {
    val archiveExtensions = setOf("cbr", "cb7", "cbt")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp")

    /** True if [file] starts with the zip signature ("PK"). */
    fun isZip(file: File): Boolean = try {
        val magic = ByteArray(2)
        file.inputStream().use { it.read(magic) } == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
    } catch (_: Throwable) {
        false
    }

    fun toCbz(src: File, dst: File) {
        val archive = try { Archive.readNew() } catch (e: Throwable) { throw IllegalArgumentException("Archive support is unavailable.") }
        var pages = 0
        try {
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenFileName(archive, src.path.toByteArray(Charsets.UTF_8), 64 * 1024)
            ZipOutputStream(dst.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)           // pages are already compressed images
                val direct = ByteBuffer.allocateDirect(256 * 1024)
                val chunk = ByteArray(256 * 1024)
                val seen = HashSet<String>()
                while (true) {
                    val entry = Archive.readNextHeader(archive)
                    if (entry == 0L) break
                    if (ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG) continue
                    val raw = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { String(it, Charsets.UTF_8) } ?: continue
                    val name = raw.replace('\\', '/').trimStart('/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in imageExtensions || name.contains("__MACOSX") || name.substringAfterLast('/').startsWith(".")) continue
                    if (!seen.add(name)) continue
                    try {
                        zip.putNextEntry(ZipEntry(name))
                    } catch (_: ZipException) {
                        continue
                    }
                    while (true) {
                        direct.clear()
                        Archive.readData(archive, direct)
                        val n = direct.position()
                        if (n == 0) break
                        direct.flip()
                        direct.get(chunk, 0, n)
                        zip.write(chunk, 0, n)
                    }
                    zip.closeEntry()
                    pages++
                }
            }
        } catch (e: ArchiveException) {
            val msg = e.message ?: ""
            throw IllegalArgumentException(
                if (msg.contains("passphrase", true) || msg.contains("password", true) || msg.contains("encrypt", true))
                    "This archive is password-protected."
                else "Couldn't unpack this archive ($msg).",
            )
        } finally {
            try { Archive.readClose(archive) } catch (_: Throwable) {}
            try { Archive.readFree(archive) } catch (_: Throwable) {}
        }
        if (pages == 0) throw IllegalArgumentException("No page images were found in this archive.")
    }
}
