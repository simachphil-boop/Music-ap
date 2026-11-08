package app.vesper.player.data.assistant

import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.transfer.TrackListParser
import app.vesper.player.data.wave.SoundProfile
import kotlin.math.abs
import kotlin.random.Random

/** What the user asked for, understood from a free-text request. */
data class MusicRequest(
    val text: String,
    /** Wanted range of intensity 0..1 (energy + tempo), null = any. */
    val energy: ClosedFloatingPointRange<Float>? = null,
    val bright: Float? = null,
    val calmDynamics: Boolean = false,
    val label: String = "",
    val seedTrackId: Long? = null,
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val minutes: Int = 45,
    val novelty: Boolean = false,
)

data class Pick(val track: TrackEntity, val reasons: List<String>, val score: Double)

data class Suggestion(val title: String, val intro: String, val picks: List<Pick>)

/**
 * Local music recommendations — an ordinary algorithm, not a neural network:
 * it reads the request (activity, mood, "similar to…", artist, genre, length),
 * scores every track of the library by its sound profile (energy, tempo, brightness measured
 * on the phone), your favourites and listening history, avoids what it suggested recently,
 * spreads artists out, and says why each track was chosen.
 */
object Recommender {

    private data class Mood(val words: List<String>, val label: String, val energy: ClosedFloatingPointRange<Float>, val bright: Float? = null, val calm: Boolean = false)

    private val moods = listOf(
        Mood(listOf("трениров", "спорт", "бег", "пробеж", "зал", "кардио", "качал", "workout", "gym"), "для тренировки", 0.68f..1f),
        Mood(listOf("вечерин", "танц", "туса", "party", "дискотек"), "для вечеринки", 0.7f..1f, bright = 0.65f),
        Mood(listOf("прогул", "гулять", "погулять", "пешком", "walk"), "для прогулки", 0.42f..0.72f),
        Mood(listOf("дорог", "машин", "поездк", "в пути", "drive", "трасс"), "для дороги", 0.5f..0.82f),
        Mood(listOf("сон", "засып", "спать", "колыбел", "sleep"), "для сна", 0f..0.25f, bright = 0.3f, calm = true),
        Mood(listOf("спокой", "вечер", "расслаб", "отдых", "чил", "chill", "relax", "медитац", "тих"), "спокойная", 0f..0.42f, calm = true),
        Mood(listOf("работ", "учёб", "учеб", "фокус", "концентрац", "сосредоточ", "study", "focus", "домашк"), "для концентрации", 0.25f..0.55f, calm = true),
        Mood(listOf("груст", "печал", "меланхол", "дожд", "осен", "sad"), "под грустное настроение", 0.05f..0.45f, bright = 0.3f),
        Mood(listOf("радост", "весел", "бодр", "утр", "позитив", "счаст", "happy", "энерги"), "бодрая", 0.55f..0.88f, bright = 0.7f),
        Mood(listOf("романт", "свидан", "любов", "love"), "романтичная", 0.2f..0.55f, bright = 0.5f),
    )

    private val similarWords = listOf("похож", "как этот", "как эт", "такое же", "подобн", "similar", "в стиле", "наподобие")
    private val noveltyWords = listOf("нов", "незнаком", "давно не", "забыт", "свеж", "открыт")

