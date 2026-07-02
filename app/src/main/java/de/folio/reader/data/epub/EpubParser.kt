package de.folio.reader.data.epub

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Entpackt eine EPUB und liest Titel, Autor, Lese-Reihenfolge (Spine) und Cover.
 *
 * Bewusst schlank gehalten: Es wird kein vollständiger EPUB-Renderer gebaut. Die
 * Kapitel sind XHTML-Dateien, die der Reader direkt im WebView lädt – dadurch
 * lösen sich relative Bild-/CSS-Verweise von selbst auf.
 */
class EpubParser {

    /** Entpackt [epub] nach [targetDir] (wird zuvor geleert). */
    fun extract(epub: File, targetDir: File) {
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        epub.inputStream().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    // Zip-Slip-Schutz
                    if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        throw SecurityException("Ungültiger Zip-Eintrag: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.copyTo(it, 1 shl 16) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    /** Parst eine bereits entpackte EPUB unter [bookDir]. */
    fun parse(bookDir: File): EpubBook {
        val opfFile = locateOpf(bookDir)
        val opfDir = opfFile.parentFile ?: bookDir

        val manifest = HashMap<String, ManifestItem>()      // id -> item
        val spineIds = ArrayList<String>()
        var title = ""
        var author = ""
        var coverId: String? = null

        opfFile.inputStream().use { stream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(stream, null)

            var event = parser.eventType
            var capture: StringBuilder? = null
            var captureTarget = ""

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val type = parser.getAttributeValue(null, "media-type") ?: ""
                            val props = parser.getAttributeValue(null, "properties") ?: ""
                            if (id.isNotEmpty()) manifest[id] = ManifestItem(id, href, type, props)
                            if (props.contains("cover-image")) coverId = id
                        }
                        "itemref" -> parser.getAttributeValue(null, "idref")?.let { spineIds += it }
                        "meta" -> {
                            val name = parser.getAttributeValue(null, "name")
                            if (name == "cover") {
                                parser.getAttributeValue(null, "content")?.let { coverId = it }
                            }
                        }
                        "title" -> { capture = StringBuilder(); captureTarget = "title" }
                        "creator" -> { capture = StringBuilder(); captureTarget = "creator" }
                    }
                    XmlPullParser.TEXT -> capture?.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        if (capture != null && (parser.name == "title" || parser.name == "creator")) {
                            val text = capture.toString().trim()
                            if (captureTarget == "title" && title.isEmpty()) title = text
                            if (captureTarget == "creator" && author.isEmpty()) author = text
                            capture = null
                        }
                    }
                }
                event = parser.next()
            }
        }

        val spinePaths = spineIds.mapNotNull { id ->
            manifest[id]?.href?.let { resolve(opfDir, it) }
        }.filter { File(it).exists() }

        val coverPath = coverId?.let { manifest[it]?.href }
            ?.let { resolve(opfDir, it) }
            ?.takeIf { File(it).exists() }

        return EpubBook(
            title = title.ifBlank { bookDir.name },
            author = author,
            spine = spinePaths,
            coverPath = coverPath,
        )
    }

    private fun locateOpf(bookDir: File): File {
        val container = File(bookDir, "META-INF/container.xml")
        if (container.exists()) {
            val fullPath = container.inputStream().use { readContainerRootfile(it) }
            if (fullPath != null) {
                val opf = File(bookDir, fullPath)
                if (opf.exists()) return opf
            }
        }
        // Fallback: erste .opf-Datei suchen
        return bookDir.walkTopDown().firstOrNull { it.extension.equals("opf", true) }
            ?: throw IllegalStateException("Keine OPF-Datei gefunden – ungültige EPUB.")
    }

    private fun readContainerRootfile(stream: InputStream): String? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            event = parser.next()
        }
        return null
    }

    /** Löst einen href relativ zum OPF-Ordner zu einem absoluten Pfad auf. */
    private fun resolve(opfDir: File, href: String): String {
        // hrefs im OPF sind häufig URL-kodiert (z. B. "Kapitel%201.xhtml") –
        // ohne Dekodierung existiert die Datei scheinbar nicht und das Kapitel
        // fällt aus dem Spine. Uri.decode dekodiert nur %XX-Sequenzen.
        val clean = android.net.Uri.decode(href.substringBefore('#').substringBefore('?'))
        return File(opfDir, clean).canonicalPath
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )
}
