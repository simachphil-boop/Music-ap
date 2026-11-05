package app.vesper.player.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vesper.player.appContainer
import app.vesper.player.data.LibraryRepository
import app.vesper.player.data.db.PlaylistEntity
import app.vesper.player.data.db.PlaylistItemEntity
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.db.PlaylistSummary
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.media.ProbeResult
import app.vesper.player.data.prefs.AppSettings
import app.vesper.player.data.prefs.SortOrder
import app.vesper.player.data.wave.AnalysisState
import app.vesper.player.data.wave.Vibe
import app.vesper.player.playback.QueueEntry
import app.vesper.player.data.download.LinkInfo
import app.vesper.player.data.download.DownloadKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GroupType { ALBUM, ARTIST, GENRE }

data class TrackGroup(
    val type: GroupType,
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<TrackEntity>,
) {
    val artUri: String? get() = tracks.firstOrNull()?.uri
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }
}

data class LibraryState(
    val loaded: Boolean = false,
    val allTracks: List<TrackEntity> = emptyList(),
    val sortedTracks: List<TrackEntity> = emptyList(),
    val albums: List<TrackGroup> = emptyList(),
    val artists: List<TrackGroup> = emptyList(),
    val genres: List<TrackGroup> = emptyList(),
    val favorites: List<TrackEntity> = emptyList(),
    val recentlyAdded: List<TrackEntity> = emptyList(),
    val recentlyPlayed: List<TrackEntity> = emptyList(),
    val trackById: Map<Long, TrackEntity> = emptyMap(),
)

data class VideoState(
    val loaded: Boolean = false,
    val all: List<VideoEntity> = emptyList(),
    val sorted: List<VideoEntity> = emptyList(),
    val recent: List<VideoEntity> = emptyList(),
    val favorites: List<VideoEntity> = emptyList(),
    val byId: Map<Long, VideoEntity> = emptyMap(),
)

data class PlaylistEntry(val item: PlaylistItemEntity, val track: TrackEntity?, val video: VideoEntity?)

data class PlaylistDetail(val playlist: PlaylistEntity, val entries: List<PlaylistEntry>) {
    val tracks: List<TrackEntity> get() = entries.mapNotNull { it.track }
    val videos: List<VideoEntity> get() = entries.mapNotNull { it.video }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val repo = container.library
    private val settingsRepo = container.settings

