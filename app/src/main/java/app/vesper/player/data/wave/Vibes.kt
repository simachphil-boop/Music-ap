package app.vesper.player.data.wave

import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.TrackFeaturesEntity
import kotlin.math.sqrt

enum class VibeGroup(val title: String) { CHARACTER("Характер"), MOOD("Настроение"), ACTIVITY("Занятие") }

/** How a vibe ranks tracks beyond pure sound similarity. */
enum class VibeStrategy { TASTE, FAVORITES, DISCOVERY, FORGOTTEN, SOUND }

/**
 * A Wave preset. Targets are on the normalised 0..1 feature scale
 * (null = this dimension doesn't matter).
 */
data class Vibe(
    val id: String,
    val title: String,
    val subtitle: String,
    val group: VibeGroup,
    val strategy: VibeStrategy,
    val energy: Float? = null,
    val tempo: Float? = null,
    val brightness: Float? = null,
    val dynamics: Float? = null,
    val likesGenres: Set<String> = emptySet(),
    val avoidsGenres: Set<String> = emptySet(),
)

/** Feature vector used for scoring (real analysis or genre-based estimate). */
data class SoundProfile(
    val energy: Float,
    val tempo: Float,
    val brightness: Float,
    val dynamics: Float,
    val estimated: Boolean,
) {
    companion object {
        fun normTempo(bpm: Float): Float = ((bpm - 70f) / 110f).coerceIn(0f, 1f)

        fun from(features: TrackFeaturesEntity?, track: TrackEntity): SoundProfile {
            if (features != null && !features.failed) {
                return SoundProfile(
                    energy = features.energy,
                    tempo = normTempo(features.bpm),
                    brightness = features.brightness,
                    dynamics = features.dynamics,
                    estimated = false,
                )
            }
            return Genres.estimate(track)
        }
    }
}

object Genres {
    val calm = setOf("ambient", "classical", "classic", "piano", "chill", "lofi", "lo-fi", "acoustic", "jazz", "new age", "soundtrack", "folk", "instrumental", "meditation", "sleep", "neoclassical", "downtempo", "bossa")
    val energetic = setOf("rock", "metal", "punk", "edm", "dance", "electro", "house", "techno", "drum", "dnb", "trance", "hip hop", "hip-hop", "rap", "phonk", "hardstyle", "dubstep", "trap", "workout", "grunge", "hardcore")
    val happy = setOf("pop", "disco", "funk", "reggae", "k-pop", "kpop", "latin", "ska", "summer", "dance pop", "eurodance")
    val melancholic = setOf("blues", "soul", "indie", "dark", "gothic", "goth", "post-rock", "emo", "doom", "shoegaze", "darkwave", "post-punk", "sad", "slowcore")

    fun matches(track: TrackEntity, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return false
        val g = track.genre.lowercase()
        return keys.any { g.contains(it) }
    }

    /** Rough sound profile from tags when the file hasn't been analysed (yet). */
    fun estimate(track: TrackEntity): SoundProfile = when {
        matches(track, energetic) -> SoundProfile(0.78f, 0.68f, 0.62f, 0.35f, true)
        matches(track, happy) -> SoundProfile(0.66f, 0.62f, 0.66f, 0.40f, true)
        matches(track, calm) -> SoundProfile(0.28f, 0.32f, 0.35f, 0.55f, true)
        matches(track, melancholic) -> SoundProfile(0.42f, 0.38f, 0.32f, 0.50f, true)
        else -> SoundProfile(0.5f, 0.5f, 0.5f, 0.45f, true)
    }
}

object Vibes {
    val myWave = Vibe("my", "Моя волна", "Бесконечный поток по вашему вкусу", VibeGroup.CHARACTER, VibeStrategy.TASTE)
    val favorites = Vibe("fav", "Любимое", "То, что вы слушаете чаще всего", VibeGroup.CHARACTER, VibeStrategy.FAVORITES)
    val discovery = Vibe("new", "Незнакомое", "Треки, которые вы ещё не слушали", VibeGroup.CHARACTER, VibeStrategy.DISCOVERY)
    val forgotten = Vibe("old", "Забытое", "Давно не звучало", VibeGroup.CHARACTER, VibeStrategy.FORGOTTEN)

