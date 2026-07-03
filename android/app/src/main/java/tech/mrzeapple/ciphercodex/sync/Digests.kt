package tech.mrzeapple.ciphercodex.sync

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

object Digests {

    fun md5Hex(text: String): String = md5Hex(text.toByteArray(Charsets.UTF_8))

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** KOReader's partialMD5 (frontend/util.lua) — the kosync "binary" document
     *  id. Samples up to 1024 bytes at offsets 0, then 1024*4^i for i in 0..10
     *  (the first offset is 0, not a negative shift: LuaJIT masks the shift
     *  count, so lshift(1024, -2) overflows to 0). Stops at the first offset
     *  past EOF; short tail reads are fed with their actual length. Lowercase
     *  hex, matching KOReader and CrossPoint byte for byte. */
    fun partialMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(1024)
        RandomAccessFile(file, "r").use { raf ->
            for (i in -1..10) {
                val offset = if (i == -1) 0L else 1024L shl (2 * i)
                if (offset >= raf.length()) break
                raf.seek(offset)
                var filled = 0
                while (filled < buffer.size) {
                    val n = raf.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == 0) break
                md.update(buffer, 0, filled)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
