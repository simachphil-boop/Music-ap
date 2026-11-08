package app.vesper.player.data.transfer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/** One line of someone's music list: who and what. */
data class ImportedTrack(val artist: String, val title: String, val durationSec: Int = 0, val cover: String? = null)

/**
 * Reads a list of tracks from whatever the user has: a plain text list ("Исполнитель — Название"),
 * a CSV/TSV table exported by a playlist service or spreadsheet, or a JSON data export
 * (e.g. the archive Yandex ID gives on a "download my data" request).
 */
object TrackListParser {

    fun parse(text: String, fileName: String = ""): List<ImportedTrack> {
        val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        val result = when {
            fileName.endsWith(".json", true) || trimmed.startsWith("{") || trimmed.startsWith("[") ->
                runCatching { json(trimmed) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: lines(trimmed)
            looksLikeTable(trimmed) -> table(trimmed).takeIf { it.isNotEmpty() } ?: lines(trimmed)
            else -> lines(trimmed)
        }
        return result.distinctBy { key(it.artist) + "|" + key(it.title) }
    }

    // ---- plain text --------------------------------------------------------------------------

    private val numbering = Regex("""^\s*(\d{1,4}[.)]\s+|[-•*]\s+)""")
    private val trailingTime = Regex("""\s*[(\[]?\b(\d{1,2}):(\d{2})\b[)\]]?\s*$""")
    private val dashes = listOf(" — ", " – ", " - ", " — ", "—", " -- ")

    fun lines(text: String): List<ImportedTrack> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line(it) }
        .toList()

    fun line(raw: String): ImportedTrack? {
        var s = raw.replace(numbering, "").trim()
        var duration = 0
        trailingTime.find(s)?.let { m ->
            duration = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            s = s.removeRange(m.range).trim()
        }
        if (s.isEmpty() || s.length > 300) return null
        for (d in dashes) {
            val i = s.indexOf(d)
            if (i > 0 && i < s.length - d.length) {
                val artist = s.substring(0, i).trim()
                val title = s.substring(i + d.length).trim()
                if (artist.isNotEmpty() && title.isNotEmpty()) return ImportedTrack(artist, title, duration)
            }
        }
        Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(s)?.let {
            return ImportedTrack(it.groupValues[2].trim(), it.groupValues[1].trim(), duration)
        }
        // Only a title — still useful for searching.
        return ImportedTrack("", s, duration)
    }

    // ---- CSV / TSV ---------------------------------------------------------------------------

    private fun looksLikeTable(text: String): Boolean {
        val first = text.lineSequence().firstOrNull().orEmpty().lowercase()
        val sep = separatorOf(first) ?: return false
        val cols = splitRow(first, sep).map { it.trim().lowercase() }
        return cols.any { it in TITLE_KEYS } && cols.size >= 2
    }

    private fun separatorOf(line: String): Char? = listOf('\t', ';', ',').maxByOrNull { c -> line.count { it == c } }?.takeIf { c -> line.count { it == c } > 0 }

