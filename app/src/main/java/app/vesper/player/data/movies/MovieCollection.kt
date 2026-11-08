package app.vesper.player.data.movies

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Movie(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val kinopoiskId: Long? = null,
    val poster: String? = null,
    val description: String? = null,
    val wikiUrl: String? = null,
    val watchlist: Boolean = true,
    val favorite: Boolean = false,
    val watched: Boolean = false,
    val enriched: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
) {
    /** The film's page on Kinopoisk if its id is known, otherwise a Kinopoisk search. */
    val kinopoiskUrl: String
        get() = kinopoiskId?.let { "https://www.kinopoisk.ru/film/$it/" }
            ?: ("https://www.kinopoisk.ru/index.php?kp_query=" + URLEncoder.encode(listOfNotNull(title, year?.toString()).joinToString(" "), "UTF-8"))

    /** Watching in the official online cinema (needs the user's own subscription there). */
    val watchUrl: String?
        get() = kinopoiskId?.let { "https://hd.kinopoisk.ru/film/$it" }

    val trailerSearchUrl: String
        get() = "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$title ${year ?: ""} трейлер", "UTF-8")
}

/**
 * «Моя коллекция Кинопоиска»: the user's personal film list inside Floya — want to watch,
 * favourites, watched. Films are imported from a list/export the user has, and filled in with
 * posters and descriptions from Wikipedia/Wikidata (open sources). The films themselves are
 * never downloaded: each one opens in the official service.
 */