    val player = container.player
    val sleepTimer = container.sleepTimer
    val audioEffects = container.audioEffects
    val wave = container.wave
    val analysis: StateFlow<AnalysisState> =
        container.analyzer.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisState())

    fun startWave(vibe: Vibe) = wave.start(vibe)

    val downloader = container.downloader
    val editor = container.editor
    val studio = container.studio

    /** Link received via "Share → Floya Music"; consumed by the downloads screen. */
    val sharedUrl = MutableStateFlow<String?>(null)

    fun updateDownloadEngine() {
        viewModelScope.launch { _messages.tryEmit(downloader.updateEngine()) }
    }
    fun reanalyzeLibrary() = container.analyzer.reanalyze()

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Null until the stored theme is read — avoids flashing the default theme on start. */
    val settingsLoaded: StateFlow<Boolean> =
        settingsRepo.settings.map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val scanning: StateFlow<Boolean> = repo.scanning

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _permissionVersion = MutableStateFlow(0)
    val permissionVersion: StateFlow<Int> = _permissionVersion.asStateFlow()

    val library: StateFlow<LibraryState> = combine(repo.tracks, settingsRepo.settings.map { it.musicSort }) { tracks, sort ->
        buildLibrary(tracks, sort)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    val videos: StateFlow<VideoState> = combine(repo.videos, settingsRepo.settings.map { it.videoSort }) { list, sort ->
        VideoState(
            loaded = true,
            all = list,
            sorted = LibraryRepository.sortVideos(list, sort),
            recent = list.filter { it.lastWatchedAt > 0 }.sortedByDescending { it.lastWatchedAt },
            favorites = list.filter { it.favorite }.sortedBy { it.title.lowercase() },
            byId = list.associateBy { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VideoState())

    val playlists: StateFlow<List<PlaylistSummary>> =
        repo.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** First few media ids per playlist — used for mosaic covers. */
    val playlistPreview: StateFlow<Map<Long, List<Long>>> = repo.playlistItems.map { items ->
        items.groupBy { it.playlistId }.mapValues { (_, v) -> v.sortedBy { it.position }.take(4).map { it.mediaId } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun buildLibrary(tracks: List<TrackEntity>, sort: SortOrder): LibraryState {
        val byTitle = String.CASE_INSENSITIVE_ORDER
        val albums = tracks.groupBy { "${it.album}\u2063${it.artist}" }
            .map { (key, list) ->
                val first = list.first()
                val year = list.maxOf { it.year }
                TrackGroup(
                    GroupType.ALBUM, key, first.album,
                    if (year > 0) "${first.artist} · $year" else first.artist,
                    list.sortedWith(compareBy<TrackEntity> { it.trackNumber }.thenBy(byTitle) { it.title }),
                )
            }
            .sortedWith(compareBy(byTitle) { it.title })
        val artists = tracks.groupBy { it.artist }
            .map { (artist, list) ->
                val albumCount = list.map { it.album }.distinct().size
                TrackGroup(
                    GroupType.ARTIST, artist, artist,
                    "${app.vesper.player.ui.components.plural(albumCount, "альбом", "альбома", "альбомов")} · ${app.vesper.player.ui.components.tracksCount(list.size)}",
                    list.sortedWith(compareBy<TrackEntity, String>(byTitle) { it.album }.thenBy { it.trackNumber }),
                )
            }
            .sortedWith(compareBy(byTitle) { it.title })
        val genres = tracks.groupBy { it.genre }
            .map { (genre, list) ->
                TrackGroup(GroupType.GENRE, genre, genre, app.vesper.player.ui.components.tracksCount(list.size), list.sortedWith(compareBy(byTitle) { it.title }))
            }
            .sortedWith(compareBy(byTitle) { it.title })
        return LibraryState(
            loaded = true,
            allTracks = tracks,
            sortedTracks = LibraryRepository.sortTracks(tracks, sort),
            albums = albums,
            artists = artists,
            genres = genres,
            favorites = tracks.filter { it.favorite }.sortedWith(compareBy(byTitle) { it.title }),
            recentlyAdded = tracks.sortedByDescending { it.dateAdded }.take(100),
            recentlyPlayed = tracks.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }.take(100),
            trackById = tracks.associateBy { it.id },
        )
    }

    fun group(type: GroupType, key: String): Flow<TrackGroup?> = library.map { lib ->
        when (type) {
            GroupType.ALBUM -> lib.albums
            GroupType.ARTIST -> lib.artists
            GroupType.GENRE -> lib.genres
        }.firstOrNull { it.key == key }
    }

    fun playlistDetail(id: Long): Flow<PlaylistDetail?> =
        combine(repo.playlist(id), repo.playlistItems(id), library, videos) { p, items, lib, vids ->
            if (p == null) null
            else PlaylistDetail(
                p,
                items.map { PlaylistEntry(it, lib.trackById[it.mediaId], vids.byId[it.mediaId]) }
                    .filter { if (p.kind == PlaylistKind.MUSIC) it.track != null else it.video != null },
            )
        }

    // ---------------------------------------------------------------------------------------------
    // Library actions
    // ---------------------------------------------------------------------------------------------

    fun onPermissionsChanged() {
        _permissionVersion.value++
        rescan(silent = true)
    }

    fun hasAudioPermission() = repo.hasAudioPermission()
    fun hasVideoPermission() = repo.hasVideoPermission()

    fun rescan(silent: Boolean = false) {
        viewModelScope.launch {
            if (!repo.hasAudioPermission() && !repo.hasVideoPermission()) {
                if (!silent) _messages.tryEmit("Нет доступа к медиатеке — разрешите доступ или импортируйте файлы вручную")
                return@launch
            }
            val r = runCatching { repo.syncDeviceLibrary() }
            container.analyzer.start()
            if (!silent) {
                r.onSuccess { _messages.tryEmit("Библиотека обновлена: ${it.tracks} аудио, ${it.videos} видео") }
                    .onFailure { _messages.tryEmit("Не удалось просканировать устройство") }
            }
        }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _messages.tryEmit("Импорт…")
            runCatching { repo.importDocuments(uris) }
                .onSuccess {
                    _messages.tryEmit(it.message())
                    container.analyzer.start()
                }
                .onFailure { _messages.tryEmit("Ошибка импорта: ${it.message ?: "неизвестная"}") }
        }
    }

    fun importTree(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _messages.tryEmit("Сканирую папку…")
            runCatching { repo.importTree(uri) }
                .onSuccess {
                    _messages.tryEmit(it.message())
                    container.analyzer.start()
                }
                .onFailure { _messages.tryEmit("Ошибка импорта папки: ${it.message ?: "неизвестная"}") }
        }
    }

    /** Handles ACTION_VIEW of an audio file from another app. */
    fun openExternalAudio(uri: Uri) {
        viewModelScope.launch {
            when (val r = repo.importSingle(uri)) {
                is ProbeResult.Audio -> player.playTracks(listOf(r.track), 0, shuffle = false)
                is ProbeResult.Video -> _messages.tryEmit("Это видео — откройте его в разделе «Видео»")
                is ProbeResult.Unsupported -> _messages.tryEmit("${r.name}: ${r.reason}")
            }
        }
    }

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch { repo.setTrackFavorite(track.id, !track.favorite) }
    }

    fun toggleFavoriteById(trackId: Long) {
        val t = library.value.trackById[trackId] ?: return
        toggleFavorite(t)
    }

    fun toggleVideoFavorite(video: VideoEntity) {
        viewModelScope.launch { repo.setVideoFavorite(video.id, !video.favorite) }
    }

    fun editTrack(id: Long, title: String, artist: String, album: String, genre: String) {
        viewModelScope.launch {
            repo.editTrack(id, title, artist, album, genre)
            _messages.tryEmit("Информация о треке сохранена")
        }
    }

    fun renameVideo(id: Long, title: String) {
        viewModelScope.launch { repo.renameVideo(id, title) }
    }

    fun removeTracksFromLibrary(ids: List<Long>) {
        viewModelScope.launch {
            repo.removeTracks(ids)
            _messages.tryEmit("Убрано из библиотеки (файл на устройстве не удалён)")
        }
    }

    fun removeVideosFromLibrary(ids: List<Long>) {
        viewModelScope.launch {
            repo.removeVideos(ids)
            _messages.tryEmit("Убрано из библиотеки (файл на устройстве не удалён)")
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repo.clearHistory()
            _messages.tryEmit("История очищена")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Playlists
    // ---------------------------------------------------------------------------------------------

    fun createPlaylist(name: String, kind: Int, initial: List<Long> = emptyList(), onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.createPlaylist(name, kind, initial)
            _messages.tryEmit(if (initial.isEmpty()) "Плейлист «$name» создан" else "Создан «$name» и добавлено: ${initial.size}")
            onCreated(id)
        }
    }

    fun addToPlaylist(playlist: PlaylistSummary, mediaIds: List<Long>) {
        viewModelScope.launch {
            val added = repo.addToPlaylist(playlist.id, mediaIds)
            _messages.tryEmit(
                when {
                    added == 0 -> "Уже есть в «${playlist.name}»"
                    added == 1 -> "Добавлено в «${playlist.name}»"
                    else -> "Добавлено в «${playlist.name}»: $added"
                },
            )
        }
    }

    fun addIdsToPlaylist(playlistId: Long, mediaIds: List<Long>) {
        viewModelScope.launch {
            val added = repo.addToPlaylist(playlistId, mediaIds)
            if (added > 0) _messages.tryEmit("Добавлено: $added")
        }
    }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { repo.renamePlaylist(id, name) }

    fun deletePlaylist(id: Long) = viewModelScope.launch {
        repo.deletePlaylist(id)
        _messages.tryEmit("Плейлист удалён")
    }

    fun removePlaylistItem(playlistId: Long, itemId: Long) = viewModelScope.launch { repo.removePlaylistItem(playlistId, itemId) }

    fun reorderPlaylist(playlistId: Long, itemIds: List<Long>) = viewModelScope.launch { repo.reorderPlaylist(playlistId, itemIds) }

    fun setPlaylistCover(playlistId: Long, uri: Uri?) = viewModelScope.launch {
        val ok = repo.setPlaylistCover(playlistId, uri)
        if (!ok) _messages.tryEmit("Не удалось загрузить изображение")
    }

    fun playPlaylist(playlistId: Long, shuffle: Boolean) {
        viewModelScope.launch {
            val ids = repo.playlistMediaIds(playlistId)
            val tracks = repo.tracksByIds(ids)
            if (tracks.isEmpty()) _messages.tryEmit("Плейлист пуст") else player.playTracks(tracks, 0, shuffle = shuffle)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------

    fun setTheme(id: String) = viewModelScope.launch { settingsRepo.setTheme(id) }
    fun setMusicSort(order: SortOrder) = viewModelScope.launch { settingsRepo.setMusicSort(order) }
    fun setVideoSort(order: SortOrder) = viewModelScope.launch { settingsRepo.setVideoSort(order) }
    fun setPauseOnDisconnect(v: Boolean) = viewModelScope.launch { settingsRepo.setPauseOnDisconnect(v) }
    fun setResumeOnConnect(v: Boolean) = viewModelScope.launch { settingsRepo.setResumeOnConnect(v) }
    fun setArtworkTint(v: Boolean) = viewModelScope.launch { settingsRepo.setArtworkTint(v) }
    fun setAutoRescan(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoRescan(v) }
    fun setSkipShortAudio(v: Boolean) = viewModelScope.launch {
        settingsRepo.setSkipShortAudio(v)
        if (repo.hasAudioPermission()) repo.syncDeviceLibrary()
    }
    fun setOnboardingDone() = viewModelScope.launch { settingsRepo.setOnboardingDone() }

    fun setEqualizerEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setEqualizerEnabled(v) }
    fun setEqualizerBands(levels: List<Int>) = viewModelScope.launch { settingsRepo.setEqualizerPreset(-1, levels) }
    fun applyEqualizerPreset(preset: Int) = viewModelScope.launch {
        val levels = audioEffects.presetLevels(preset) ?: emptyList()
        settingsRepo.setEqualizerPreset(preset, levels)
    }
    fun setBassBoost(v: Int) = viewModelScope.launch { settingsRepo.setBassBoost(v) }
    fun setVirtualizer(v: Int) = viewModelScope.launch { settingsRepo.setVirtualizer(v) }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun playerMessages() = player.messages

    /** Saves the internet track that is playing as an MP3 in the library. */
    fun downloadOnline(entry: QueueEntry) {
        val page = entry.pageUrl ?: return
        downloader.download(
            LinkInfo(
                url = page,
                title = entry.title,
                uploader = entry.artist,
                durationSec = (entry.durationMs / 1000).toInt(),
                thumbnail = entry.uri.ifBlank { null },
                source = "",
                heights = emptyList(),
            ),
            DownloadKind.AUDIO,
            null,
        )
        _messages.tryEmit("Скачиваю «${entry.title}» в MP3 — прогресс во вкладке «Загрузки»")
    }

    init {
        player.connect()
        container.analyzer.start()
        viewModelScope.launch { runCatching { repo.migrateDownloadFlags() } }
    }
}