    fun table(text: String): List<ImportedTrack> {
        val rows = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (rows.size < 2) return emptyList()
        val sep = separatorOf(rows[0]) ?: return emptyList()
        val header = splitRow(rows[0], sep).map { it.trim().lowercase() }
        val ti = header.indexOfFirst { it in TITLE_KEYS }
        val ai = header.indexOfFirst { it in ARTIST_KEYS }
        val di = header.indexOfFirst { it in DURATION_KEYS }
        if (ti < 0) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            val cells = splitRow(row, sep)
            val title = cells.getOrNull(ti)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artist = if (ai >= 0) cells.getOrNull(ai)?.trim().orEmpty() else ""
            ImportedTrack(artist, title, if (di >= 0) parseDuration(cells.getOrNull(di).orEmpty()) else 0)
        }
    }

    /** CSV row with "quoted, values" and "" escapes. */
    fun splitRow(row: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < row.length) {
            val ch = row[i]
            when {
                ch == '"' && quoted && i + 1 < row.length && row[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun parseDuration(v: String): Int {
        val t = v.trim()
        if (t.contains(':')) return t.split(':').fold(0) { acc, p -> acc * 60 + (p.trim().toIntOrNull() ?: 0) }
        val n = t.toLongOrNull() ?: return 0
        return if (n > 10_000) (n / 1000).toInt() else n.toInt() // milliseconds or seconds
    }

    // ---- JSON --------------------------------------------------------------------------------

    fun json(text: String): List<ImportedTrack> {
        val out = ArrayList<ImportedTrack>()
        walk(JSONTokener(text).nextValue(), out)
        return out
    }

    private fun walk(node: Any?, out: MutableList<ImportedTrack>) {
        when (node) {
            is JSONObject -> {
                trackOf(node)?.let { out += it; return }
                node.keys().forEach { walk(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)
        }
    }

    private fun trackOf(o: JSONObject): ImportedTrack? {
        val title = sequenceOf("title", "name", "trackName", "track_title", "track").mapNotNull { k ->
            o.opt(k)?.let { it as? String }?.takeIf { it.isNotBlank() }
        }.firstOrNull() ?: return null
        val artist = when (val a = o.opt("artists") ?: o.opt("artist") ?: o.opt("artistName") ?: o.opt("performer")) {
            is String -> a
            is JSONArray -> (0 until a.length()).mapNotNull { i ->
                when (val x = a.opt(i)) {
                    is JSONObject -> x.optString("name").takeIf { it.isNotBlank() }
                    is String -> x
                    else -> null
                }
            }.joinToString(", ")
            is JSONObject -> a.optString("name")
            else -> ""
        }
        // An object with a name but no artist and no duration is probably a playlist/album, not a track.
        val duration = when {
            o.has("durationMs") -> (o.optLong("durationMs") / 1000).toInt()
            o.has("duration_ms") -> (o.optLong("duration_ms") / 1000).toInt()
            o.has("duration") -> parseDuration(o.optString("duration"))
            else -> 0
        }
        if (artist.isBlank() && duration == 0) return null
        return ImportedTrack(artist, title, duration)
    }

    private val TITLE_KEYS = setOf("title", "track", "track name", "trackname", "name", "song", "название", "трек", "песня", "композиция")
    private val ARTIST_KEYS = setOf("artist", "artists", "artist name", "artist name(s)", "performer", "исполнитель", "исполнители", "артист")
    private val DURATION_KEYS = setOf("duration", "duration (ms)", "length", "time", "длительность", "время")

    /** Lower-case, no accents, no brackets like "(Remastered 2011)", only letters and digits. */
    fun key(s: String): String {
        val noBrackets = s.lowercase().replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
        val plain = Normalizer.normalize(noBrackets, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").replace('ё', 'е')
        return plain.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    }
}

/** How well a search result fits the wanted track: 0..1. */
object TrackMatcher {
    const val SURE = 0.72
    const val MAYBE = 0.45

    fun score(want: ImportedTrack, title: String, artist: String, durationSec: Int): Double {
        val wt = tokens(want.title)
        val ht = tokens(title)
        val wa = tokens(want.artist)
        val ha = tokens(artist) + ht // artist names often sit in YouTube titles
        val titleSim = overlap(wt, ht)
        val artistSim = if (wa.isEmpty()) 0.6 else overlap(wa, ha)
        val dur = if (want.durationSec > 0 && durationSec > 0) {
            val d = abs(want.durationSec - durationSec)
            when {
                d <= 3 -> 1.0
                d <= 10 -> 0.7
                d <= 30 -> 0.3
                else -> 0.0
            }
        } else 0.5
        // Live, karaoke, covers etc. are a different recording unless asked for.
        val extra = ht - wt - wa
        val penalty = if (extra.any { it in SUSPICIOUS }) 0.25 else 0.0
        return (0.55 * titleSim + 0.33 * artistSim + 0.12 * dur - penalty).coerceIn(0.0, 1.0)
    }

    private fun tokens(s: String): Set<String> = TrackListParser.key(s).split(' ').filter { it.isNotBlank() && it !in NOISE }.toSet()

    /** Share of the wanted words that are present (with a small bonus for exact sets). */
    private fun overlap(want: Set<String>, have: Set<String>): Double {
        if (want.isEmpty()) return 0.0
        val hit = want.count { w -> have.any { h -> h == w || (w.length >= 4 && (h.startsWith(w) || w.startsWith(h))) } }
        val base = hit.toDouble() / want.size
        return if (base == 1.0 && have.size <= want.size + 3) 1.0 else base * 0.95 + 0.05 * (hit.toDouble() / max(have.size, 1))
    }

    private val NOISE = setOf("feat", "ft", "official", "video", "audio", "lyrics", "lyric", "music", "hd", "hq", "4k", "клип", "премьера", "the", "a")
    private val SUSPICIOUS = setOf("live", "karaoke", "cover", "remix", "instrumental", "минус", "караоке", "кавер", "slowed", "sped", "nightcore", "reverb", "8d")
}
