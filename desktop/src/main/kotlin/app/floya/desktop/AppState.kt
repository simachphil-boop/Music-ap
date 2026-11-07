package app.floya.desktop

import app.floya.desktop.audio.AudioEngine
import app.floya.desktop.library.Library
import app.floya.desktop.online.Downloads
import app.floya.desktop.online.StreamResolver
import app.floya.desktop.theme.PlaylistSkins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the window shows, in one place. */
class AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settings = MutableStateFlow(Store.loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val library = Library()

    private val _playlists = MutableStateFlow(Store.loadPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val player = PlayerController(this)

    val downloads = Downloads(
        dirProvider = { File(settings.value.downloadDir) },
        mp3Provider = { settings.value.mp3 },
        onFinished = { file, video ->
            if (!video) library.add(file)
            say("Скачано: ${file.name}")
        },
    )

    private val history = Store.loadHistory()
    private val _recent = MutableStateFlow(history.first)
    val recent: StateFlow<List<Track>> = _recent.asStateFlow()
    private val counts = HashMap(history.second)

    init {
        PlaylistSkins.init(settings.value.skins)
        player.engine.volume = settings.value.volume
        player.engine.boost = settings.value.boost
        rescan()
    }

    fun rescan() = scope.launch { library.scan(settings.value.folders + settings.value.downloadDir) }

    fun updateSettings(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        scope.launch(Dispatchers.IO) { Store.saveSettings(s) }
    }

    fun say(text: String) {
        _message.value = text
        scope.launch {
            delay(3500)
            if (_message.value == text) _message.value = null
        }
    }

    // ---- playlists ---------------------------------------------------------------------------

    private fun savePlaylists(list: List<Playlist>) {
        _playlists.value = list
        scope.launch(Dispatchers.IO) { Store.savePlaylists(list) }
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Long {
        val id = System.currentTimeMillis()
        savePlaylists(_playlists.value + Playlist(id, name.ifBlank { "Новый плейлист" }, tracks, id))
        return id
    }

    fun renamePlaylist(id: Long, name: String) = savePlaylists(_playlists.value.map { if (it.id == id) it.copy(name = name) else it })

    fun deletePlaylist(id: Long) {
        savePlaylists(_playlists.value.filter { it.id != id })
        setSkin(id, null)
    }

    fun addToPlaylist(id: Long, tracks: List<Track>) {
        var added = 0
        savePlaylists(
            _playlists.value.map { p ->
                if (p.id != id) p else {
                    val have = p.tracks.map { it.id }.toSet()
                    val fresh = tracks.filter { it.id !in have }
                    added = fresh.size
                    p.copy(tracks = p.tracks + fresh)
                }
            },
        )
        val name = _playlists.value.firstOrNull { it.id == id }?.name.orEmpty()
        say(if (added == 0) "Уже есть в «$name»" else "Добавлено в «$name»")
    }

    fun removeFromPlaylist(id: Long, trackId: String) =
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(tracks = it.tracks.filter { t -> t.id != trackId }) else it })

    fun movePlaylistTrack(id: Long, from: Int, to: Int) =
        savePlaylists(_playlists.value.map { p -> if (p.id != id) p else p.copy(tracks = p.tracks.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) }) })

    fun setSkin(id: Long, skinId: String?) = PlaylistSkins.set(id, skinId) { map -> updateSettings { it.copy(skins = map) } }

    // ---- listening history ---------------------------------------------------------------------

    fun played(t: Track) {
        counts[t.id] = (counts[t.id] ?: 0) + 1
        _recent.value = (listOf(t) + _recent.value.filter { it.id != t.id }).take(60)
        val snapshot = _recent.value to HashMap(counts)
        scope.launch(Dispatchers.IO) { Store.saveHistory(snapshot.first, snapshot.second) }
    }

    fun playCount(id: String) = counts[id] ?: 0

    fun mostPlayed(all: List<Track>, n: Int): List<Track> =
        all.filter { (counts[it.id] ?: 0) > 0 }.sortedByDescending { counts[it.id] ?: 0 }.take(n)
}