class MovieCollection(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "movies.json")
    private val lock = Mutex()
    private val enrichGate = Mutex()
    private val _movies = MutableStateFlow<List<Movie>>(emptyList())
    val movies: StateFlow<List<Movie>> = _movies.asStateFlow()
    private val _enriching = MutableStateFlow(0)
    /** How many films are still being looked up. */
    val enriching: StateFlow<Int> = _enriching.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            _movies.value = load()
            _movies.value.filter { !it.enriched }.forEach { enrichLater(it.id) }
        }
    }

    /** Adds films from a list; returns how many were new. */
    fun import(list: List<ImportedMovie>, watched: Boolean = false): Int {
        var added = 0
        val now = System.currentTimeMillis()
        val have = _movies.value
        val fresh = list.mapIndexedNotNull { i, m ->
            val dup = have.any { h ->
                (m.kinopoiskId != null && h.kinopoiskId == m.kinopoiskId) ||
                    (m.title.isNotBlank() && h.title.equals(m.title, true) && (m.year == null || h.year == null || h.year == m.year))
            }
            if (dup) null else Movie(now * 1000 + i, m.title, m.year, m.kinopoiskId, watchlist = !watched, watched = watched, addedAt = now).also { added++ }
        }
        if (fresh.isEmpty()) return 0
        _movies.update { fresh + it }
        save()
        fresh.forEach { enrichLater(it.id) }
        return added
    }

    fun update(id: Long, f: (Movie) -> Movie) {
        _movies.update { list -> list.map { if (it.id == id) f(it) else it } }
        save()
    }

    fun remove(id: Long) {
        _movies.update { list -> list.filter { it.id != id } }
        save()
    }

    fun refresh(id: Long) {
        update(id) { it.copy(enriched = false) }
        enrichLater(id)
    }

    private fun enrichLater(id: Long) {
        _enriching.update { it + 1 }
        scope.launch(Dispatchers.IO) {
            try {
                enrichGate.withLock {
                    val m = _movies.value.firstOrNull { it.id == id } ?: return@withLock
                    val info = runCatching { MovieInfo.lookup(m) }.getOrNull()
                    update(id) { cur ->
                        if (info == null) cur.copy(enriched = true)
                        else cur.copy(
                            title = cur.title.ifBlank { info.title },
                            year = cur.year ?: info.year,
                            poster = info.poster ?: cur.poster,
                            description = info.description ?: cur.description,
                            wikiUrl = info.wikiUrl ?: cur.wikiUrl,
                            kinopoiskId = cur.kinopoiskId ?: info.kinopoiskId,
                            enriched = true,
                        )
                    }
                }
            } finally {
                _enriching.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }


    private fun load(): List<Movie> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Movie(
                id = o.getLong("id"),
                title = o.optString("title"),
                year = o.optInt("year").takeIf { it > 0 },
                kinopoiskId = o.optLong("kp").takeIf { it > 0 },
                poster = o.optString("poster").ifBlank { null },
                description = o.optString("desc").ifBlank { null },
                wikiUrl = o.optString("wiki").ifBlank { null },
                watchlist = o.optBoolean("wl", true),
                favorite = o.optBoolean("fav"),
                watched = o.optBoolean("seen"),
                enriched = o.optBoolean("enriched"),
                addedAt = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val snapshot = _movies.value
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val arr = JSONArray()
                snapshot.forEach { m ->
                    arr.put(
                        JSONObject().put("id", m.id).put("title", m.title).put("year", m.year ?: 0).put("kp", m.kinopoiskId ?: 0)
                            .put("poster", m.poster ?: "").put("desc", m.description ?: "").put("wiki", m.wikiUrl ?: "")
                            .put("wl", m.watchlist).put("fav", m.favorite).put("seen", m.watched).put("enriched", m.enriched).put("at", m.addedAt),
                    )
                }
                val tmp = File(file.path + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }
}

/** Film facts from open sources: Russian Wikipedia (description, poster) and Wikidata (Kinopoisk id, title). */
object MovieInfo {
    data class Info(val title: String, val year: Int?, val poster: String?, val description: String?, val wikiUrl: String?, val kinopoiskId: Long?)

    private const val UA = "FloyaMusic/2.2 (Android; https://github.com/simachphil-boop/Music-ap)"

    fun lookup(m: Movie): Info? {
        // Known Kinopoisk id → exact film via Wikidata (property P2603).
        val byId = m.kinopoiskId?.let { wikidataByKinopoisk(it) }
        val pageTitle = byId?.second ?: searchWikipedia(m.title, m.year) ?: return byId?.let { Info(it.first, m.year, null, null, null, m.kinopoiskId) }
        val summary = JSONObject(get("https://ru.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(pageTitle.replace(' ', '_'), "UTF-8")))
        val qid = summary.optString("wikibase_item").ifBlank { null }
        val kp = m.kinopoiskId ?: qid?.let { kinopoiskOf(it) }
        val desc = summary.optString("extract").ifBlank { null }
        val year = m.year ?: desc?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        return Info(
            title = byId?.first ?: summary.optString("title").substringBefore(" (").ifBlank { m.title },
            year = year,
            poster = summary.optJSONObject("originalimage")?.optString("source") ?: summary.optJSONObject("thumbnail")?.optString("source"),
            description = desc,
            wikiUrl = summary.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page"),
            kinopoiskId = kp,
        )
    }

    private fun searchWikipedia(title: String, year: Int?): String? {
        if (title.isBlank()) return null
        val q = URLEncoder.encode(listOfNotNull(title, year?.toString(), "фильм").joinToString(" "), "UTF-8")
        val r = JSONObject(get("https://ru.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=$q"))
        val hits = r.optJSONObject("query")?.optJSONArray("search") ?: return null
        val titles = (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optString("title") }
        // Prefer pages that look like a film article: "Название (фильм, 1997)".
        return titles.firstOrNull { it.contains("фильм", true) && (year == null || it.contains(year.toString())) }
            ?: titles.firstOrNull { it.contains("фильм", true) || it.contains("сериал", true) }
            ?: titles.firstOrNull()
    }

    /** (Russian title, Wikipedia page) for a Kinopoisk id. */
    private fun wikidataByKinopoisk(kp: Long): Pair<String, String?>? {
        val sparql = "SELECT ?item ?label ?article WHERE { ?item wdt:P2603 \"$kp\". " +
            "OPTIONAL { ?item rdfs:label ?label FILTER(LANG(?label) = \"ru\") } " +
            "OPTIONAL { ?article schema:about ?item; schema:isPartOf <https://ru.wikipedia.org/> } } LIMIT 1"
        val r = JSONObject(get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(sparql, "UTF-8")))
        val b = r.optJSONObject("results")?.optJSONArray("bindings")?.optJSONObject(0) ?: return null
        val label = b.optJSONObject("label")?.optString("value").orEmpty()
        val article = b.optJSONObject("article")?.optString("value")?.substringAfterLast("/wiki/")?.let { java.net.URLDecoder.decode(it, "UTF-8").replace('_', ' ') }
        return label to article
    }

    private fun kinopoiskOf(qid: String): Long? = runCatching {
        val r = JSONObject(get("https://www.wikidata.org/wiki/Special:EntityData/$qid.json"))
        r.getJSONObject("entities").getJSONObject(qid).getJSONObject("claims").getJSONArray("P2603")
            .getJSONObject(0).getJSONObject("mainsnak").getJSONObject("datavalue").getString("value").toLong()
    }.getOrNull()

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
