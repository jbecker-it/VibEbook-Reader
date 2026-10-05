package de.folio.reader.data.epub

import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ComicArchiveParser {
    fun parse(root: File): EpubBook {
        val images = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") && !it.relativeTo(root).path.startsWith("__MACOSX/") }
            .sortedBy { file -> file.relativeTo(root).path.lowercase().replace(Regex("\\d+")) { it.value.padStart(16, '0') } }.toList()
        require(images.isNotEmpty()) { "Datei enthält weder EPUB-Kapitel noch Comic-Bilder." }
        val pages = File(root, "folio-cbz-pages").apply { mkdirs() }
        val toc = JSONArray()
        val spine = images.mapIndexed { index, image ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            val w = bounds.outWidth.takeIf { it > 0 } ?: 1000
            val h = bounds.outHeight.takeIf { it > 0 } ?: 1500
            val path = image.relativeTo(root).invariantSeparatorsPath.split('/').joinToString("/") { Uri.encode(it) }
            val page = File(pages, "${index.toString().padStart(5, '0')}.html")
            page.writeText("""<!DOCTYPE html><html><head><meta name="viewport" content="width=$w,height=$h"><style>html,body{margin:0;padding:0;width:${w}px;height:${h}px}img{width:100%;height:100%;object-fit:contain}</style></head><body><img src="../$path" alt="Seite ${index + 1}"></body></html>""")
            toc.put(JSONObject().put("label", "Seite ${index + 1}").put("path", page.path).put("fragment", "").put("depth", 0))
            page.path
        }
        return EpubBook("Comic", "", spine, images.first().path, toc.toString())
    }
}
