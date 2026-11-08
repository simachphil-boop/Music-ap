package app.vesper.player.data.media

import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.transfer.TrackListParser
import kotlin.math.abs

/** Library tidying that never touches files: only what is shown. */
object SmartLibrary {
    /**
     * Hides extra copies of the same song (same title and artist, length within 3 s — e.g. the same
     * MP3 in Downloads and in Music). Keeps the best copy: favourite, most played, then the biggest file.
     */
    fun dedupe(tracks: List<TrackEntity>): List<TrackEntity> {
        val groups = tracks.groupBy { TrackListParser.key(it.title) + "|" + TrackListParser.key(it.artist) }
        val keep = HashSet<Long>(tracks.size)
        for ((key, list) in groups) {
            if (list.size == 1 || key.startsWith("|")) {
                list.forEach { keep += it.id }
                continue
            }
            // Same name but clearly different recordings (e.g. 3:10 and 6:40 remix) stay separate.
            val clusters = ArrayList<MutableList<TrackEntity>>()
            for (t in list.sortedBy { it.durationMs }) {
                val c = clusters.lastOrNull()
                if (c != null && abs(c.last().durationMs - t.durationMs) <= 3000) c += t else clusters += mutableListOf(t)
            }
            clusters.forEach { c ->
                val best = c.maxWithOrNull(compareBy<TrackEntity>({ it.favorite }, { it.playCount }, { it.sizeBytes }))!!
                keep += best.id
            }
        }
        return tracks.filter { it.id in keep }
    }
}
