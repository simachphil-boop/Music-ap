package app.vesper.player.data.wave

import android.content.Context
import app.vesper.player.data.db.MediaSource
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.TrackFeaturesEntity
import app.vesper.player.data.db.VesperDatabase
import app.vesper.player.data.download.Downloader
import app.vesper.player.data.download.SearchResult
import app.vesper.player.data.download.SearchSource
import app.vesper.player.playback.OnlineItem
import app.vesper.player.playback.PlayerConnection
import app.vesper.player.playback.PlayerUiState
import app.vesper.player.playback.QueueEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Where the Wave takes music from. Files that were already on the phone are never used. */
enum class WaveMode(val title: String) {
    ONLINE("Онлайн"),
    DOWNLOADS("Мои загрузки"),
}

data class WaveState(
    val vibe: Vibe? = null,
    val mode: WaveMode = WaveMode.ONLINE,
    val active: Boolean = false,
    val starting: Boolean = false,
    /** Sound profile of the track currently playing (drives the wave animation). */
    val nowProfile: SoundProfile? = null,
)

/**
 * "Wave": an endless, self-extending queue.
 * - ONLINE streams internet music found by mood/activity queries, personalised by the
 *   artists you download and like.
 * - DOWNLOADS ranks tracks downloaded through the app by measured sound and feedback.
 */
