package com.comicshelf.app.core

import android.graphics.Bitmap
import android.util.Log
import android.util.Xml
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * EPUB spine reader. OPF/XHTML are parsed on the Kotlin side (XmlPullParser);
 * chapter bytes come from the native random-access ZIP session opened by
 * [NativeBridge.epubOpen]. Nothing is extracted to disk or downloaded whole.
 *
 * One spine item == one "page" for progress/bookmarks. A spine item is either a
 * text chapter (blocks of paragraphs/images) or an image page (SVG/img wrapper).
 */
object EpubBookSource {

    private const val TAG = "EpubBookSource"
    private const val CACHE_CHAPTERS = 6

    data class SpineItem(val id: String, val href: String, val mediaType: String)
    data class Book(
        val title: String,
        val opfDir: String,
        val spine: List<SpineItem>,
        val manifestImages: List<String>,
    )

    sealed class Block {
        data class Text(val text: String, val heading: Int = 0) : Block()
        data class Image(val href: String) : Block()
    }

    data class Page(val blocks: List<Block>, val imageOnly: Boolean)

    private class Session(val book: Book) {
        // Visible chapters can load concurrently; LinkedHashMap itself is not thread-safe.
        val cache: LinkedHashMap<Int, Page> = object : LinkedHashMap<Int, Page>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>?) =
                size > CACHE_CHAPTERS
        }
    }

    private val sessions = ConcurrentHashMap<Long, Session>()

    suspend fun open(bookId: Long): Book? = withContext(Dispatchers.IO) {
        close(bookId)
        val arr = NativeBridge.epubOpen(bookId) ?: return@withContext null
        val opf = arr.getOrNull(0)?.trimStart('\uFEFF') ?: return@withContext null
        val opfDir = arr.getOrNull(1) ?: ""
        val entries = arr.getOrNull(2)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        try {
            val parser = android.util.Xml.newPullParser()
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
            parser.setInput(StringReader(sanitizeXml(opf)))

            val manifest = LinkedHashMap<String, SpineItem>()
            val images = ArrayList<String>()
            val spineIds = ArrayList<String>()
            var title = ""
            var inTitle = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.localName()) {
                        "item" -> {
                            val id = parser.attr("id")
                            val href = parser.attr("href")
                            if (id != null && href != null) {
                                val full = resolve(opfDir, href)
                                val mt = parser.attr("media-type") ?: when {
                                    isXhtml(full) -> "application/xhtml+xml"
                                    isRaster(full) -> "image/*"
                                    else -> ""
                                }
                                manifest[id] = SpineItem(id, full, mt)
                                if (mt.startsWith("image/") && isRaster(full)) images += full
                            }
                        }
                        "itemref" -> parser.attr("idref")?.let { spineIds += it }
                        "title" -> if (parser.namespace == DC_NS) inTitle = true
                    }
                    XmlPullParser.TEXT -> if (inTitle) title += parser.text ?: ""
                    XmlPullParser.END_TAG -> if (parser.localName() == "title") inTitle = false
                }
                event = parser.next()
            }

            val spine = spineIds.mapNotNull { manifest[it] }
            val pages = when {
                spine.isNotEmpty() -> spine
                images.isNotEmpty() -> images.map { SpineItem("", it, "image/*") }
                else -> entries.filter(::isRaster).sortedWith(NATURAL)
                    .map { SpineItem("", it, "image/*") }
            }
            if (pages.isEmpty()) {
                Log.w(TAG, "no pages book=$bookId opf=$opfDir")
                NativeBridge.epubClose(bookId)
                return@withContext null
            }
            val book = Book(title.trim(), opfDir, pages, images)
            sessions[bookId] = Session(book)
            Log.i(TAG, "open book=$bookId spine=${pages.size} title=${book.title.take(40)}")
            book
        } catch (t: Throwable) {
            Log.w(TAG, "OPF parse failed book=$bookId: ${t.javaClass.simpleName}: ${t.message}")
            NativeBridge.epubClose(bookId)
            null
        }
    }

    fun close(bookId: Long) {
        if (sessions.remove(bookId) != null) NativeBridge.epubClose(bookId)
    }

    suspend fun loadPage(bookId: Long, index: Int): Page? = withContext(Dispatchers.IO) {
        val s = sessions[bookId] ?: return@withContext null
        synchronized(s.cache) { s.cache[index] }?.let { return@withContext it }
        val item = s.book.spine.getOrNull(index) ?: return@withContext null
        val bytes = NativeBridge.epubRead(bookId, item.href) ?: run {
            Log.w(TAG, "chapter read failed book=$bookId idx=$index href=${item.href}")
            return@withContext Page(emptyList(), imageOnly = false)
        }
        val page = if (item.mediaType.startsWith("image/") || isRaster(item.href)) {
            Page(listOf(Block.Image(item.href)), imageOnly = true)
        } else {
            parseXhtml(bytes.toString(Charsets.UTF_8), baseDirOf(item.href))
        }
        synchronized(s.cache) { s.cache[index] = page }
        page
    }

    suspend fun loadImage(bookId: Long, href: String, maxBytes: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            val bytes = NativeBridge.epubRead(bookId, href) ?: return@withContext null
            PageDecoder.decodeImageBytes(bytes, maxBytes)
        }

    // ---------------------------------------------------------------- parsing

    private const val DC_NS = "http://purl.org/dc/elements/1.1/"

    private fun XmlPullParser.localName(): String =
        (name ?: "").substringAfter(':').lowercase()

    private fun XmlPullParser.attr(want: String): String? {
        for (i in 0 until attributeCount) {
            val n = (getAttributeName(i) ?: "").substringAfter(':').lowercase()
            if (n == want) return getAttributeValue(i)
        }
        return null
    }

    internal fun isRaster(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

    private fun isXhtml(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm")

    private fun baseDirOf(path: String): String =
        if ('/' !in path) "" else path.substring(0, path.lastIndexOf('/'))

    /** Relative href -> ZIP path. Strips fragment/query, percent-decodes, normalizes . .. */
    internal fun resolve(baseDir: String, raw: String): String {
        var h = raw.substringBefore('#').substringBefore('?')
        h = runCatching {
            URLDecoder.decode(h.replace("+", "%2B"), "UTF-8") // keep literal '+'
        }.getOrDefault(h)
        val parts = ArrayList<String>()
        if (!h.startsWith("/") && baseDir.isNotEmpty()) parts += baseDir.split('/')
        for (p in h.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += p
            }
        }
        return parts.joinToString("/")
    }

    private val NATURAL = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ia = i
                var jb = j
                while (ia < a.length && a[ia].isDigit()) ia++
                while (jb < b.length && b[jb].isDigit()) jb++
                val na = a.substring(i, ia).toLongOrNull() ?: Long.MAX_VALUE
                val nb = b.substring(j, jb).toLongOrNull() ?: Long.MAX_VALUE
                if (na != nb) return@Comparator na.compareTo(nb)
                i = ia
                j = jb
            } else {
                if (ca != cb) return@Comparator ca.compareTo(cb)
                i++
                j++
            }
        }
        (a.length - i).compareTo(b.length - j)
    }

    /** Fixes real-world XHTML: bad comments and unescaped `<` / `&`. */
    internal fun sanitizeXml(xml: String): String {
        val comment = Regex("(?s)<!--.*?-->")
        val tag = Regex("<(?!/?[A-Za-z_][A-Za-z0-9_.:-]*(?:\\s[^<>]*)?/?>|\\?[^<>]*\\?>|![^<>]*>)")
        val amp = Regex("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9A-Fa-f]+);)")
        return xml.replace(comment, "").replace(tag, "&lt;").replace(amp, "&amp;")
    }

    private fun parseXhtml(rawXml: String, baseDir: String): Page {
        val blocks = ArrayList<Block>()
        val buf = StringBuilder()
        var heading = 0
        var skip = 0
        var textChars = 0

        fun flush() {
            // Keep <br>/line breaks; collapse only spaces/tabs and extra blank lines.
            val t = buf.toString()
                .replace(Regex("[\\t ]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{2,}"), "\n")
                .trim()
            if (t.isNotEmpty()) blocks += Block.Text(t, heading)
            buf.clear()
            heading = 0
        }

        try {
            val p = Xml.newPullParser()
            runCatching { p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
            p.setInput(StringReader(sanitizeXml(rawXml)))
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (p.localName()) {
                        "head", "style", "script", "desc" -> skip++
                        "rt", "rp" -> skip++
                        "br" -> buf.append('\n')
                        "p", "div", "li", "blockquote", "section", "article" -> flush()
                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            flush()
                            heading = p.localName().last().digitToIntOrNull() ?: 1
                        }
                        "img" -> {
                            flush()
                            p.attr("src")?.let { blocks += Block.Image(resolve(baseDir, it)) }
                        }
                        "image" -> {
                            flush()
                            val h = p.attr("href") ?: p.attr("xlink:href")
                            h?.let { blocks += Block.Image(resolve(baseDir, it)) }
                        }
                    }
                    XmlPullParser.TEXT -> if (skip == 0) {
                        // Pretty-printed XHTML puts newlines/indentation between <rb>/<span>
                        // inline pieces. Trim segment edges so "一" "日" "目" stay together;
                        // keep real internal spaces, and keep a lone space between inline tags.
                        val raw = p.text ?: ""
                        if (raw.isNotBlank()) {
                            val t = raw.trim().replace(Regex("\\s+"), " ")
                            buf.append(t)
                            textChars += t.length
                        } else if (raw == " " || raw == "\u00A0") {
                            buf.append(' ')
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.localName()) {
                        "head", "style", "script", "desc", "rt", "rp" -> if (skip > 0) skip--
                        "p", "div", "li", "blockquote", "section", "article",
                        "h1", "h2", "h3", "h4", "h5", "h6" -> flush()
                    }
                }
                e = p.next()
            }
            flush()
        } catch (t: Throwable) {
            Log.d(TAG, "XHTML parse fallback: ${t.javaClass.simpleName}: ${t.message}")
            return fallbackParse(rawXml, baseDir)
        }
        val hasImage = blocks.any { it is Block.Image }
        // Cover pages often carry <desc>URL</desc>-like short text; image + <200 chars = image page.
        return Page(blocks, imageOnly = hasImage && textChars < 200)
    }

    private fun fallbackParse(rawXml: String, baseDir: String): Page {
        val images = ArrayList<Block>()
        Regex("<img\\b[^>]*\\bsrc=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(rawXml).forEach { images += Block.Image(resolve(baseDir, it.groupValues[1])) }
        Regex("<image\\b[^>]*(?:xlink:href|href)=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(rawXml).forEach { images += Block.Image(resolve(baseDir, it.groupValues[1])) }
        var text = rawXml
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n\n")
        text = text.replace(Regex("<[^>]+>"), "")
        text = text.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        text = text.replace(Regex("[\\t ]+"), " ").replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{2,}"), "\n").trim()
        val blocks = ArrayList<Block>()
        if (text.isNotEmpty()) blocks += Block.Text(text)
        blocks += images
        return Page(blocks, imageOnly = blocks.isNotEmpty() &&
            blocks.all { it is Block.Image } && text.length < 200)
    }
}