/** Queue, shuffle/repeat and the bridge between tracks and the audio engine. */
class PlayerController(private val app: AppState) {
    val engine = AudioEngine(onEnded = { app.scope.launch { onEnded() } }, onError = { app.scope.launch { onError(it) } })

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private var loadJob: Job? = null
    private var errorsInRow = 0

    fun playList(list: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val order = if (shuffle) list.shuffled() else list
        _queue.value = order
        playAt(if (shuffle) 0 else start.coerceIn(0, order.lastIndex))
    }

    fun playAt(i: Int) {
        val q = _queue.value
        if (i !in q.indices) return
        _index.value = i
        val t = q[i]
        _current.value = t
        loadJob?.cancel()
        engine.stop()
        loadJob = app.scope.launch {
            val source = withContext(Dispatchers.IO) {
                runCatching {
                    if (t.path != null) t.path to emptyMap() else StreamResolver.resolve(t.url!!).let { it.url to it.headers }
                }
            }
            source.onSuccess { (src, headers) ->
                if (_current.value?.id != t.id) return@onSuccess
                engine.load(src, headers, 0, autoplay = true)
                app.played(t)
                prefetchNext()
            }.onFailure { onError(it.message ?: "Не удалось открыть трек") }
        }
    }

    private fun prefetchNext() {
        val next = _queue.value.getOrNull(_index.value + 1) ?: return
        val url = next.url ?: return
        app.scope.launch(Dispatchers.IO) { runCatching { StreamResolver.resolve(url) } }
    }

    fun toggle() {
        if (_current.value == null) {
            val lib = app.library.tracks.value
            if (lib.isNotEmpty()) playList(lib, shuffle = true)
            return
        }
        engine.toggle()
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val n = _index.value + 1
        if (n <= q.lastIndex) playAt(n) else if (app.settings.value.repeat == 1) playAt(0)
    }

    fun previous() {
        if (engine.position.value > 4000) {
            engine.seek(0)
            if (!engine.playing.value) engine.play()
            return
        }
        val p = _index.value - 1
        if (p >= 0) playAt(p) else engine.seek(0)
    }

    fun seek(ms: Long) = engine.seek(ms)

    fun addToQueue(tracks: List<Track>, next: Boolean = false) {
        if (_current.value == null) {
            playList(tracks)
            return
        }
        val q = _queue.value.toMutableList()
        if (next) q.addAll(_index.value + 1, tracks) else q.addAll(tracks)
        _queue.value = q
        app.say(if (next) "Сыграет следующим" else "Добавлено в очередь")
    }

    fun removeFromQueue(i: Int) {
        if (i == _index.value) return
        val q = _queue.value.toMutableList()
        if (i !in q.indices) return
        q.removeAt(i)
        _queue.value = q
        if (i < _index.value) _index.value = _index.value - 1
    }

    fun setVolume(v: Float) {
        engine.volume = v
        app.updateSettings { it.copy(volume = v) }
    }

    fun setBoost(b: Float) {
        engine.boost = b
        app.updateSettings { it.copy(boost = b) }
    }

    fun cycleRepeat() = app.updateSettings { it.copy(repeat = (it.repeat + 1) % 3) }

    fun shuffleQueue() {
        val q = _queue.value
        val cur = _current.value ?: return
        val rest = q.filterIndexed { i, _ -> i != _index.value }.shuffled()
        _queue.value = listOf(cur) + rest
        _index.value = 0
        app.say("Очередь перемешана")
    }

    private fun onEnded() {
        errorsInRow = 0
        when (app.settings.value.repeat) {
            2 -> { engine.seek(0); engine.play() }
            else -> {
                val q = _queue.value
                if (_index.value < q.lastIndex) playAt(_index.value + 1)
                else if (app.settings.value.repeat == 1 && q.isNotEmpty()) playAt(0)
            }
        }
    }

    private fun onError(msg: String) {
        app.say("Не удалось воспроизвести: $msg")
        errorsInRow++
        if (errorsInRow < 4 && _index.value < _queue.value.lastIndex) playAt(_index.value + 1)
    }
}
