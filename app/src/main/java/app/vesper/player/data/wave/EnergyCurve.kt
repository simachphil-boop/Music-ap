package app.vesper.player.data.wave

import app.vesper.player.data.db.TrackEntity
import kotlin.math.abs
import kotlin.random.Random

/** A hand-drawn intensity plan: [points] are 0..1 values spread evenly over [minutes]. */
data class EnergyCurve(val points: List<Float>, val minutes: Int) {
    /** Curve value at [x] in 0..1 (linear between points). */
    fun at(x: Float): Float {
        if (points.isEmpty()) return 0.5f
        if (points.size == 1) return points[0]
        val pos = x.coerceIn(0f, 1f) * (points.size - 1)
        val i = pos.toInt().coerceAtMost(points.size - 2)
        val f = pos - i
        return points[i] * (1 - f) + points[i + 1] * f
    }

    companion object {
        const val RESOLUTION = 32

        fun preset(kind: CurvePreset, minutes: Int): EnergyCurve =
            EnergyCurve(List(RESOLUTION) { i -> kind.shape(i / (RESOLUTION - 1f)).coerceIn(0.02f, 0.98f) }, minutes)
    }
}

enum class CurvePreset(val title: String, val shape: (Float) -> Float) {
    WORKOUT("Тренировка", { x ->
        when {
            x < 0.15f -> 0.3f + x / 0.15f * 0.45f
            x < 0.8f -> 0.8f + 0.15f * kotlin.math.sin(x * 18f)
            else -> 0.85f - (x - 0.8f) / 0.2f * 0.6f
        }
    }),
    PARTY("Вечеринка", { x -> 0.35f + 0.6f * x + 0.05f * kotlin.math.sin(x * 25f) }),
    SLEEP("Засыпание", { x -> 0.6f - 0.55f * x }),
    FOCUS("Фокус", { x -> 0.42f + 0.06f * kotlin.math.sin(x * 9f) }),
    ROLLER("Горки", { x -> 0.5f + 0.42f * kotlin.math.sin(x * 12.5f) }),
}

data class CurvePick(val track: TrackEntity, val startMs: Long, val level: Float, val target: Float)

/**
 * Lays tracks along the curve: walks through the planned time, and for each slot takes the unused
 * track whose intensity (energy + tempo + brightness, ranked within *your* library so the whole
 * 0..1 range is used) is closest to what the curve asks for at that moment.
 */
object CurveBuilder {
    fun build(
        curve: EnergyCurve,
        tracks: List<TrackEntity>,
        profiles: Map<Long, SoundProfile>,
        seed: Int = 0,
    ): List<CurvePick> {
        val pool = tracks.filter { it.durationMs in 60_000..12 * 60_000 && !it.uri.startsWith("http") }
        if (pool.isEmpty()) return emptyList()
        val raw = pool.associate { t ->
            val p = profiles[t.id] ?: SoundProfile.from(null, t)
            t.id to (0.6f * p.energy + 0.25f * p.tempo + 0.15f * p.brightness)
        }
        // Rank → percentile, so a library of mostly loud (or mostly quiet) music still spans the curve.
        val ranked = raw.entries.sortedBy { it.value }
        val level = HashMap<Long, Float>(ranked.size)
        ranked.forEachIndexed { i, e -> level[e.key] = if (ranked.size == 1) 0.5f else i / (ranked.size - 1f) }

        val random = Random(seed)
        val totalMs = curve.minutes * 60_000L
        val used = HashSet<Long>()
        val recentArtists = ArrayDeque<String>()
        val out = ArrayList<CurvePick>()
        var t = 0L
        while (t < totalMs) {
            var best: TrackEntity? = null
            var bestCost = Float.MAX_VALUE
            var bestTarget = 0f
            for (track in pool) {
                if (track.id in used) continue
                // Judge the track by the curve around its middle, not its first second.
                val mid = t + minOf(track.durationMs, 240_000L) / 2
                val target = curve.at(mid.toFloat() / totalMs)
                var cost = abs((level[track.id] ?: 0.5f) - target)
                if (recentArtists.any { it.equals(track.artist, ignoreCase = true) }) cost += 0.12f
                if (track.favorite) cost -= 0.02f
                cost += random.nextFloat() * 0.035f
                if (cost < bestCost) {
                    bestCost = cost
                    best = track
                    bestTarget = target
                }
            }
            val pick = best ?: break
            used += pick.id
            out += CurvePick(pick, t, level[pick.id] ?: 0.5f, bestTarget)
            recentArtists.addLast(pick.artist)
            if (recentArtists.size > 3) recentArtists.removeFirst()
            t += pick.durationMs
        }
        return out
    }
}
