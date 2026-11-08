package app.vesper.player.data.assistant

import android.content.Context
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.download.SearchResult
import app.vesper.player.data.search.WebSearch
import app.vesper.player.data.transfer.TrackListParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import app.vesper.player.data.wave.SoundProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Connection to a language model chosen by the user (any OpenAI-compatible service). */
data class AiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    /** The user explicitly agreed that track names may be sent to that service. */
    val consent: Boolean = false,
) {
    val usable: Boolean get() = enabled && consent && apiKey.isNotBlank() && baseUrl.startsWith("https://")
}

/** A song found online for the request, with an optional reason. */
data class OnlineFind(val result: SearchResult, val why: String)

/** Online songs, online videos and the user's own matching videos for one request. */
data class Discovery(
    val title: String,
    val songs: List<OnlineFind>,
    val onlineVideos: List<SearchResult>,
    val localVideos: List<VideoEntity>,
    val byAi: Boolean,
    val note: String? = null,
)

data class AssistantAnswer(val suggestion: Suggestion, val byAi: Boolean, val note: String? = null)

/**
 * The music picker. By default everything happens on the phone ([Recommender]).
 * If the user connects their own AI key (and allows it), the request plus a shortlist of
 * candidate tracks — titles, artists, genre, energy; never files or account data — is sent to
 * that model, which chooses and explains; the result is always limited to tracks you own.
 */