class WaveEngine(
    context: Context,
    private val db: VesperDatabase,
    private val player: PlayerConnection,
    private val downloader: Downloader,
    private val scope: CoroutineScope,
) {
    private val dao = db.wave()
    private val prefs = context.getSharedPreferences("floya_wave", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        WaveState(mode = runCatching { WaveMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(WaveMode.ONLINE)),
    )
    val state: StateFlow<WaveState> = _state.asStateFlow()

    // Downloads mode
    private val recentPicks = ArrayDeque<Long>()
    private val recentArtists = ArrayDeque<String>()
    // Online mode
    private val onlineSeen = HashSet<String>()
    private var queries: List<String> = emptyList()
    private var queryCursor = 0

    private var extending = false
    private var profileFor: Long = -1

    init {
        scope.launch { player.state.collect { onPlayerState(it) } }
    }

    fun setMode(mode: WaveMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _state.update { it.copy(mode = mode) }
    }

    private fun onPlayerState(s: PlayerUiState) {
        val active = s.queueOwner == OWNER
        if (active != _state.value.active) _state.update { it.copy(active = active) }
        val currentId = s.current?.trackId ?: -1L
        if (currentId != profileFor) {
            profileFor = currentId
            scope.launch {
                val track = if (currentId > 0) db.tracks().get(currentId) else null
                val profile = track?.let { SoundProfile.from(dao.features(it.id), it) }
                _state.update { it.copy(nowProfile = profile) }
            }
        }
        if (active && !extending && !_state.value.starting && s.queue.isNotEmpty() && s.queue.size - 1 - s.currentIndex <= 2) {
            extending = true
            val vibe = _state.value.vibe ?: Vibes.myWave
            val online = s.queue.any { it.online }
            scope.launch {
                try {
                    if (online) {
                        val exclude = s.queue.mapNotNull { it.pageUrl }.toSet()
                        player.addOnline(pickOnline(vibe, 8, exclude))
                    } else {
                        val picks = pickDownloads(vibe, 10, s.queue.map { it.trackId }.toSet())
                        if (picks.isNotEmpty()) player.addToQueue(picks, silent = true)
                    }
                } finally {
                    extending = false
                }
            }
        }
    }

    /** The preset started most often — shown in statistics. */
    fun favoriteVibe(): Vibe? = Vibes.all.maxByOrNull { prefs.getInt("vibe_count_" + it.id, 0) }
        ?.takeIf { prefs.getInt("vibe_count_" + it.id, 0) > 0 }

    fun start(vibe: Vibe) {
        if (_state.value.starting) return
        prefs.edit().putInt("vibe_count_" + vibe.id, prefs.getInt("vibe_count_" + vibe.id, 0) + 1).apply()
        _state.update { it.copy(starting = true) }
        scope.launch {
            try {
                when (_state.value.mode) {
                    WaveMode.ONLINE -> {
                        onlineSeen.clear()
                        queries = buildQueries(vibe)
                        queryCursor = 0
                        val picks = pickOnline(vibe, 10, emptySet())
                        if (picks.isEmpty()) {
                            player.notify("Не удалось найти музыку — проверьте интернет или включите «Мои загрузки»")
                            return@launch
                        }
                        _state.update { it.copy(vibe = vibe) }
                        player.playOnline(picks, 0, owner = OWNER)
                    }
                    WaveMode.DOWNLOADS -> {
                        recentPicks.clear()
                        recentArtists.clear()
                        val picks = pickDownloads(vibe, 15, emptySet())
                        if (picks.isEmpty()) {
                            player.notify("Скачанной музыки пока нет — скачайте треки во вкладке «Загрузки» или включите «Онлайн»")
                            return@launch
                        }
                        _state.update { it.copy(vibe = vibe) }
                        player.playTracks(picks, 0, shuffle = false, owner = OWNER)
                    }
                }
            } finally {
                _state.update { it.copy(starting = false) }
            }
        }
    }

    /** "Not for me": skip now and never pick again. */
    fun dislike(entry: QueueEntry) {
        val page = entry.pageUrl
        if (page != null) {
            prefs.edit().putStringSet(KEY_BANNED, bannedUrls() + page).apply()
        } else if (entry.trackId > 0) {
            scope.launch {
                dao.ensureStats(entry.trackId)
                dao.setBanned(entry.trackId, true)
            }
        }
        player.next()
        player.notify("Больше не попадётся в волне")
    }

    /** "More like this" for internet tracks: remember the artist for future waves. */
    fun likeOnline(entry: QueueEntry) {
        val artist = entry.artist.trim()
        if (artist.isBlank()) return
        val list = likedArtists().filterNot { it.equals(artist, true) }
        prefs.edit().putString(KEY_LIKED, (listOf(artist) + list).take(40).joinToString("\n")).apply()
        player.notify("Учту: больше похожего на $artist")
    }

    fun isLikedArtist(artist: String): Boolean = likedArtists().any { it.equals(artist.trim(), true) }

    fun resetLearning() {
        scope.launch {
            dao.clearStats()
            prefs.edit().remove(KEY_BANNED).remove(KEY_LIKED).apply()
            player.notify("Обучение волны сброшено")
        }
    }

    private fun bannedUrls(): Set<String> = prefs.getStringSet(KEY_BANNED, emptySet()).orEmpty()
    private fun likedArtists(): List<String> = prefs.getString(KEY_LIKED, "").orEmpty().lines().filter { it.isNotBlank() }

    // ---------------------------------------------------------------------------------------------
    // Online
    // ---------------------------------------------------------------------------------------------

    private suspend fun buildQueries(vibe: Vibe): List<String> {
        val base = Vibes.onlineQueries(vibe)
        val downloaded = db.tracks().getAll().filter { it.source == MediaSource.DOWNLOADED }
        val topArtists = downloaded
            .filter { !it.artist.startsWith("Неизвестн") }
            .groupBy { it.artist }
            .entries.sortedByDescending { (_, list) -> list.sumOf { it.playCount + if (it.favorite) 5 else 0 } + list.size }
            .map { it.key }
            .take(10)
        val seeds = (likedArtists() + topArtists).distinctBy { it.lowercase() }
        val list = when (vibe.strategy) {
            VibeStrategy.TASTE -> seeds.take(12) + base.take(2)
            VibeStrategy.FAVORITES -> seeds.ifEmpty { base }
            else -> base
        }
        return list.ifEmpty { base }.shuffled()
    }

    private suspend fun pickOnline(vibe: Vibe, count: Int, exclude: Set<String>): List<OnlineItem> {
        if (queries.isEmpty()) queries = buildQueries(vibe)
        val banned = bannedUrls()
        val longForm = vibe.id in setOf("focus", "sleep", "relax")
        val maxDuration = if (longForm) 1800 else 600
        val out = ArrayList<OnlineItem>()
        var attempts = 0
        while (out.size < count && attempts < 4) {
            val q = queries[queryCursor % queries.size]
            queryCursor++
            attempts++
            val results = downloader.search(q, SearchSource.YOUTUBE).getOrNull() ?: continue
            val authorsInBatch = HashSet<String>()
            results
                .filter { it.durationSec in 90..maxDuration }
                .filter { longForm || !isCompilation(it.title) }
                .filter { it.url !in onlineSeen && it.url !in banned && it.url !in exclude }
                .shuffled()
                .forEach { r ->
                    if (out.size >= count) return@forEach
                    val author = r.author.lowercase()
                    if (author.isNotBlank() && !authorsInBatch.add(author)) return@forEach
                    out += r.toOnline()
                    onlineSeen += r.url
                }
        }
        return out.shuffled()
    }

    private fun isCompilation(title: String): Boolean {
        val t = title.lowercase()
        return listOf("mix", "микс", "playlist", "плейлист", "full album", "сборник", "подборка", "compilation", "1 hour", "1 час", "hours", "часа", "top 10", "топ 10", "non-stop", "нон-стоп")
            .any { t.contains(it) }
    }

    private fun SearchResult.toOnline() = OnlineItem(url, title, author, durationSec, thumbnail)

    // ---------------------------------------------------------------------------------------------
    // Downloads (local files saved by the app)
    // ---------------------------------------------------------------------------------------------

    private suspend fun pickDownloads(vibe: Vibe, count: Int, exclude: Set<Long>): List<TrackEntity> {
        val tracks = db.tracks().getAll().filter { it.source == MediaSource.DOWNLOADED }
        val features = dao.allFeatures().associateBy { it.trackId }
        val stats = dao.allStats().associateBy { it.trackId }
        return withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val taste = if (vibe.strategy == VibeStrategy.TASTE) tasteProfile(tracks, features) else null

            fun eligible(t: TrackEntity, allowRecent: Boolean) =
                t.durationMs >= 45_000 && t.id !in exclude && stats[t.id]?.banned != true &&
                    (allowRecent || t.id !in recentPicks)

            var pool = tracks.filter { eligible(it, allowRecent = false) }
            if (pool.size < count) {
                recentPicks.clear()
                pool = tracks.filter { eligible(it, allowRecent = true) }
            }

            val scored = pool.mapNotNull { t ->
                val p = SoundProfile.from(features[t.id], t)
                val st = stats[t.id]
                val skips = st?.skips ?: 0
                val completions = st?.completions ?: 0
                val skipRate = skips / (skips + completions + 2f)
                val plays = (ln(1.0 + t.playCount) / ln(30.0)).toFloat().coerceAtMost(1f)
                val pref = (if (t.favorite) 0.6f else 0f) + 0.4f * plays
                val novelty = 1f / (1 + t.playCount)
                val daysSince = if (t.lastPlayedAt > 0) (now - t.lastPlayedAt) / 86_400_000f else -1f

                val score: Float = when (vibe.strategy) {
                    VibeStrategy.TASTE ->
                        0.35f * pref + 0.30f * (taste?.let { similarity(p, it) } ?: 0.5f) + 0.15f * novelty - 0.45f * skipRate
                    VibeStrategy.FAVORITES ->
                        if (!t.favorite && t.playCount < 3) return@mapNotNull null
                        else 0.75f * pref - 0.3f * skipRate
                    VibeStrategy.DISCOVERY ->
                        if (t.playCount > 1) return@mapNotNull null
                        else 0.5f + 0.25f * (taste?.let { similarity(p, it) } ?: 0.5f)
                    VibeStrategy.FORGOTTEN ->
                        if (daysSince < 21f) return@mapNotNull null
                        else 0.45f * pref + 0.3f * (daysSince / 180f).coerceAtMost(1f) - 0.3f * skipRate
                    VibeStrategy.SOUND ->
                        0.65f * Vibes.fit(vibe, p, t) + 0.2f * pref + 0.05f * novelty - 0.4f * skipRate
                }
                t to score + Random.nextFloat() * 0.22f
            }.sortedByDescending { it.second }

            val out = ArrayList<TrackEntity>(count)
            val artists = ArrayDeque(recentArtists)
            for ((t, _) in scored) {
                if (out.size >= count) break
                if (scored.size > count * 2 && artists.takeLast(3).any { it.equals(t.artist, ignoreCase = true) }) continue
                out += t
                artists.addLast(t.artist)
            }
            if (out.size < count) {
                for ((t, _) in scored) {
                    if (out.size >= count) break
                    if (t !in out) out += t
                }
            }
            out.forEach {
                recentPicks.addLast(it.id)
                recentArtists.addLast(it.artist)
            }
            while (recentPicks.size > 120) recentPicks.removeFirst()
            while (recentArtists.size > 6) recentArtists.removeFirst()
            out
        }
    }

    private fun tasteProfile(tracks: List<TrackEntity>, features: Map<Long, TrackFeaturesEntity>): SoundProfile? {
        var w = 0f
        var e = 0f
        var tp = 0f
        var b = 0f
        var d = 0f
        for (t in tracks) {
            val weight = (if (t.favorite) 2f else 0f) + ln(1f + t.playCount)
            if (weight <= 0f) continue
            val p = SoundProfile.from(features[t.id], t)
            w += weight
            e += p.energy * weight
            tp += p.tempo * weight
            b += p.brightness * weight
            d += p.dynamics * weight
        }
        if (w == 0f) return null
        return SoundProfile(e / w, tp / w, b / w, d / w, estimated = false)
    }

    private fun similarity(a: SoundProfile, b: SoundProfile): Float {
        val de = a.energy - b.energy
        val dt = a.tempo - b.tempo
        val db = a.brightness - b.brightness
        val dd = a.dynamics - b.dynamics
        val dist = sqrt((1.2f * de * de + dt * dt + 0.7f * db * db + 0.4f * dd * dd) / 3.3f)
        return (1f - dist / 0.5f).coerceIn(0f, 1f)
    }

    companion object {
        const val OWNER = "wave"
        private const val KEY_MODE = "mode"
        private const val KEY_BANNED = "banned_urls"
        private const val KEY_LIKED = "liked_artists"
    }
}