    /** Turns "спокойную музыку на час, похожее на Кино" into a [MusicRequest]. */
    fun understand(text: String, library: List<TrackEntity>, currentTrackId: Long?): MusicRequest {
        val q = text.lowercase()
        val mood = moods.firstOrNull { m -> m.words.any { it in q } }
        val wantsSimilar = similarWords.any { it in q }
        val qKey = " " + TrackListParser.key(text) + " "
        val artists = library.asSequence().map { it.artist }.filter { it.length >= 3 && !it.startsWith("<") }.distinct()
            .filter { a -> " ${TrackListParser.key(a)} ".let { k -> k.isNotBlank() && qKey.contains(k) } }.take(3).toList()
        val genres = library.asSequence().map { it.genre }.filter { it.length >= 3 }.distinct()
            .filter { g -> qKey.contains(" " + TrackListParser.key(g) + " ") }.take(2).toList()
        val minutes = Regex("""(\d{1,3})\s*(мин|minute)""").find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d)\s*(час|hour)""").find(q)?.groupValues?.get(1)?.toIntOrNull()?.times(60)
            ?: if (q.contains("на час") || q.contains("час музыки")) 60 else if (q.contains("полчаса")) 30 else 45
        val seed = if (wantsSimilar && artists.isEmpty()) currentTrackId else null
        return MusicRequest(
            text = text,
            energy = mood?.energy,
            bright = mood?.bright,
            calmDynamics = mood?.calm ?: false,
            label = mood?.label.orEmpty(),
            seedTrackId = seed,
            artists = artists,
            genres = genres,
            minutes = minutes.coerceIn(10, 240),
            novelty = noveltyWords.any { it in q },
        )
    }

    fun recommend(
        req: MusicRequest,
        library: List<TrackEntity>,
        profiles: Map<Long, SoundProfile>,
        recentlySuggested: Set<Long>,
        useHistory: Boolean,
        now: Long = System.currentTimeMillis(),
        random: Random = Random(now),
    ): Suggestion {
        val pool = library.filter { it.durationMs in 45_000..15 * 60_000 && !it.uri.startsWith("http") }
        if (pool.isEmpty()) return Suggestion("Нечего подобрать", "В библиотеке пока нет подходящих треков.", emptyList())
        val seed = req.seedTrackId?.let { id -> pool.firstOrNull { it.id == id } ?: library.firstOrNull { it.id == id } }
        val seedProfile = seed?.let { profiles[it.id] ?: SoundProfile.from(null, it) }
        val artistKeys = req.artists.map { TrackListParser.key(it) }.toSet()
        val genreKeys = req.genres.map { TrackListParser.key(it) }.toSet()

        // Taste: artists you like (favourites count double) and play a lot.
        val artistLove = HashMap<String, Double>()
        pool.forEach { t ->
            val k = TrackListParser.key(t.artist)
            val v = (if (t.favorite) 2.0 else 0.0) + (if (useHistory) minOf(t.playCount, 20) / 10.0 else 0.0)
            if (v > 0) artistLove[k] = (artistLove[k] ?: 0.0) + v
        }
        val maxLove = artistLove.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

        data class Scored(val t: TrackEntity, val score: Double, val reasons: List<String>)

        val scored = pool.map { t ->
            val p = profiles[t.id] ?: SoundProfile.from(null, t)
            val intensity = 0.65f * p.energy + 0.35f * p.tempo
            val reasons = ArrayList<String>()
            var s = 0.0
            req.energy?.let { r ->
                val fit = when {
                    intensity in r -> 1.0
                    else -> (1.0 - minOf(abs(intensity - r.start), abs(intensity - r.endInclusive)) * 3.0).coerceAtLeast(0.0)
                }
                s += 0.45 * fit
                if (fit > 0.8) reasons += moodReason(req.label, intensity, p)
            }
            req.bright?.let { b -> s += 0.1 * (1.0 - abs(p.brightness - b)) }
            if (req.calmDynamics) s += 0.05 * (1.0 - p.dynamics)
            if (seed != null && seedProfile != null) {
                if (t.id == seed.id) return@map Scored(t, -1.0, reasons)
                val dist = abs(p.energy - seedProfile.energy) + abs(p.tempo - seedProfile.tempo) + 0.6f * abs(p.brightness - seedProfile.brightness)
                val sim = (1.0 - dist / 1.6).coerceIn(0.0, 1.0)
                s += 0.4 * sim
                if (sim > 0.75) reasons += "по звучанию близко к «${seed.title}»: похожие энергия и темп"
                if (t.artist.equals(seed.artist, true)) { s += 0.08; reasons += "тот же исполнитель, что и в «${seed.title}»" }
                if (t.genre.isNotBlank() && t.genre.equals(seed.genre, true)) { s += 0.08; reasons += "тот же жанр — ${t.genre}" }
            }
            val ak = TrackListParser.key(t.artist)
            if (artistKeys.isNotEmpty()) {
                if (ak in artistKeys) { s += 0.5; reasons += "вы просили ${t.artist}" }
            }
            if (genreKeys.isNotEmpty() && TrackListParser.key(t.genre) in genreKeys) { s += 0.35; reasons += "жанр ${t.genre}" }
            if (t.favorite) { s += 0.08; reasons += "в вашем избранном" }
            artistLove[ak]?.let { love ->
                val v = love / maxLove
                s += 0.1 * v
                if (v > 0.5 && t.artist.isNotBlank()) reasons += "вы часто слушаете ${t.artist}"
            }
            if (useHistory) {
                val daysSince = if (t.lastPlayedAt > 0) (now - t.lastPlayedAt) / 86_400_000.0 else 999.0
                if (daysSince < 1) s -= 0.2 // just heard it
                if (req.novelty) {
                    if (t.playCount == 0 || daysSince > 60) { s += 0.2; reasons += if (t.playCount == 0) "вы его ещё не слушали" else "давно не звучал" }
                    else s -= 0.1
                }
            }
            // Don't keep offering the same songs.
            if (t.id in recentlySuggested) s -= 0.3
            s += random.nextDouble() * 0.06
            Scored(t, s, reasons.distinct())
        }.filter { it.score > -0.5 }.sortedByDescending { it.score }

        // Fill the requested time, at most 3 tracks per artist and never two in a row.
        val target = req.minutes * 60_000L
        val out = ArrayList<Pick>()
        val perArtist = HashMap<String, Int>()
        var total = 0L
        var lastArtist = ""
        val candidates = scored.toMutableList()
        while (total < target && candidates.isNotEmpty()) {
            val i = candidates.indexOfFirst { c ->
                val a = TrackListParser.key(c.t.artist)
                (perArtist[a] ?: 0) < (if (artistKeys.isNotEmpty()) 8 else 3) && (a != lastArtist || candidates.size < 4 || artistKeys.isNotEmpty())
            }.takeIf { it >= 0 } ?: break
            val c = candidates.removeAt(i)
            val a = TrackListParser.key(c.t.artist)
            perArtist[a] = (perArtist[a] ?: 0) + 1
            lastArtist = a
            total += c.t.durationMs
            out += Pick(c.t, c.reasons.ifEmpty { listOf("подходит по общему настроению вашей библиотеки") }.take(2), c.score)
        }

        val title = when {
            seed != null -> "Похоже на «${seed.title}»"
            req.artists.isNotEmpty() -> "В духе ${req.artists.joinToString(", ")}"
            req.label.isNotBlank() -> "Подборка " + req.label
            req.genres.isNotEmpty() -> "Жанр: ${req.genres.joinToString(", ")}"
            else -> "Подборка по вашему вкусу"
        }
        val intro = buildString {
            append("${out.size} треков, около ${total / 60_000} мин. ")
            if (req.energy != null) append("Подбирал по измеренной энергии и темпу треков. ")
            if (seed != null) append("Ориентир — звучание «${seed.title}». ")
            if (useHistory) append("Учёл избранное и историю прослушивания. ") else append("История прослушивания не учитывалась. ")
            append("Недавно предложенные треки отодвинуты назад.")
        }
        return Suggestion(title, intro, out)
    }

    private fun moodReason(label: String, intensity: Float, p: SoundProfile): String = when {
        intensity > 0.7f -> "высокая энергия и быстрый темп" + if (label.isNotBlank()) " — $label" else ""
        intensity < 0.35f -> "мягкое, неторопливое звучание" + if (label.isNotBlank()) " — $label" else ""
        p.brightness > 0.6f -> "светлое звучание, средний темп" + if (label.isNotBlank()) " — $label" else ""
        else -> "умеренный темп" + if (label.isNotBlank()) " — $label" else ""
    }
}