class MusicAssistant(context: Context) {
    private val prefs = context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs("floya_assistant"), Context.MODE_PRIVATE)

    var config: AiConfig
        get() = AiConfig(
            enabled = prefs.getBoolean("ai_enabled", false),
            baseUrl = prefs.getString("ai_base", null) ?: AiConfig().baseUrl,
            model = prefs.getString("ai_model", null) ?: AiConfig().model,
            apiKey = prefs.getString("ai_key", null).orEmpty(),
            consent = prefs.getBoolean("ai_consent", false),
        )
        set(v) = prefs.edit()
            .putBoolean("ai_enabled", v.enabled).putString("ai_base", v.baseUrl.trim().trimEnd('/'))
            .putString("ai_model", v.model.trim()).putString("ai_key", v.apiKey.trim()).putBoolean("ai_consent", v.consent)
            .apply()

    /** Whether listening history may be used for picks (stays on the phone in local mode). */
    var useHistory: Boolean
        get() = prefs.getBoolean("use_history", true)
        set(v) = prefs.edit().putBoolean("use_history", v).apply()

    /** Track ids suggested during the last 3 days — pushed back so picks don't repeat. */
    private fun recent(): Set<Long> {
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        return prefs.getString("recent", "").orEmpty().split(';').mapNotNull { e ->
            val (id, at) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.takeIf { (at.toLongOrNull() ?: 0) > cutoff }
        }.toSet()
    }

    private fun remember(ids: List<Long>) {
        val now = System.currentTimeMillis()
        val cutoff = now - 3 * 86_400_000L
        val kept = prefs.getString("recent", "").orEmpty().split(';').filter { e ->
            (e.substringAfter(':', "0").toLongOrNull() ?: 0) > cutoff
        }
        val all = (kept + ids.map { "$it:$now" }).takeLast(400)
        prefs.edit().putString("recent", all.joinToString(";")).apply()
    }

    suspend fun ask(
        text: String,
        library: List<TrackEntity>,
        profiles: Map<Long, SoundProfile>,
        currentTrackId: Long?,
    ): AssistantAnswer = withContext(Dispatchers.Default) {
        val req = Recommender.understand(text, library, currentTrackId)
        val local = Recommender.recommend(req, library, profiles, recent(), useHistory)
        val cfg = config
        val answer = if (cfg.usable && local.picks.isNotEmpty()) {
            runCatching { askModel(cfg, text, req, library, profiles) }
                .fold(
                    onSuccess = { s -> if (s.picks.isEmpty()) AssistantAnswer(local, false, "ИИ не выбрал ни одного трека — показан локальный подбор") else AssistantAnswer(s, true) },
                    onFailure = { e -> AssistantAnswer(local, false, "ИИ недоступен (${e.message?.take(120)}). Показан локальный подбор.") },
                )
        } else {
            AssistantAnswer(local, false)
        }
        remember(answer.suggestion.picks.map { it.track.id })
        answer
    }

    private suspend fun askModel(
        cfg: AiConfig,
        text: String,
        req: MusicRequest,
        library: List<TrackEntity>,
        profiles: Map<Long, SoundProfile>,
    ): Suggestion = withContext(Dispatchers.IO) {
        // Shortlist: a wide local pick, so the model chooses only among the user's own tracks.
        val wide = Recommender.recommend(req.copy(minutes = 600), library, profiles, recent(), useHistory).picks.take(120)
        val byId = wide.associateBy { it.track.id }
        val catalog = wide.joinToString("\n") { p ->
            val pr = profiles[p.track.id]
            "${p.track.id}|${p.track.artist}|${p.track.title}|${p.track.genre}|энергия ${pr?.energy?.let { "%.2f".format(java.util.Locale.US, it) } ?: "?"}"
        }
        val taste = if (useHistory) {
            library.filter { it.favorite || it.playCount > 3 }.groupBy { it.artist }.entries
                .sortedByDescending { e -> e.value.sumOf { it.playCount } + e.value.count { it.favorite } * 5 }.take(8).joinToString(", ") { it.key }
        } else ""
        val system = "Ты — музыкальный помощник в плеере. Выбирай треки ТОЛЬКО из списка кандидатов (по id). " +
            "Ответь строго JSON: {\"title\":\"короткое название подборки\",\"intro\":\"1-2 предложения\",\"picks\":[{\"id\":число,\"why\":\"почему этот трек, до 12 слов\"}]}. " +
            "Нужно примерно ${req.minutes} минут музыки (12–30 треков), не ставь подряд одного исполнителя. Пиши по-русски."
        val user = buildString {
            append("Запрос: ").append(text).append('\n')
            if (taste.isNotBlank()) append("Любимые исполнители: ").append(taste).append('\n')
            append("Кандидаты (id|исполнитель|название|жанр|энергия 0-1):\n").append(catalog)
        }
        val json = chat(cfg, system, user)
        val picks = json.optJSONArray("picks") ?: JSONArray()
        val out = (0 until picks.length()).mapNotNull { i ->
            val o = picks.optJSONObject(i) ?: return@mapNotNull null
            val pick = byId[o.optLong("id")] ?: return@mapNotNull null // ignore anything not in the library
            Pick(pick.track, listOf(o.optString("why").ifBlank { pick.reasons.firstOrNull() ?: "" }).filter { it.isNotBlank() }, pick.score)
        }.distinctBy { it.track.id }
        Suggestion(json.optString("title").ifBlank { "Подборка" }, json.optString("intro"), out)
    }

    /** One request to the user's chat model; returns the JSON object from its reply. */
    private fun chat(cfg: AiConfig, system: String, user: String): JSONObject {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("temperature", 0.7)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        val conn = (URL(cfg.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
        }
        val reply = try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 401 || code == 403) error("ключ не подходит")
            if (code == 429) error("превышен лимит запросов")
            if (code !in 200..299) error("сервис ответил $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val content = JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        return JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1))
    }

    /**
     * New music and videos from the internet for the same request: songs from YouTube Music and
     * videos from YouTube, plus your own videos whose titles match. With an AI key the model names
     * concrete songs and video searches for your taste; otherwise the request itself is searched.
     */
    suspend fun discover(
        text: String,
        library: List<TrackEntity>,
        videos: List<VideoEntity>,
        currentTrack: TrackEntity?,
        web: WebSearch,
    ): Discovery = coroutineScope {
        val words = TrackListParser.key(text).split(' ').filter { it.length >= 4 && it !in stopWords }
        val localVideos = videos.filter { v -> val k = TrackListParser.key(v.title); words.any { it in k } }.take(12)
        val owned = library.map { TrackListParser.key(it.artist) + "|" + TrackListParser.key(it.title) }.toHashSet()
        fun notOwned(r: SearchResult) = (TrackListParser.key(r.author.substringBefore(" · ")) + "|" + TrackListParser.key(r.title)) !in owned

        val cfg = config
        if (cfg.usable) {
            val ai = runCatching {
                withContext(Dispatchers.IO) {
                    val taste = if (useHistory) library.filter { it.favorite || it.playCount > 3 }.groupBy { it.artist }.entries
                        .sortedByDescending { e -> e.value.sumOf { it.playCount } + e.value.count { it.favorite } * 5 }.take(10).joinToString(", ") { it.key } else ""
                    chat(
                        cfg,
                        "Ты — музыкальный помощник в плеере. Подбери под запрос реальные существующие песни и видео. " +
                            "Ответь строго JSON: {\"title\":\"название подборки\",\"songs\":[{\"artist\":\"...\",\"title\":\"...\",\"why\":\"до 10 слов\"}],\"videos\":[\"поисковый запрос для YouTube\"]}. " +
                            "15 песен разных исполнителей и 3 запроса для видео (клипы, лайвы, подборки). Пиши по-русски.",
                        buildString {
                            append("Запрос: ").append(text).append('\n')
                            currentTrack?.let { append("Сейчас играет: ").append(it.artist).append(" — ").append(it.title).append('\n') }
                            if (taste.isNotBlank()) append("Любимые исполнители: ").append(taste).append('\n')
                        },
                    )
                }
            }
            ai.getOrNull()?.let { json ->
                val songsJson = json.optJSONArray("songs") ?: JSONArray()
                val wanted = (0 until songsJson.length()).mapNotNull { songsJson.optJSONObject(it) }.take(18)
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                val songs = wanted.map { o ->
                    async {
                        gate.withPermit {
                            web.songs("${o.optString("artist")} ${o.optString("title")}").getOrNull()?.firstOrNull()
                                ?.let { OnlineFind(it, o.optString("why")) }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.result.url }
                val vq = json.optJSONArray("videos") ?: JSONArray()
                val vids = (0 until minOf(vq.length(), 4)).map { i ->
                    async { web.videos(vq.optString(i)).getOrNull().orEmpty().take(3) }
                }.awaitAll().flatten().distinctBy { it.url }
                if (songs.isNotEmpty() || vids.isNotEmpty()) {
                    return@coroutineScope Discovery(json.optString("title"), songs, vids, localVideos, byAi = true)
                }
            }
            val note = ai.exceptionOrNull()?.let { "ИИ недоступен (${it.message?.take(100)}) — показан обычный поиск" }
            return@coroutineScope plainDiscover(text, currentTrack, web, localVideos, ::notOwned).copy(note = note)
        }
        plainDiscover(text, currentTrack, web, localVideos, ::notOwned)
    }

    private suspend fun plainDiscover(
        text: String,
        currentTrack: TrackEntity?,
        web: WebSearch,
        localVideos: List<VideoEntity>,
        notOwned: (SearchResult) -> Boolean,
    ): Discovery = coroutineScope {
        val similar = currentTrack != null && listOf("похож", "этот трек", "такое же", "в том же духе").any { it in text.lowercase() }
        val songQuery = if (similar) currentTrack!!.artist else text
        val videoQuery = if (similar) "${currentTrack!!.artist} клип" else text
        val songs = async { web.songs(songQuery).getOrNull().orEmpty() }
        val vids = async { web.videos(videoQuery).getOrNull().orEmpty() }
        val list = songs.await().filter(notOwned).filter { !similar || it.title != currentTrack!!.title }.take(15)
            .map { OnlineFind(it, if (similar) "того же исполнителя" else "") }
        Discovery("", list, vids.await().take(8), localVideos, byAi = false)
    }

    private val stopWords = setOf("музыка", "музыку", "песни", "песню", "видео", "подбери", "хочу", "что", "нибудь", "какую", "какое", "для", "послушать", "посмотреть", "сделай", "составь", "плейлист")

    /** Quick connection check for the settings screen. */
    suspend fun test(cfg: AiConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(cfg.baseUrl.trimEnd('/') + "/models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
            }
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> "Подключение работает"
                    401, 403 -> error("Ключ не подходит")
                    else -> error("Сервис ответил $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
