package de.folio.reader.data.epub

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** OPF rendition metadata, including mixed-layout spine overrides. */
object EpubLayout {
    fun read(stream: InputStream): Map<String, Boolean> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(stream)
        fun elements(name: String): List<Element> {
            val nodes = doc.getElementsByTagNameNS("*", name)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
        val layout = elements("meta").firstOrNull {
            it.getAttribute("property") == "rendition:layout" && !it.hasAttribute("refines")
        }?.textContent?.trim()
        val default = when (layout) { "pre-paginated" -> true; "reflowable" -> false; else -> null }
        val hrefs = elements("item").associate { it.getAttribute("id") to it.getAttribute("href") }
        return buildMap {
            for (item in elements("itemref")) {
                val props = item.getAttribute("properties").split(Regex("\\s+"))
                val fixed = when {
                    "rendition:layout-pre-paginated" in props -> true
                    "rendition:layout-reflowable" in props -> false
                    else -> default
                }
                val href = hrefs[item.getAttribute("idref")]
                if (fixed != null && href != null) put(href, fixed)
            }
        }
    }
}
