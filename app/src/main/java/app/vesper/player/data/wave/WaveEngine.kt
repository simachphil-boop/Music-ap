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
    web: app.vesper.player.data.search.WebSearch,
) {
    private val dao = db.wave()
    private val prefs = context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs("floya_wave"), Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        WaveState(mode = runCatching { WaveMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(WaveMode.ONLINE)),
    )
    val state: StateFlow<WaveState> = _state.asStateFlow()

    // Downloads mode
    private val recentPicks = ArrayDeque<Long>()
    private val recentArtists = ArrayDeque<String>()
    // Online mode
    private val online = OnlineWave(prefs, db, web)
    private val appContext = context.applicationContext
    private var heardEntry: QueueEntry? = null
    private var heardSince = 0L

    private var extending = false
    private var profileFor: Long = -1

    init {
        scope.launch { player.state.collect { onPlayerState(it) } }
        // Prepare the stream of the first saved song, so «Слушать волну» starts without a pause.
        scope.launch {
            kotlinx.coroutines.delay(2500)
            if (_state.value.mode == WaveMode.ONLINE && !_state.value.active) {
                online.peekSaved()?.let { app.vesper.player.playback.StreamResolver.prefetch(appContext, it.pageUrl, allowSlow = false) }
            }
        }
    }

    /** Learns from how each wave song was heard: most of it — more like this; skipped at once — less. */
    private fun trackListening(active: Boolean, current: QueueEntry?) {
        val prev = heardEntry
        if (prev?.key == current?.key && (active || prev == null)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val url = prev?.pageUrl
        if (prev != null && url != null) {
            val heard = now - heardSince
            val enough = if (prev.durationMs > 0) minOf(60_000L, prev.durationMs / 2) else 60_000L
            when {
                heard >= enough -> online.onCompleted(url)
                heard < 20_000 && current != null -> scope.launch { online.onSkipped(prev.artist) }
            }
        }
        heardEntry = if (active) current else null
        heardSince = now
    }

    fun setMode(mode: WaveMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _state.update { it.copy(mode = mode) }
    }

    private fun onPlayerState(s: PlayerUiState) {
        val active = s.queueOwner == OWNER
        if (active != _state.value.active) _state.update { it.copy(active = active) }
        trackListening(active, s.current)
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
                        player.addOnline(online.pick(vibe, 8, exclude))
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
                        online.reset(vibe)
                        // A saved selection plays at once; fresh music is fetched while it is already on.
                        val instant = online.hasReady(6)
                        val picks = online.pick(vibe, if (instant) 6 else 10, emptySet())
                        if (picks.isEmpty()) {
                            player.notify("Не удалось найти музыку — проверьте интернет или включите «Мои загрузки»")
                            return@launch
                        }
                        _state.update { it.copy(vibe = vibe) }
                        player.playOnline(picks, 0, owner = OWNER)
                        if (instant) scope.launch { runCatching { online.refresh(vibe, picks.map { it.pageUrl }.toSet()) } }
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
        entry.pageUrl?.let { online.onLiked(it) }
        player.notify("Учту: больше похожего на $artist")
    }

    fun isLikedArtist(artist: String): Boolean = likedArtists().any { it.equals(artist.trim(), true) }

    fun resetLearning() {
        scope.launch {
            dao.clearStats()
            prefs.edit().remove(KEY_BANNED).remove(KEY_LIKED).apply()
            online.forget()
            player.notify("Обучение волны сброшено")
        }
    }

    private fun bannedUrls(): Set<String> = prefs.getStringSet(KEY_BANNED, emptySet()).orEmpty()
    private fun likedArtists(): List<String> = prefs.getString(KEY_LIKED, "").orEmpty().lines().filter { it.isNotBlank() }

    // ---------------------------------------------------------------------------------------------
    // Online
    // ---------------------------------------------------------------------------------------------

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
