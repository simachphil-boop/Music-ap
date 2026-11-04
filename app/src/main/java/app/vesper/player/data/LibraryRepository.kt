package app.vesper.player.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import app.vesper.player.data.db.MediaSource
import app.vesper.player.data.db.PlaylistEntity
import app.vesper.player.data.db.PlaylistItemEntity
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.db.PlaylistSummary
import app.vesper.player.data.db.QueueItemEntity
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VesperDatabase
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.media.ArtworkLoader
import app.vesper.player.data.media.MediaImporter
import app.vesper.player.data.media.MediaStoreScanner
import app.vesper.player.data.media.ProbeResult
import app.vesper.player.data.prefs.SettingsRepository
import app.vesper.player.data.prefs.SortField
import app.vesper.player.data.prefs.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ImportReport(
    val addedAudio: Int = 0,
    val addedVideo: Int = 0,
    val duplicates: Int = 0,
    val unsupported: List<String> = emptyList(),
) {
    val added get() = addedAudio + addedVideo

    fun message(): String {
        val parts = mutableListOf<String>()
        if (addedAudio > 0) parts += "треков: $addedAudio"
        if (addedVideo > 0) parts += "видео: $addedVideo"
        val head = if (parts.isEmpty()) "Ничего нового не добавлено" else "Добавлено — " + parts.joinToString(", ")
        val tail = buildList {
            if (duplicates > 0) add("уже в библиотеке: $duplicates")
            if (unsupported.isNotEmpty()) add("пропущено: ${unsupported.size}")
        }
        return if (tail.isEmpty()) head else "$head (${tail.joinToString(", ")})"
    }
}

data class ScanReport(val tracks: Int, val videos: Int)

