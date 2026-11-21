/*
 * ZipArchive.kt
 *
 * Robust, dependency-free ZIP reader that parses the Central Directory by hand.
 *
 * Why this exists: on Android 5/6 (API <= 23), java.util.zip.ZipInputStream / ZipFile
 * decode entry names with java.nio.charset.ModifiedUtf8, which throws
 * java.io.UTFDataFormatException ("bad second byte at 1") whenever an entry name is
 * encoded in anything that is not valid Modified UTF-8 (e.g. GBK, the default on
 * Chinese Windows). A corrupt/decode-able name crashes getNextEntry() and aborts the
 * whole game launch. AOSP only fixed this in API 24, so both our minSdk=21 and
 * minSdk=23 builds are affected.
 *
 * This reader treats entry names as raw bytes and decodes them with UTF-8 (falling back
 * to GBK, then ISO-8859-1), which never throws. Extraction reads the local file header
 * directly and inflates the raw deflate stream, so it never touches ZipInputStream's
 * fragile name decoder either.
 */

package com.swordfish.lemuroid.common.kotlin

import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object ZipArchive {

    data class Entry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val method: Int,
        val crc: Long,
        val localHeaderOffset: Long,
        val isDirectory: Boolean
    )

    private const val EOCD_SIG = 0x06054b50
    private const val CD_SIG = 0x02014b50
    private const val LFH_SIG = 0x04034b50
    private const val EOCD_MIN_SIZE = 22
    private const val MAX_EOCD_SCAN = 65557 // 22-byte EOCD + max 65535-byte comment

    private const val FLAG_UTF8 = 0x800
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    /** Read every entry from the central directory. Never throws on bad filenames.
     *  Returns null when the file is missing, unreadable, or not a valid zip. */
    fun listEntries(file: File): List<Entry>? {
        if (!file.exists() || file.length() < EOCD_MIN_SIZE) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val eocdPos = findEocd(raf) ?: return null
                readCentralDirectory(raf, eocdPos)
            }
        }.getOrNull()
    }

    /** Largest non-directory entry whose extension is in [supportedExtensions].
     *  Honors [preferredName] when its extension is supported (mirrors the legacy
     *  File.findZipRomEntryName contract). */
    fun findRomEntry(
        file: File,
        supportedExtensions: Set<String>,
        preferredName: String? = null
    ): Entry? {
        val entries = listEntries(file) ?: return null
        if (entries.isEmpty()) return null

        if (preferredName != null &&
            preferredName.substringAfterLast('.', "").lowercase() in supportedExtensions
        ) {
            entries.firstOrNull { !it.isDirectory && it.name == preferredName }?.let { return it }
        }

        var best: Entry? = null
        for (e in entries) {
            if (e.isDirectory) continue
            val ext = e.name.substringAfterLast('.', "").lowercase()
            if (ext !in supportedExtensions) continue
            if (best == null || e.size > best.size) best = e
        }
        return best
    }

    /** Extract exactly [entry] to [target]. Returns true only when [target] is non-empty. */
    fun extractEntry(file: File, entry: Entry, target: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(entry.localHeaderOffset)
                if (raf.readLeU32() != LFH_SIG.toLong()) return false
                raf.seek(entry.localHeaderOffset + 26) // name length field in local header
                val nameLen = raf.readLeU16()
                val extraLen = raf.readLeU16()
                val dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen
                raf.seek(dataStart)

                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()

                BufferedOutputStream(FileOutputStream(target)).use { out ->
                    when (entry.method) {
                        METHOD_STORED -> copyExact(raf, out, entry.compressedSize)
                        METHOD_DEFLATED -> inflateExact(raf, out, entry.compressedSize)
                        else -> return false
                    }
                }
            }
            target.length() > 0
        }.getOrDefault(false)
    }

    // ---- low-level parsing -------------------------------------------------

    private fun findEocd(raf: RandomAccessFile): Long? {
        val len = raf.length()
        val scanStart = (len - MAX_EOCD_SCAN).coerceAtLeast(0)
        val scanLen = (len - scanStart).toInt()
        if (scanLen < EOCD_MIN_SIZE) return null
        val buf = ByteArray(scanLen)
        raf.seek(scanStart)
        if (raf.read(buf) != scanLen) return null
        var i = scanLen - EOCD_MIN_SIZE
        while (i >= 0) {
            if (buf[i].toInt() == 0x50 && buf[i + 1].toInt() == 0x4b &&
                buf[i + 2].toInt() == 0x05 && buf[i + 3].toInt() == 0x06
            ) {
                return scanStart + i
            }
            i--
        }
        return null
    }

    private fun readCentralDirectory(raf: RandomAccessFile, eocdPos: Long): List<Entry> {
        raf.seek(eocdPos + 10) // total number of central directory records
        val total = raf.readLeU16()
        if (total <= 0) return emptyList()
        raf.seek(eocdPos + 16) // offset of start of central directory
        val cdOffset = raf.readLeU32()
        if (cdOffset == 0xFFFFFFFFL) return emptyList() // Zip64 not supported
        raf.seek(cdOffset)

        val entries = ArrayList<Entry>(total)
        repeat(total) {
            if (raf.readLeU32() != CD_SIG.toLong()) return@repeat // malformed; stop early
            raf.seek(raf.filePointer + 4) // skip version-made-by + version-needed
            val flags = raf.readLeU16()
            val method = raf.readLeU16()
            raf.seek(raf.filePointer + 4) // skip mod time + mod date
            val crc = raf.readLeU32()
            val compressed = raf.readLeU32()
            val size = raf.readLeU32()
            val nameLen = raf.readLeU16()
            val extraLen = raf.readLeU16()
            val commentLen = raf.readLeU16()
            raf.seek(raf.filePointer + 8) // skip disk num + internal attrs + external attrs
            val localOffset = raf.readLeU32()

            val nameBytes = ByteArray(nameLen)
            if (nameLen > 0) {
                val read = raf.read(nameBytes)
                if (read != nameLen) return@repeat
            }
            val skip = extraLen.toLong() + commentLen.toLong()
            if (skip > 0) raf.seek(raf.filePointer + skip)

            val name = decodeName(nameBytes, (flags and FLAG_UTF8) != 0)
            entries.add(
                Entry(
                    name = name,
                    size = size,
                    compressedSize = compressed,
                    method = method,
                    crc = crc,
                    localHeaderOffset = localOffset,
                    isDirectory = name.endsWith("/")
                )
            )
        }
        return entries
    }

    private fun decodeName(bytes: ByteArray, utf8Flag: Boolean): String {
        if (bytes.isEmpty()) return ""
        if (utf8Flag) return String(bytes, StandardCharsets.UTF_8)
        val asUtf8 = String(bytes, StandardCharsets.UTF_8)
        if (!asUtf8.contains('\uFFFD')) return asUtf8
        return runCatching { String(bytes, Charset.forName("GBK")) }
            .getOrDefault(String(bytes, StandardCharsets.ISO_8859_1))
    }

    private fun copyExact(raf: RandomAccessFile, out: BufferedOutputStream, bytes: Long) {
        val buf = ByteArray(8192)
        var remaining = bytes
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = raf.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun inflateExact(raf: RandomAccessFile, out: BufferedOutputStream, bytes: Long) {
        val bounded = object : InputStream() {
            private var left = bytes
            override fun read(): Int {
                if (left <= 0) return -1
                val b = raf.read()
                if (b < 0) return -1
                left--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val toRead = minOf(len, left.toInt())
                val n = raf.read(b, off, toRead)
                if (n > 0) left -= n
                return n
            }
        }
        val inflater = Inflater(true) // raw deflate, no zlib header
        InflaterInputStream(bounded, inflater, 8192).use { inf ->
            val buf = ByteArray(8192)
            while (true) {
                val n = inf.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        inflater.end()
    }

    private fun RandomAccessFile.readLeU16(): Int {
        val b = ByteArray(2)
        if (read(b) != 2) throw EOFException()
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun RandomAccessFile.readLeU32(): Long {
        val b = ByteArray(4)
        if (read(b) != 4) throw EOFException()
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }
}
