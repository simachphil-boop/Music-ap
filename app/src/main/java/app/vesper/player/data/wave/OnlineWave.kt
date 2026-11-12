package app.vesper.player.data.wave

import android.content.SharedPreferences
import app.vesper.player.data.db.VesperDatabase
import app.vesper.player.data.download.SearchResult
import app.vesper.player.data.search.WebSearch
import app.vesper.player.playback.ONLINE_ID_PREFIX
import app.vesper.player.playback.OnlineItem
import app.vesper.player.playback.YouTubeFast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Where the online Wave gets its music — the YouTube Music catalogue, asked directly (each
 * request takes about half a second; the old way started yt-dlp for every search).
 *
 * "Моя волна" mixes two sources, roughly three to one:
 *  - taste: the "radio" YouTube Music builds around songs you listened to the end, liked or
 *    downloaded — music with a similar sound that people with the same taste play;
 *  - popular: what is in the charts right now.
 * Songs you skip in the first seconds push their artist out of the session; banned songs and
 * what the Wave played recently don't come back. The last selection is kept on the phone, so the
 * next start plays at once and fresh music is fetched while the first song is already on.
 */
class OnlineWave(
    private val prefs: SharedPreferences,
    private val db: VesperDatabase,
    private val web: WebSearch,
) {
    data class Candidate(val item: OnlineItem, val popular: Boolean)

    private val lock = Mutex()
    private val pool = ArrayList<Candidate>()
    private var poolVibe: String? = null
    private val sessionSeen = HashSet<String>()
    private val sessionSeeds = ArrayDeque<String>()
    private val skippedArtists = HashMap<String, Int>()
    private val lastArtists = ArrayDeque<String>()
    private var cursor = (System.currentTimeMillis() % 1000).toInt()

    /** Called when a wave starts: forgets the session and loads the saved selection for an instant start. */
    suspend fun reset(vibe: Vibe) = lock.withLock {
        pool.clear()
        sessionSeen.clear()
        skippedArtists.clear()
        lastArtists.clear()
        poolVibe = vibe.id
        if (vibe.id == Vibes.myWave.id) pool += loadCache()
    }

    /** First song of the saved selection, if any — lets the app prepare its stream ahead of time. */
    fun peekSaved(): OnlineItem? = runCatching { loadCache().firstOrNull()?.item }.getOrNull()

    /** True when [pick] can answer without going to the network. */
    suspend fun hasReady(count: Int): Boolean = lock.withLock { pool.size >= count }

    suspend fun pick(vibe: Vibe, count: Int, exclude: Set<String>): List<OnlineItem> = lock.withLock {
        if (poolVibe != vibe.id) {
            pool.clear()
            poolVibe = vibe.id
        }
        pool.removeAll { it.item.pageUrl in exclude || it.item.pageUrl in sessionSeen }
        if (pool.size < count) refill(vibe, exclude)
        val out = ArrayList<OnlineItem>(count)
        val iter = pool.iterator()
        while (iter.hasNext() && out.size < count) {
            val c = iter.next()
            val artist = c.item.artist.lowercase()
            // No artist twice in a row (unless there is nothing else to play).
            if (pool.size > count * 2 && artist.isNotBlank() && artist in lastArtists) continue
            out += c.item
            iter.remove()
            sessionSeen += c.item.pageUrl
            lastArtists.addLast(artist)
            while (lastArtists.size > 3) lastArtists.removeFirst()
        }
        if (out.isNotEmpty()) {
            remember(KEY_HISTORY, out.map { it.pageUrl }, HISTORY_LIMIT)
            if (vibe.id == Vibes.myWave.id) saveCache()
        }
        out
    }

    /** Fetches more music in the background (after an instant start from the saved selection). */
    suspend fun refresh(vibe: Vibe, exclude: Set<String>) = lock.withLock {
        if (poolVibe == vibe.id) {
            refill(vibe, exclude)
            if (vibe.id == Vibes.myWave.id) saveCache()
        }
    }

    /** The listener heard most of a song: it becomes a seed for what comes next. */
    fun onCompleted(pageUrl: String) {
        if (YouTubeFast.videoId(pageUrl) == null) return
        sessionSeeds.remove(pageUrl)
        sessionSeeds.addFirst(pageUrl)
        while (sessionSeeds.size > 8) sessionSeeds.removeLast()
        remember(KEY_SEEDS, listOf(pageUrl), SEEDS_LIMIT)
    }

    /** Skipped within seconds: after two such skips the artist is left out for this session. */
    suspend fun onSkipped(artist: String) = lock.withLock {
        val a = artist.trim().lowercase()
        if (a.isBlank()) return@withLock
        val n = (skippedArtists[a] ?: 0) + 1
        skippedArtists[a] = n
        if (n >= 2) pool.removeAll { it.item.artist.lowercase() == a }
    }

    fun onLiked(pageUrl: String) = onCompleted(pageUrl)

    fun forget() {
        prefs.edit().remove(KEY_SEEDS).remove(KEY_HISTORY).remove(KEY_CACHE).apply()
        sessionSeeds.clear()
    }

    // ---- fetching ------------------------------------------------------------------------------

    private suspend fun refill(vibe: Vibe, exclude: Set<String>) {
        val banned = prefs.getStringSet(KEY_BANNED, emptySet()).orEmpty()
        val history = list(KEY_HISTORY).toHashSet()
        val longForm = vibe.id in setOf("focus", "sleep", "relax")
        val maxDuration = if (longForm) 1800 else 600
        val have = pool.map { it.item.pageUrl }.toHashSet()

        fun usable(r: SearchResult, avoidHistory: Boolean): Boolean =
            (r.durationSec == 0 || r.durationSec in 90..maxDuration) &&
                (longForm || !isCompilation(r.title)) &&
                r.url !in banned && r.url !in exclude && r.url !in sessionSeen && r.url !in have &&
                (!avoidHistory || r.url !in history) &&
                (skippedArtists[artistOf(r).lowercase()] ?: 0) < 2

        var taste: List<SearchResult> = emptyList()
        var popular: List<SearchResult> = emptyList()
        coroutineScope {
            when (vibe.strategy) {
                VibeStrategy.TASTE, VibeStrategy.DISCOVERY -> {
                    val seeds = tasteSeeds()
                    val radios = seeds.map { async { web.radio(it) } }
                    val artists = if (seeds.size < 2) likedArtists().shuffled().take(2 - seeds.size) else emptyList()
                    val byArtist = artists.map { async { web.songsFast(it) } }
                    val hits = async { web.songsFast(nextOf(popularQueries())) }
                    taste = (radios.awaitAll().flatten() + byArtist.awaitAll().flatten()).shuffled()
                    popular = hits.await()
                    if (vibe.strategy == VibeStrategy.DISCOVERY) {
                        val known = knownArtists()
                        taste = taste.filter { artistOf(it).lowercase() !in known }
                        popular = popular.filter { artistOf(it).lowercase() !in known }
                    }
                }
                VibeStrategy.FAVORITES -> {
                    val artists = (likedArtists() + topArtists()).distinctBy { it.lowercase() }
                    val chosen = List(minOf(2, artists.size)) { nextOf(artists) }.distinct()
                    taste = chosen.map { async { web.songsFast(it) } }.awaitAll().flatten().shuffled()
                    if (taste.isEmpty()) popular = web.songsFast(nextOf(popularQueries()))
                }
                else -> {
                    val queries = Vibes.onlineQueries(vibe)
                    val chosen = List(minOf(2, queries.size)) { nextOf(queries) }.distinct()
                    taste = chosen.map { async { web.songsFast(it) } }.awaitAll().flatten().shuffled()
                }
            }
        }
        // Prefer what the Wave hasn't played lately; if that leaves too little, repeats are allowed.
        var t = taste.distinctBy { it.url }.filter { usable(it, avoidHistory = true) }
        var p = popular.distinctBy { it.url }.filter { usable(it, avoidHistory = true) }
        if (t.size + p.size < 8) {
            t = taste.distinctBy { it.url }.filter { usable(it, avoidHistory = false) }
            p = popular.distinctBy { it.url }.filter { usable(it, avoidHistory = false) }
        }
        pool += interleave(t.map { Candidate(it.toOnline(), false) }, p.shuffled().map { Candidate(it.toOnline(), true) })
            .distinctBy { it.item.pageUrl }
    }

    /** Up to two songs to build radio from: the freshest of this session plus one from earlier days. */
    private suspend fun tasteSeeds(): List<String> {
        val out = ArrayList<String>()
        sessionSeeds.firstOrNull()?.let { out += it }
        val saved = list(KEY_SEEDS)
        val listened = runCatching {
            db.listens().recent(200).filter { it.online && it.listenedMs >= 60_000 }
                .map { it.mediaKey.removePrefix(ONLINE_ID_PREFIX) }
                .filter { YouTubeFast.videoId(it) != null }
        }.getOrDefault(emptyList())
        val older = (saved + listened).distinct().filter { it !in out }
        if (older.isNotEmpty()) out += older.shuffled().first()
        if (out.size < 2) {
            // Nothing heard online yet: find a favourite from the phone in the catalogue and start from it.
            val local = runCatching {
                db.tracks().getAll().filter { (it.favorite || it.playCount > 2) && !it.artist.startsWith("<") && !it.artist.startsWith("Неизвестн") }
                    .sortedByDescending { it.playCount + if (it.favorite) 5 else 0 }.take(12)
            }.getOrDefault(emptyList())
            if (local.isNotEmpty()) {
                val t = local.random()
                web.songsFast("${t.artist} ${t.title}").firstOrNull()?.url?.takeIf { it !in out }?.let { out += it }
            }
        }
        return out.take(2)
    }

    private fun likedArtists(): List<String> = prefs.getString(KEY_LIKED, "").orEmpty().lines().filter { it.isNotBlank() }

    private suspend fun topArtists(): List<String> = runCatching {
        val since = System.currentTimeMillis() - 90L * 86_400_000
        val heard = db.listens().topArtists(since, 12).map { it.artist }
        val local = db.tracks().getAll().filter { it.favorite || it.playCount > 2 }.groupBy { it.artist }
            .entries.sortedByDescending { it.value.size }.map { it.key }.take(8)
        (heard + local).filter { it.isNotBlank() && !it.startsWith("<") && !it.startsWith("Неизвестн") && it != "Онлайн" }
    }.getOrDefault(emptyList())

    private suspend fun knownArtists(): Set<String> =
        (likedArtists() + topArtists() + runCatching { db.tracks().getAll().map { it.artist } }.getOrDefault(emptyList()))
            .map { it.lowercase() }.toHashSet()

    private fun popularQueries(): List<String> {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return listOf("хиты $year", "топ песни $year", "популярные песни сейчас", "top hits $year", "новинки $year хиты", "чарт $year", "лучшие песни $year")
    }

    private fun <T> nextOf(list: List<T>): T = list[Math.floorMod(cursor++, list.size)]

    // ---- small storage -------------------------------------------------------------------------

    private fun list(key: String): List<String> = prefs.getString(key, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun remember(key: String, urls: List<String>, limit: Int) {
        val merged = (urls + list(key).filter { it !in urls }).take(limit)
        prefs.edit().putString(key, merged.joinToString("\n")).apply()
    }

    private fun saveCache() {
        val arr = JSONArray()
        pool.take(40).forEach { c ->
            arr.put(
                JSONObject().put("u", c.item.pageUrl).put("t", c.item.title).put("a", c.item.artist)
                    .put("d", c.item.durationSec).put("th", c.item.thumbnail ?: "").put("p", c.popular),
            )
        }
        prefs.edit().putString(KEY_CACHE, JSONObject().put("at", System.currentTimeMillis()).put("items", arr).toString()).apply()
    }

    private fun loadCache(): List<Candidate> = runCatching {
        val o = JSONObject(prefs.getString(KEY_CACHE, null) ?: return emptyList())
        if (System.currentTimeMillis() - o.optLong("at") > CACHE_TTL_MS) return emptyList()
        val banned = prefs.getStringSet(KEY_BANNED, emptySet()).orEmpty()
        val arr = o.getJSONArray("items")
        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("u") !in banned }.map {
            Candidate(OnlineItem(it.getString("u"), it.optString("t"), it.optString("a"), it.optInt("d"), it.optString("th").ifBlank { null }), it.optBoolean("p"))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val KEY_BANNED = "banned_urls"
        const val KEY_LIKED = "liked_artists"
        private const val KEY_SEEDS = "wave_seeds"
        private const val KEY_HISTORY = "wave_history"
        private const val KEY_CACHE = "wave_cache"
        private const val SEEDS_LIMIT = 30
        private const val HISTORY_LIMIT = 300
        private const val CACHE_TTL_MS = 5L * 86_400_000

        fun artistOf(r: SearchResult): String = r.author.substringBefore(" · ").trim()

        fun SearchResult.toOnline() = OnlineItem(url, title, artistOf(this), durationSec, thumbnail)

        /** Three songs by taste, then one popular; whatever is left of either goes to the end. */
        fun <T> interleave(taste: List<T>, popular: List<T>): List<T> {
            if (taste.isEmpty()) return popular
            val out = ArrayList<T>(taste.size + popular.size)
            var p = 0
            taste.forEachIndexed { i, t ->
                out += t
                if (i % 3 == 2 && p < popular.size) out += popular[p++]
            }
            while (p < popular.size) out += popular[p++]
            return out
        }

        fun isCompilation(title: String): Boolean {
            val t = title.lowercase()
            // "mix" as a word of its own — a "Remix" is an ordinary song.
            if (Regex("""(?<![a-zа-яё])(mix|микс)""").containsMatchIn(t)) return true
            return listOf("playlist", "плейлист", "full album", "сборник", "подборка", "compilation", "1 hour", "1 час", "hours", "часа", "top 10", "топ 10", "non-stop", "нон-стоп")
                .any { t.contains(it) }
        }
    }
}
