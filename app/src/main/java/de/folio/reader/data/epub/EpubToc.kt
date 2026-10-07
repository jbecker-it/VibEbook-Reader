package de.folio.reader.data.epub

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File

object EpubToc {
    fun read(root: File, opfDir: File, nav: String?, ncx: String?): String {
        val result = JSONArray()
        fun add(file: File, href: String, label: String, depth: Int) {
            val target = EpubSafety.resolve(root, file.parentFile!!, href)
            if (target.isFile && label.isNotBlank()) result.put(JSONObject().put("label", label.trim())
                .put("path", target.path).put("fragment", android.net.Uri.decode(href.substringAfter('#', ""))).put("depth", depth.coerceAtMost(8)))
        }
        try {
            if (nav != null) {
                val file = EpubSafety.resolve(root, opfDir, nav)
                require(file.length() <= 4 * 1024 * 1024); val document = Jsoup.parse(file, "UTF-8")
                val toc = document.select("nav").firstOrNull { it.attr("epub:type").split(' ').contains("toc") || it.attr("role") == "doc-toc" }
                    ?: document.selectFirst("nav")
                toc?.select("a[href]")?.forEach { a -> add(file, a.attr("href"), a.text(), a.parents().count { it.tagName() == "ol" }.minus(1).coerceAtLeast(0)) }
            }
            if (result.length() == 0 && ncx != null) {
                val file = EpubSafety.resolve(root, opfDir, ncx)
                require(file.length() <= 4 * 1024 * 1024); val document = Jsoup.parse(file.readText(), "", Parser.xmlParser())
                document.select("navPoint").forEach { point ->
                    val label = point.children().firstOrNull { it.tagName().equals("navLabel", true) }?.text().orEmpty()
                    val href = point.children().firstOrNull { it.tagName().equals("content", true) }?.attr("src").orEmpty()
                    add(file, href, label, point.parents().count { it.tagName().equals("navPoint", true) })
                }
            }
        } catch (_: Exception) { /* A broken optional TOC does not make a book unreadable. */ }
        return result.toString()
    }
}
