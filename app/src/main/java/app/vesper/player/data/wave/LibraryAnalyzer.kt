package app.vesper.player.data.wave

import android.content.Context
import android.net.Uri
import app.vesper.player.data.db.TrackFeaturesEntity
import app.vesper.player.data.db.VesperDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AnalysisState(val analyzed: Int = 0, val total: Int = 0, val running: Boolean = false) {
    val fraction: Float get() = if (total == 0) 0f else (analyzed.toFloat() / total).coerceIn(0f, 1f)
    val complete: Boolean get() = total > 0 && analyzed >= total
}

/** Background job that measures the sound of every track once and stores the result. */
class LibraryAnalyzer(
    private val context: Context,
    private val db: VesperDatabase,
    private val scope: CoroutineScope,
) {
    private val dao = db.wave()
    private val running = MutableStateFlow(false)
    private var job: Job? = null

    val state: Flow<AnalysisState> =
        combine(dao.observeAnalyzedCount(), db.tracks().observeCount(), running) { analyzed, total, run ->
            AnalysisState(analyzed.coerceAtMost(total), total, run)
        }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            running.value = true
            try {
                while (true) {
                    val batch = dao.pendingTracks(25)
                    if (batch.isEmpty()) break
                    for (track in batch) {
                        ensureActive()
                        val f = AudioAnalyzer.analyze(context, Uri.parse(track.uri), track.durationMs)
                        dao.insertFeatures(
                            TrackFeaturesEntity(
                                trackId = track.id,
                                energy = f?.energy ?: 0.5f,
                                dynamics = f?.dynamics ?: 0.5f,
                                brightness = f?.brightness ?: 0.5f,
                                bpm = f?.bpm ?: 110f,
                                tempoConfidence = f?.tempoConfidence ?: 0f,
                                failed = f == null,
                                analyzedAt = System.currentTimeMillis(),
                            ),
                        )
                        // Stay gentle on CPU and battery.
                        delay(60)
                    }
                }
            } finally {
                running.value = false
            }
        }
    }

    fun reanalyze() {
        scope.launch {
            job?.cancelAndJoin()
            dao.clearFeatures()
            start()
        }
    }
}
