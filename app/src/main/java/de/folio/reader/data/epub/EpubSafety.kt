package de.folio.reader.data.epub

import org.jsoup.Jsoup
import java.io.File

object EpubSafety {
    fun resolve(root: File, directory: File, href: String): File {
        val clean = android.net.Uri.decode(href.substringBefore('#').substringBefore('?'))
        require(!clean.contains(':') && !clean.contains('\\')) { "Ungültiger EPUB-Pfad." }
        val file = File(directory, clean).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "EPUB verweist außerhalb seines Buchverzeichnisses." }
        return file
    }
    /** Keep publisher layout; remove book-owned executable content. App pagination is injected separately. */
    fun sanitize(html: String, svg: Boolean = false): String {
        val doc = if (svg) Jsoup.parse(html, "", org.jsoup.parser.Parser.xmlParser()) else Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        doc.select("script,iframe,object,embed,base,meta[http-equiv=refresh]").remove()
        doc.allElements.forEach { element ->
            element.attributes().asList().filter { it.key.startsWith("on", true) }.forEach { element.removeAttr(it.key) }
            listOf("href", "src", "xlink:href", "action").forEach { attr ->
                val value = element.attr(attr).trim()
                if (value.startsWith("javascript:", true) || value.startsWith("vbscript:", true)) element.removeAttr(attr)
            }
        }
        doc.select("meta[http-equiv=Content-Security-Policy]").remove()
        if (!svg) doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
            .attr("content", "default-src 'none'; img-src 'self' file: data:; style-src 'self' file: 'unsafe-inline'; font-src 'self' file: data:; script-src 'none'; frame-src 'none'; connect-src 'none'")
        return doc.outerHtml()
    }
}
