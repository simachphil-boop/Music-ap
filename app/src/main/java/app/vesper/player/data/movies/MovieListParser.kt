package app.vesper.player.data.movies

import app.vesper.player.data.transfer.TrackListParser
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A film as it appears in someone's list. */
data class ImportedMovie(val title: String, val year: Int? = null, val kinopoiskId: Long? = null)

/**
 * Reads a list of films: plain text ("Брат (1997)", "Interstellar, 2014", Kinopoisk links),
 * CSV/TSV tables with title/year columns, or a JSON data export.
 */
object MovieListParser {
    private val kpLink = Regex("""kinopoisk\.ru/(?:film|series)/(\d+)""")
    private val yearInBrackets = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val yearAtEnd = Regex("""[,;\-–—]\s*((?:19|20)\d{2})\s*$""")
    private val numbering = Regex("""^\s*(\d{1,4}[.)]\s+|[-•*]\s+)""")

    fun parse(text: String, fileName: String = ""): List<ImportedMovie> {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        val list = when {
            fileName.endsWith(".json", true) || t.startsWith("{") || t.startsWith("[") ->
                runCatching { json(t) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: lines(t)
            isTable(t) -> table(t)
            else -> lines(t)
        }
        return list.distinctBy { it.kinopoiskId?.toString() ?: (TrackListParser.key(it.title) + (it.year ?: "")) }
    }

    fun lines(text: String): List<ImportedMovie> = text.lineSequence().mapNotNull { line(it) }.toList()

    fun line(raw: String): ImportedMovie? {
        var s = raw.trim().replace(numbering, "").trim()
        if (s.isEmpty() || s.startsWith("#")) return null
        val kp = kpLink.find(s)?.groupValues?.get(1)?.toLongOrNull()
        if (kp != null) {
            s = s.replace(Regex("""https?://\S+"""), "").trim(' ', '-', '—', '|', ',')
        }
        var year: Int? = null
        (yearInBrackets.find(s) ?: yearAtEnd.find(s))?.let { m ->
            year = m.groupValues[1].toInt()
            s = s.removeRange(m.range).trim(' ', ',', '-', '—')
        }
        if (s.isEmpty() && kp == null) return null
        return ImportedMovie(s, year, kp)
    }

    private fun isTable(t: String): Boolean {
        val first = t.lineSequence().firstOrNull().orEmpty().lowercase()
        val sep = listOf('\t', ';', ',').maxByOrNull { c -> first.count { it == c } } ?: return false
        if (first.count { it == sep } == 0) return false
        return TrackListParser.splitRow(first, sep).map { it.trim() }.any { it in TITLE_KEYS }
    }

    fun table(t: String): List<ImportedMovie> {
        val rows = t.lineSequence().filter { it.isNotBlank() }.toList()
        val first = rows.first()
        val sep = listOf('\t', ';', ',').maxByOrNull { c -> first.count { it == c } }!!
        val header = TrackListParser.splitRow(first.lowercase(), sep).map { it.trim() }
        val ti = header.indexOfFirst { it in TITLE_KEYS }
        val yi = header.indexOfFirst { it in YEAR_KEYS }
        val li = header.indexOfFirst { it in LINK_KEYS }
        return rows.drop(1).mapNotNull { row ->
            val c = TrackListParser.splitRow(row, sep)
            val title = c.getOrNull(ti)?.trim().orEmpty()
            val kp = if (li >= 0) kpLink.find(c.getOrNull(li).orEmpty())?.groupValues?.get(1)?.toLongOrNull() else null
            if (title.isEmpty() && kp == null) return@mapNotNull null
            val year = if (yi >= 0) c.getOrNull(yi)?.trim()?.take(4)?.toIntOrNull() else null
            ImportedMovie(title, year, kp)
        }
    }

    fun json(t: String): List<ImportedMovie> {
        val out = ArrayList<ImportedMovie>()
        walk(JSONTokener(t).nextValue(), out)
        return out
    }

    private fun walk(node: Any?, out: MutableList<ImportedMovie>) {
        when (node) {
            is JSONObject -> {
                movieOf(node)?.let { out += it; return }
                node.keys().forEach { walk(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)
        }
    }

    private fun movieOf(o: JSONObject): ImportedMovie? {
        val title = sequenceOf("nameRu", "name_ru", "filmName", "film_name", "title", "name", "nameOriginal", "nameEn")
            .mapNotNull { k -> (o.opt(k) as? String)?.takeIf { it.isNotBlank() } }.firstOrNull()
        val kp = sequenceOf("kinopoiskId", "kinopoisk_id", "filmId", "film_id", "kpId").mapNotNull { k -> o.opt(k)?.toString()?.toLongOrNull() }.firstOrNull()
            ?: (o.opt("url") as? String)?.let { kpLink.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val year = sequenceOf("year", "releaseYear", "productionYear").mapNotNull { k -> o.opt(k)?.toString()?.take(4)?.toIntOrNull() }.firstOrNull()
        // Needs something film-like besides a name — avoids treating any named object as a film.
        if (title == null && kp == null) return null
        if (kp == null && year == null) return null
        return ImportedMovie(title.orEmpty(), year, kp)
    }

    private val TITLE_KEYS = setOf("title", "name", "название", "фильм", "film", "movie", "название фильма", "русское название")
    private val YEAR_KEYS = setOf("year", "год", "год выпуска")
    private val LINK_KEYS = setOf("url", "link", "ссылка", "kinopoisk", "кинопоиск")
}
