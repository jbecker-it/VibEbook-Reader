package de.folio.reader.data.epub

import org.junit.Assert.*
import org.junit.Test

class EpubLayoutTest {
    private fun read(metadata: String, override: String = "") = EpubLayout.read("""
        <package xmlns="http://www.idpf.org/2007/opf"><metadata>$metadata</metadata>
        <manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/></manifest>
        <spine><itemref idref="a"/><itemref idref="b" properties="$override"/></spine></package>
    """.byteInputStream())

    @Test fun packageDefaultAndSpineOverride() {
        assertEquals(mapOf("a.xhtml" to true, "b.xhtml" to false), read(
            """<meta property="rendition:layout">pre-paginated</meta>""", "rendition:layout-reflowable"))
        assertEquals(mapOf("a.xhtml" to false, "b.xhtml" to true), read(
            """<meta property="rendition:layout">reflowable</meta>""", "rendition:layout-pre-paginated"))
    }

    @Test fun absentMetadataAllowsViewportDetection() {
        assertTrue(read("").isEmpty())
    }

    @Test(expected = org.xml.sax.SAXParseException::class)
    fun rejectsExternalEntities() {
        EpubLayout.read("""<!DOCTYPE package [<!ENTITY x SYSTEM "file:///etc/passwd">]><package>&x;</package>""".byteInputStream())
    }
}
