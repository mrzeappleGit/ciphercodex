package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavXmlTest {
    private val dufsXml = """
    <?xml version="1.0" encoding="utf-8"?>
    <D:multistatus xmlns:D="DAV:">
      <D:response><D:href>/ccx/state/</D:href><D:propstat><D:prop>
        <D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
      <D:response><D:href>/ccx/state/aabb01.json</D:href><D:propstat><D:prop>
        <D:resourcetype></D:resourcetype></D:prop></D:propstat></D:response>
      <D:response><D:href>/ccx/state/dead%20beef.json</D:href><D:propstat><D:prop>
        <D:resourcetype></D:resourcetype></D:prop></D:propstat></D:response>
    </D:multistatus>
    """.trimIndent()

    @Test
    fun `extracts child names, skips self, decodes percent-encoding`() {
        assertEquals(listOf("aabb01.json", "dead beef.json"),
            WebDavXml.childNames(dufsXml, "/ccx/state/"))
    }

    @Test
    fun `lowercase namespace prefix also parses`() {
        val xml = dufsXml.replace("D:", "d:")
        assertEquals(listOf("aabb01.json", "dead beef.json"),
            WebDavXml.childNames(xml, "/ccx/state/"))
    }

    @Test
    fun `directory children keep no trailing slash`() {
        val xml = """<D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/ccx/</D:href></D:response>
          <D:response><D:href>/ccx/books/</D:href></D:response>
        </D:multistatus>"""
        assertEquals(listOf("books"), WebDavXml.childNames(xml, "/ccx/"))
    }
}
