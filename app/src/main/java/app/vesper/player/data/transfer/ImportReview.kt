package app.vesper.player.data.transfer

import app.vesper.player.data.download.SearchResult
import app.vesper.player.data.search.WebSearch
import app.vesper.player.playback.OnlineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class MatchState { WAITING, SEARCHING, SURE, CHECK, NOT_FOUND, SKIPPED }

data class MatchRow(
    val index: Int,
    val want: ImportedTrack,
    val state: MatchState = MatchState.WAITING,
    val candidates: List<Pair<SearchResult, Double>> = emptyList(),
    val chosen: Int = 0,
) {
    val pick: SearchResult? get() = candidates.getOrNull(chosen)?.first
    val score: Double get() = candidates.getOrNull(chosen)?.second ?: 0.0
    val willSave: Boolean get() = (state == MatchState.SURE || state == MatchState.CHECK) && pick != null
}

data class ReviewState(
    val name: String,
    val source: String,
    val sourceUrl: String,
    val cover: String?,
    val rows: List<MatchRow>,
) {
    val done: Int get() = rows.count { it.state != MatchState.WAITING && it.state != MatchState.SEARCHING }
    val running: Boolean get() = done < rows.size
    val sure: Int get() = rows.count { it.state == MatchState.SURE }
    val check: Int get() = rows.count { it.state == MatchState.CHECK }
    val missing: List<MatchRow> get() = rows.filter { it.state == MatchState.NOT_FOUND }
}

/**
 * Brings a list of tracks (from Yandex Music, a file, any service) into Floya:
 * for every track it searches YouTube Music, scores the results ([TrackMatcher]) and lets the
 * user confirm doubtful matches, pick another candidate or skip — before anything is saved.
 * Nothing is downloaded here; only names, order and links to legally streamable versions.
 */
class ImportReview(
    private val webSearch: WebSearch,
    private val transfer: PlaylistTransfer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ReviewState?>(null)
    val state: StateFlow<ReviewState?> = _state.asStateFlow()
    private var job: Job? = null

    fun start(name: String, source: String, sourceUrl: String, cover: String?, tracks: List<ImportedTrack>) {
        job?.cancel()
        _state.value = ReviewState(name, source, sourceUrl, cover, tracks.mapIndexed { i, t -> MatchRow(i, t) })
        val gate = Semaphore(4)
        job = scope.launch {
            tracks.indices.map { i ->
                launch { gate.withPermit { match(i) } }
            }
        }
    }

    fun start(p: TransferredPlaylist) = start(
        p.name, p.source, p.sourceUrl, p.coverUrl,
        p.items.map { ImportedTrack(it.artist, it.title, it.durationSec, it.thumbnail) },
    )

    private suspend fun match(i: Int) {
        val row = _state.value?.rows?.getOrNull(i) ?: return
        update(i) { it.copy(state = MatchState.SEARCHING) }
        val q = listOf(row.want.artist, row.want.title).filter { it.isNotBlank() }.joinToString(" ")
        val results = webSearch.songs(q).getOrNull().orEmpty().take(6)
        val scored = results.map { r ->
            val artist = r.author.substringBefore(" · ")
            r to TrackMatcher.score(row.want, r.title, artist, r.durationSec)
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.second ?: 0.0
        val st = when {
            scored.isEmpty() || best < TrackMatcher.MAYBE -> MatchState.NOT_FOUND
            best >= TrackMatcher.SURE -> MatchState.SURE
            else -> MatchState.CHECK
        }
        update(i) { it.copy(state = st, candidates = scored, chosen = 0) }
    }

    /** User picked another candidate: it becomes a confirmed match. */
    fun choose(i: Int, candidate: Int) = update(i) { it.copy(chosen = candidate, state = MatchState.SURE) }

    fun skip(i: Int) = update(i) { it.copy(state = MatchState.SKIPPED) }

    fun restore(i: Int) = update(i) {
        it.copy(state = if (it.candidates.isEmpty()) MatchState.NOT_FOUND else if (it.score >= TrackMatcher.SURE) MatchState.SURE else MatchState.CHECK)
    }

    fun confirmAll() = _state.update { s -> s?.copy(rows = s.rows.map { if (it.state == MatchState.CHECK) it.copy(state = MatchState.SURE) else it }) }

    /** Search one more time with a hand-written query (for tracks that weren't found). */
    fun research(i: Int, query: String) {
        scope.launch {
            update(i) { it.copy(state = MatchState.SEARCHING) }
            val row = _state.value?.rows?.getOrNull(i) ?: return@launch
            val results = webSearch.songs(query).getOrNull().orEmpty().take(6)
            val scored = results.map { r -> r to TrackMatcher.score(row.want, r.title, r.author.substringBefore(" · "), r.durationSec).coerceAtLeast(0.5) }
            update(i) { it.copy(state = if (scored.isEmpty()) MatchState.NOT_FOUND else MatchState.CHECK, candidates = scored, chosen = 0) }
        }
    }

    /** Saves confirmed matches as a Floya playlist in the original order. Returns its id. */
    suspend fun save(): Long? {
        val s = _state.value ?: return null
        val items = s.rows.filter { it.willSave }.map { r ->
            val p = r.pick!!
            OnlineItem(
                pageUrl = p.url,
                title = r.want.title,
                artist = r.want.artist.ifBlank { p.author.substringBefore(" · ") },
                durationSec = if (p.durationSec > 0) p.durationSec else r.want.durationSec,
                thumbnail = p.thumbnail ?: r.want.cover,
            )
        }
        if (items.isEmpty()) return null
        return transfer.save(TransferredPlaylist(s.name, s.source, s.sourceUrl, s.cover, items))
    }

    fun missingText(): String = _state.value?.missing.orEmpty().joinToString("\n") { r ->
        listOf(r.want.artist, r.want.title).filter { it.isNotBlank() }.joinToString(" — ")
    }

    fun close() {
        job?.cancel()
        _state.value = null
    }

    private fun update(i: Int, f: (MatchRow) -> MatchRow) {
        _state.update { s -> s?.copy(rows = s.rows.map { if (it.index == i) f(it) else it }) }
    }
}