class LibraryRepository(
    private val context: Context,
    private val db: VesperDatabase,
    private val settings: SettingsRepository,
) {
    private val trackDao = db.tracks()
    private val videoDao = db.videos()
    private val playlistDao = db.playlists()
    private val queueDao = db.queue()
    private val scanMutex = Mutex()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val tracks: Flow<List<TrackEntity>> = trackDao.observeAll()
    val videos: Flow<List<VideoEntity>> = videoDao.observeAll()
    val playlists: Flow<List<PlaylistSummary>> = playlistDao.observeSummaries()
    val playlistItems: Flow<List<PlaylistItemEntity>> = playlistDao.observeAllItems()

    fun playlist(id: Long): Flow<PlaylistEntity?> = playlistDao.observe(id)
    fun playlistItems(id: Long): Flow<List<PlaylistItemEntity>> = playlistDao.observeItems(id)

    fun hasAudioPermission(): Boolean = granted(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    fun hasVideoPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------------------------------------
    // Device scan (MediaStore)
    // ---------------------------------------------------------------------------------------------

    suspend fun syncDeviceLibrary(): ScanReport = scanMutex.withLock {
        _scanning.value = true
        try {
            withContext(Dispatchers.IO) {
                val s = settings.current()
                var trackCount = 0
                var videoCount = 0
                if (hasAudioPermission()) {
                    val scanned = MediaStoreScanner.scanAudio(context, if (s.skipShortAudio) 30_000L else 0L)
                    trackCount = scanned.size
                    mergeTracks(scanned)
                }
                if (hasVideoPermission()) {
                    val scanned = MediaStoreScanner.scanVideo(context)
                    videoCount = scanned.size
                    mergeVideos(scanned)
                }
                playlistDao.pruneMissingTracks()
                playlistDao.pruneMissingVideos()
                ScanReport(trackCount, videoCount)
            }
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun mergeTracks(scanned: List<TrackEntity>) {
        val existing = trackDao.getAll().filter { it.source == MediaSource.MEDIA_STORE }.associateBy { it.uri }
        val inserts = ArrayList<TrackEntity>()
        val updates = ArrayList<TrackEntity>()
        for (t in scanned) {
            val old = existing[t.uri]
            if (old == null) {
                inserts += t
            } else {
                val merged = if (old.edited) {
                    old.copy(durationMs = t.durationMs, sizeBytes = t.sizeBytes, mimeType = t.mimeType)
                } else {
                    t.copy(
                        id = old.id,
                        favorite = old.favorite,
                        playCount = old.playCount,
                        lastPlayedAt = old.lastPlayedAt,
                        dateAdded = old.dateAdded.takeIf { it > 0 } ?: t.dateAdded,
                    )
                }
                if (merged != old) updates += merged
            }
        }
        val scannedUris = scanned.mapTo(HashSet()) { it.uri }
        val removed = existing.values.filter { it.uri !in scannedUris }.map { it.id }
        inserts.chunked(500).forEach { trackDao.insertAll(it) }
        updates.chunked(500).forEach { trackDao.updateAll(it) }
        removed.chunked(500).forEach { trackDao.deleteIds(it) }
    }

    private suspend fun mergeVideos(scanned: List<VideoEntity>) {
        val existing = videoDao.getAll().filter { it.source == MediaSource.MEDIA_STORE }.associateBy { it.uri }
        val inserts = ArrayList<VideoEntity>()
        val updates = ArrayList<VideoEntity>()
        for (v in scanned) {
            val old = existing[v.uri]
            if (old == null) {
                inserts += v
            } else {
                val merged = v.copy(
                    id = old.id,
                    title = old.title,
                    favorite = old.favorite,
                    lastPositionMs = old.lastPositionMs,
                    lastWatchedAt = old.lastWatchedAt,
                    dateAdded = old.dateAdded.takeIf { it > 0 } ?: v.dateAdded,
                )
                if (merged != old) updates += merged
            }
        }
        val scannedUris = scanned.mapTo(HashSet()) { it.uri }
        val removed = existing.values.filter { it.uri !in scannedUris }.map { it.id }
        inserts.chunked(500).forEach { videoDao.insertAll(it) }
        updates.chunked(500).forEach { videoDao.updateAll(it) }
        removed.chunked(500).forEach { videoDao.deleteIds(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // Import (Storage Access Framework)
    // ---------------------------------------------------------------------------------------------

    suspend fun importDocuments(uris: List<Uri>): ImportReport = withContext(Dispatchers.IO) {
        uris.forEach { MediaImporter.takePermission(context, it) }
        importProbed(uris.map { it to null }, "Импорт")
    }

    suspend fun importTree(treeUri: Uri): ImportReport = withContext(Dispatchers.IO) {
        MediaImporter.takePermission(context, treeUri)
        val folderName = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)?.name
        }.getOrNull() ?: "Папка"
        val files = MediaImporter.listTree(context, treeUri)
        importProbed(files.map { it.first to it.second }, folderName)
    }

    /** Opens a single file shared/opened from another app (ACTION_VIEW). */
    suspend fun importSingle(uri: Uri): ProbeResult = withContext(Dispatchers.IO) {
        MediaImporter.takePermission(context, uri)
        when (val r = MediaImporter.probe(context, uri)) {
            is ProbeResult.Audio -> {
                val existing = trackDao.getByUri(r.track.uri)
                if (existing != null) ProbeResult.Audio(existing)
                else {
                    val id = trackDao.insert(r.track)
                    ProbeResult.Audio(r.track.copy(id = id))
                }
            }
            is ProbeResult.Video -> {
                val existing = videoDao.getByUri(r.video.uri)
                if (existing != null) ProbeResult.Video(existing)
                else {
                    val id = videoDao.insert(r.video)
                    ProbeResult.Video(r.video.copy(id = id))
                }
            }
            is ProbeResult.Unsupported -> r
        }
    }

    private suspend fun importProbed(files: List<Pair<Uri, String?>>, folder: String): ImportReport {
        var audio = 0
        var video = 0
        var dup = 0
        val unsupported = ArrayList<String>()
        for ((uri, name) in files) {
            when (val r = MediaImporter.probe(context, uri, name, folder)) {
                is ProbeResult.Audio -> if (trackDao.insert(r.track) == -1L) dup++ else audio++
                is ProbeResult.Video -> if (videoDao.insert(r.video) == -1L) dup++ else video++
                is ProbeResult.Unsupported -> unsupported += "${r.name}: ${r.reason}"
            }
        }
        return ImportReport(audio, video, dup, unsupported)
    }

    suspend fun removeTracks(ids: List<Long>) = withContext(Dispatchers.IO) {
        ids.chunked(500).forEach { trackDao.deleteIds(it) }
        playlistDao.pruneMissingTracks()
    }

    suspend fun removeVideos(ids: List<Long>) = withContext(Dispatchers.IO) {
        ids.chunked(500).forEach { videoDao.deleteIds(it) }
        playlistDao.pruneMissingVideos()
    }

    // ---------------------------------------------------------------------------------------------
    // Favorites, history, edits
    // ---------------------------------------------------------------------------------------------

    suspend fun setTrackFavorite(id: Long, favorite: Boolean) = trackDao.setFavorite(id, favorite)
    suspend fun setVideoFavorite(id: Long, favorite: Boolean) = videoDao.setFavorite(id, favorite)
    suspend fun markPlayed(id: Long) = trackDao.markPlayed(id, System.currentTimeMillis())
    suspend fun saveVideoPosition(id: Long, positionMs: Long) =
        videoDao.savePosition(id, positionMs, System.currentTimeMillis())

    suspend fun editTrack(id: Long, title: String, artist: String, album: String, genre: String) =
        trackDao.editInfo(id, title.trim(), artist.trim(), album.trim(), genre.trim())

    suspend fun renameVideo(id: Long, title: String) = videoDao.rename(id, title.trim())

    suspend fun clearHistory() {
        trackDao.clearHistory()
        videoDao.clearHistory()
    }

    suspend fun track(id: Long): TrackEntity? = trackDao.get(id)
    suspend fun tracksByIds(ids: List<Long>): List<TrackEntity> {
        if (ids.isEmpty()) return emptyList()
        val map = ids.distinct().chunked(500).flatMap { trackDao.getByIds(it) }.associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    suspend fun video(id: Long): VideoEntity? = videoDao.get(id)
    suspend fun videosByIds(ids: List<Long>): List<VideoEntity> {
        if (ids.isEmpty()) return emptyList()
        val map = ids.distinct().chunked(500).flatMap { videoDao.getByIds(it) }.associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    // ---------------------------------------------------------------------------------------------
    // Playlists
    // ---------------------------------------------------------------------------------------------

    suspend fun createPlaylist(name: String, kind: Int, initial: List<Long> = emptyList()): Long {
        val now = System.currentTimeMillis()
        val id = playlistDao.insertPlaylist(
            PlaylistEntity(name = name.trim().ifBlank { "Новый плейлист" }, kind = kind, createdAt = now, updatedAt = now),
        )
        if (initial.isNotEmpty()) addToPlaylist(id, initial)
        return id
    }

    suspend fun renamePlaylist(id: Long, name: String) {
        val p = playlistDao.get(id) ?: return
        playlistDao.updatePlaylist(p.copy(name = name.trim().ifBlank { p.name }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePlaylist(id: Long) {
        playlistDao.get(id)?.coverPath?.let { runCatching { File(it).delete() } }
        playlistDao.deletePlaylist(id)
    }

    /** Adds media ids, skipping ones already present. Returns number actually added. */
    suspend fun addToPlaylist(playlistId: Long, mediaIds: List<Long>): Int {
        val existing = playlistDao.getItems(playlistId)
        val present = existing.mapTo(HashSet()) { it.mediaId }
        val toAdd = mediaIds.distinct().filter { it !in present }
        if (toAdd.isEmpty()) return 0
        val start = (existing.maxOfOrNull { it.position } ?: -1) + 1
        playlistDao.insertItems(toAdd.mapIndexed { i, m -> PlaylistItemEntity(playlistId = playlistId, mediaId = m, position = start + i) })
        playlistDao.touch(playlistId, System.currentTimeMillis())
        return toAdd.size
    }

    suspend fun removePlaylistItem(playlistId: Long, itemId: Long) {
        playlistDao.deleteItem(itemId)
        playlistDao.touch(playlistId, System.currentTimeMillis())
    }

    suspend fun reorderPlaylist(playlistId: Long, itemIdsInOrder: List<Long>) {
        playlistDao.reorder(itemIdsInOrder)
        playlistDao.touch(playlistId, System.currentTimeMillis())
    }

    suspend fun playlistMediaIds(playlistId: Long): List<Long> = playlistDao.getItems(playlistId).map { it.mediaId }

    /** Copies a downscaled cover image into app storage (picker grants are temporary). */
    suspend fun setPlaylistCover(playlistId: Long, imageUri: Uri?): Boolean = withContext(Dispatchers.IO) {
        val p = playlistDao.get(playlistId) ?: return@withContext false
        p.coverPath?.let { runCatching { File(it).delete() } }
        if (imageUri == null) {
            playlistDao.updatePlaylist(p.copy(coverPath = null, updatedAt = System.currentTimeMillis()))
            return@withContext true
        }
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 900 && bounds.outHeight / (sample * 2) >= 900) sample *= 2
            context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull() ?: return@withContext false
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "playlist_${playlistId}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        playlistDao.updatePlaylist(p.copy(coverPath = file.absolutePath, updatedAt = System.currentTimeMillis()))
        true
    }

    // ---------------------------------------------------------------------------------------------
    // Queue persistence (used by PlaybackService)
    // ---------------------------------------------------------------------------------------------

    suspend fun saveQueue(trackIds: List<Long>) {
        queueDao.replace(trackIds.mapIndexed { i, id -> QueueItemEntity(i, id) })
    }

    suspend fun loadQueue(): List<TrackEntity> = tracksByIds(queueDao.getAll().map { it.trackId })

    fun invalidateArtwork(uri: String) = ArtworkLoader.invalidate(uri)

    companion object {
        fun sortTracks(list: List<TrackEntity>, order: SortOrder): List<TrackEntity> {
            val cmp: Comparator<TrackEntity> = when (order.field) {
                SortField.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                SortField.ARTIST -> compareBy<TrackEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                    .thenBy { it.trackNumber }
                SortField.DATE_ADDED -> compareBy { it.dateAdded }
                SortField.DURATION -> compareBy { it.durationMs }
            }
            return list.sortedWith(if (order.ascending) cmp else cmp.reversed())
        }

        fun sortVideos(list: List<VideoEntity>, order: SortOrder): List<VideoEntity> {
            val cmp: Comparator<VideoEntity> = when (order.field) {
                SortField.TITLE, SortField.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                SortField.DATE_ADDED -> compareBy { it.dateAdded }
                SortField.DURATION -> compareBy { it.durationMs }
            }
            return list.sortedWith(if (order.ascending) cmp else cmp.reversed())
        }

        const val KIND_MUSIC = PlaylistKind.MUSIC
        const val KIND_VIDEO = PlaylistKind.VIDEO
    }
}
