package tech.mrzeapple.ciphercodex.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Vectors from KOReader's own spec/unit/util_spec.lua (fixtures from
 *  koreader/test-data). If these fail, sync document matching is broken
 *  against every KOReader and CrossPoint device. */
class DigestsTest {

    private fun fixture(name: String): File {
        val url = checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }
        return File(url.toURI())
    }

    @Test
    fun partialMd5MatchesKoreaderVector_pdf() {
        assertEquals("41cce710f34e5ec21315e19c99821415", Digests.partialMd5(fixture("tall.pdf")))
    }

    @Test
    fun partialMd5MatchesKoreaderVector_epub() {
        assertEquals("59d481d168cca6267322f150c5f6a2a3", Digests.partialMd5(fixture("leaves.epub")))
    }

    @Test
    fun partialMd5OfTinyFileIsPlainMd5() {
        val f = Files.createTempFile("digest", ".bin").toFile()
        f.writeBytes(ByteArray(100) { it.toByte() })
        // 100 bytes < 1024: only the offset-0 sample is read, so partial == full MD5.
        val full = java.security.MessageDigest.getInstance("MD5")
            .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(full, Digests.partialMd5(f))
        f.delete()
    }

    @Test
    fun md5HexIsLowercase32() {
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", Digests.md5Hex("password"))
    }
}
