package app.vesper.player.data.wave

import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.TrackFeaturesEntity
import app.vesper.player.data.db.VesperDatabase
import app.vesper.player.playback.PlayerConnection
import app.vesper.player.playback.PlayerUiState
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

data class WaveState(
    val vibe: Vibe? = null,
    val active: Boolean = false,
    val starting: Boolean = false,
    /** Sound profile of the track currently playing (drives the wave animation). */
    val nowProfile: SoundProfile? = null,
)

/**
 * "Wave": an endless, self-extending queue built from the local library. Ranks tracks by
 * how they sound (measured on-device), the chosen mood/activity, and listening feedback
 * (favourites, plays, skips, dislikes). Works fully offline.
 */
class WaveEngine(
    private val db: VesperDatabase,
    private val player: PlayerConnection,
    private val scope: CoroutineScope,
) {
    private val dao = db.wave()
    private val _state = MutableStateFlow(WaveState())
    val state: StateFlow<WaveState> = _state.asStateFlow()

    private val recentPicks = ArrayDeque<Long>()
    private val recentArtists = ArrayDeque<String>()
    private var extending = false
    private var profileFor: Long = -1

    init {
        scope.launch { player.state.collect { onPlayerState(it) } }
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
            val inQueue = s.queue.map { it.trackId }.toSet()
            scope.launch {
                try {
                    val picks = pick(_state.value.vibe ?: Vibes.myWave, 10, inQueue)
                    if (picks.isNotEmpty()) player.addToQueue(picks, silent = true)
                } finally {
                    extending = false
                }
            }
        }
    }

    fun start(vibe: Vibe) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        scope.launch {
            try {
                recentPicks.clear()
                recentArtists.clear()
                val picks = pick(vibe, 15, emptySet())
                if (picks.isEmpty()) {
                    player.notify("Для волны нужна музыка в библиотеке")
                    return@launch
                }
                _state.update { it.copy(vibe = vibe) }
                player.playTracks(picks, 0, shuffle = false, owner = OWNER)
            } finally {
                _state.update { it.copy(starting = false) }
            }
        }
    }

    /** "Not for me": never pick this track again and skip it now. */
    fun dislike(trackId: Long) {
        scope.launch {
            dao.ensureStats(trackId)
            dao.setBanned(trackId, true)
        }
        player.next()
        player.notify("Больше не попадётся в волне")
    }

    fun resetLearning() {
        scope.launch {
            dao.clearStats()
            player.notify("Обучение волны сброшено")
        }
    }

    private suspend fun pick(vibe: Vibe, count: Int, exclude: Set<Long>): List<TrackEntity> {
        val tracks = db.tracks().getAll()
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

            // Greedy pick with artist spacing so the same artist doesn't repeat back-to-back.
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

    /** Average sound of what the listener loves — the centre of "Моя волна". */
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
    }
}