    val energetic = Vibe(
        "energetic", "Бодрое", "Громко, быстро, с напором", VibeGroup.MOOD, VibeStrategy.SOUND,
        energy = 0.85f, tempo = 0.7f, likesGenres = Genres.energetic, avoidsGenres = Genres.calm,
    )
    val happy = Vibe(
        "happy", "Весёлое", "Светлое и танцевальное", VibeGroup.MOOD, VibeStrategy.SOUND,
        energy = 0.65f, tempo = 0.65f, brightness = 0.7f, likesGenres = Genres.happy, avoidsGenres = Genres.melancholic,
    )
    val calm = Vibe(
        "calm", "Спокойное", "Мягко и негромко", VibeGroup.MOOD, VibeStrategy.SOUND,
        energy = 0.25f, tempo = 0.3f, brightness = 0.35f, likesGenres = Genres.calm, avoidsGenres = Genres.energetic,
    )
    val sad = Vibe(
        "sad", "Грустное", "Медленно и задумчиво", VibeGroup.MOOD, VibeStrategy.SOUND,
        energy = 0.35f, tempo = 0.3f, brightness = 0.28f, likesGenres = Genres.melancholic, avoidsGenres = Genres.happy,
    )
    val dark = Vibe(
        "dark", "Мрачное", "Тяжело, глубоко, без солнца", VibeGroup.MOOD, VibeStrategy.SOUND,
        energy = 0.62f, brightness = 0.2f, likesGenres = Genres.melancholic + setOf("metal", "industrial"), avoidsGenres = Genres.happy,
    )

    val workout = Vibe(
        "workout", "Тренировка", "Максимум энергии и темпа", VibeGroup.ACTIVITY, VibeStrategy.SOUND,
        energy = 0.92f, tempo = 0.8f, dynamics = 0.3f, likesGenres = Genres.energetic, avoidsGenres = Genres.calm,
    )
    val focus = Vibe(
        "focus", "Фокус", "Ровный фон для работы и учёбы", VibeGroup.ACTIVITY, VibeStrategy.SOUND,
        energy = 0.4f, tempo = 0.45f, dynamics = 0.25f, brightness = 0.4f,
        likesGenres = setOf("ambient", "lofi", "lo-fi", "classical", "piano", "instrumental", "soundtrack", "chill", "downtempo"),
        avoidsGenres = setOf("rap", "hip hop", "metal", "punk"),
    )
    val drive = Vibe(
        "drive", "Дорога", "Уверенный ритм для пути", VibeGroup.ACTIVITY, VibeStrategy.SOUND,
        energy = 0.66f, tempo = 0.6f, brightness = 0.55f,
    )
    val party = Vibe(
        "party", "Вечеринка", "Хиты и танцевальные треки", VibeGroup.ACTIVITY, VibeStrategy.SOUND,
        energy = 0.85f, tempo = 0.72f, brightness = 0.7f, likesGenres = Genres.happy + setOf("dance", "house", "edm", "disco"), avoidsGenres = Genres.calm,
    )
    val relax = Vibe(
        "relax", "Отдых", "Расслабиться после дня", VibeGroup.ACTIVITY, VibeStrategy.SOUND,
        energy = 0.32f, tempo = 0.38f, likesGenres = Genres.calm + setOf("soul", "jazz", "bossa"),
    )
    val sleep = Vibe(
        "sleep", "Сон", "Тише, медленнее, без резких звуков", VibeGroup.ACTIVITY, VibeStrategy.SOUND,
        energy = 0.12f, tempo = 0.2f, brightness = 0.25f, dynamics = 0.3f, likesGenres = Genres.calm, avoidsGenres = Genres.energetic,
    )

    val all = listOf(
        myWave, favorites, discovery, forgotten,
        energetic, happy, calm, sad, dark,
        workout, focus, drive, party, relax, sleep,
    )

    fun byId(id: String?): Vibe? = all.firstOrNull { it.id == id }

    /** 0..1 — how well a sound profile matches the vibe's targets. */
    fun fit(vibe: Vibe, p: SoundProfile, track: TrackEntity): Float {
        var sum = 0f
        var weights = 0f
        fun dim(target: Float?, value: Float, w: Float) {
            if (target == null) return
            val d = target - value
            sum += w * d * d
            weights += w
        }
        dim(vibe.energy, p.energy, 1.2f)
        dim(vibe.tempo, p.tempo, if (p.estimated) 0.6f else 1f)
        dim(vibe.brightness, p.brightness, 0.7f)
        dim(vibe.dynamics, p.dynamics, 0.5f)
        var score = if (weights == 0f) 0.6f else (1f - sqrt(sum / weights) / 0.55f).coerceIn(0f, 1f)
        if (Genres.matches(track, vibe.likesGenres)) score += 0.18f
        if (Genres.matches(track, vibe.avoidsGenres)) score -= 0.3f
        if (p.estimated) score *= 0.9f
        return score.coerceIn(0f, 1.2f)
    }
}
