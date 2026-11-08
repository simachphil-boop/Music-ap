package app.vesper.player.data.account

import android.content.Context
import androidx.room.withTransaction
import app.vesper.player.data.LibraryRepository
import app.vesper.player.data.db.ListenEntity
import app.vesper.player.data.db.OnlinePlaylistEntity
import app.vesper.player.data.db.PlaylistEntity
import app.vesper.player.data.db.PlaylistItemEntity
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VesperDatabase
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.movies.MovieCollection
import app.vesper.player.data.prefs.PlaylistFolders
import app.vesper.player.data.transfer.PlaylistTransfer
import app.vesper.player.playback.OnlineItem
import app.vesper.player.ui.theme.PlaylistSkins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class SyncStatus(
    val busy: Boolean = false,
    val lastSyncAt: Long = 0,
    val error: String? = null,
    /** Short result of the last sync, e.g. "Загружено из облака: 5 плейлистов". */
    val info: String? = null,
)

/**
 * Keeps the account's library in the cloud so it shows up on any phone after signing in:
 * playlists (with folders and skins), favourites, play counts, listening history, video
 * progress, playlists transferred from other services and the film collection.
 *
 * Audio and video files themselves are not uploaded. On another phone each track is matched to
 * the same song in that phone's library; songs that aren't there are played online (found on
 * YouTube by name). Nothing is lost: unmatched entries are kept and go back to the cloud.
 *
 * The copy is one gzip-compressed JSON split into ≤700 KB parts under `users/{uid}/sync/`.
 * Conflicts: if both this phone and the cloud changed since the last sync, both are merged
 * (nothing removed); otherwise the newer side wins.
 */
@OptIn(FlowPreview::class)
class CloudSync(
    private val context: Context,
    private val db: VesperDatabase,
    private val library: LibraryRepository,
    private val movies: MovieCollection,
    private val account: AccountManager,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(Profile.prefs("floya_sync"), Context.MODE_PRIVATE)
    private val pending = context.getSharedPreferences(Profile.prefs("floya_cloud_pending"), Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _status = MutableStateFlow(SyncStatus(lastSyncAt = prefs.getLong("last_sync", 0)))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private var watcher: Job? = null

    /** Starts automatic syncing for a signed-in account: pull now, push a little after every change. */
    fun start() {
        if (account.session.value == null || !account.configured || watcher != null) return
        scope.launch { sync(fromUser = false) }
        watcher = scope.launch {
            merge(
                db.playlists().observeSummaries(),
                db.playlists().observeAllItems(),
                db.tracks().observeAll(),
                db.videos().observeAll(),
                db.onlinePlaylists().observeAll(),
                movies.movies,
                PlaylistFolders.map,
                PlaylistSkins.choices,
            ).drop(8).debounce(60_000).collect { sync(fromUser = false) }
        }
    }

    /** Called when the app goes to the background: make sure the last changes reach the cloud. */
    fun flush() {
        if (account.session.value == null || !account.configured) return
        scope.launch { sync(fromUser = false) }
    }

    /** Full two-way sync. Returns a short human-readable result. */
    suspend fun sync(fromUser: Boolean = true): String? = withContext(Dispatchers.IO) {
        val session = account.session.value ?: return@withContext null
        lock.withLock {
            _status.update { it.copy(busy = true, error = null) }
            try {
                val token = account.token()
                val meta = Firebase.getDoc(token, session.uid, "meta")
                val remoteGen = (meta?.get("gen") as? Long) ?: 0L
                val knownGen = prefs.getLong("remote_gen", -1L)
                val local = buildSnapshot()
                val localHash = sha1(local.toString())
                val localChanged = localHash != prefs.getString("hash", null)
                var info: String? = null

                if (meta != null && remoteGen != knownGen) {
                    // The cloud has something this phone hasn't seen yet.
                    val remote = download(token, session.uid, meta)
                    val neverSynced = knownGen == -1L
                    // Both sides changed (or this phone brings its own data): merge, nothing is removed.
                    val mergeBoth = localChanged && !isEmptySnapshot(local)
                    if (neverSynced && library.hasAudioPermission()) runCatching { library.syncDeviceLibrary() }
                    val applied = apply(remote, merge = mergeBoth)
                    info = applied
                    val after = buildSnapshot()
                    val afterText = after.toString()
                    if (mergeBoth && sha1(afterText) != sha1(remote.toString())) {
                        upload(token, session.uid, afterText, meta)
                    } else {
                        prefs.edit().putLong("remote_gen", remoteGen).apply()
                    }
                    prefs.edit().putString("hash", sha1(afterText)).apply()
                } else if (localChanged || meta == null) {
                    if (meta == null && isEmptySnapshot(local)) {
                        prefs.edit().putString("hash", localHash).apply()
                    } else {
                        upload(token, session.uid, local.toString(), meta)
                        prefs.edit().putString("hash", localHash).apply()
                        if (fromUser) info = "Сохранено в облаке"
                    }
                } else if (fromUser) {
                    info = "Всё уже синхронизировано"
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_sync", now).apply()
                _status.value = SyncStatus(busy = false, lastSyncAt = now, info = info)
                info
            } catch (e: AccountException) {
                _status.update { it.copy(busy = false, error = e.message) }
                if (fromUser) throw e
                null
            } catch (e: Exception) {
                val msg = "Не удалось синхронизировать: ${e.message ?: e.javaClass.simpleName}"
                _status.update { it.copy(busy = false, error = msg) }
                if (fromUser) throw AccountException(msg)
                null
            }
        }
    }

    /** Removes everything this account stored in the cloud (before deleting the account). */
    suspend fun deleteRemote(token: String, uid: String) = withContext(Dispatchers.IO) {
        val meta = Firebase.getDoc(token, uid, "meta") ?: return@withContext
        val gen = meta["gen"] as? Long ?: 0L
        val parts = (meta["parts"] as? Long ?: 0L).toInt()
        repeat(parts) { Firebase.deleteDoc(token, uid, "g${gen}_$it") }
        Firebase.deleteDoc(token, uid, "meta")
    }

    // ---- transport -----------------------------------------------------------------------------

    private fun upload(token: String, uid: String, json: String, oldMeta: Map<String, Any>?) {
        val bytes = gzip(json)
        val gen = maxOf(System.currentTimeMillis(), ((oldMeta?.get("gen") as? Long) ?: 0L) + 1)
        val parts = (bytes.indices step PART).map { bytes.copyOfRange(it, minOf(it + PART, bytes.size)) }.ifEmpty { listOf(ByteArray(0)) }
        parts.forEachIndexed { i, p -> Firebase.putDoc(token, uid, "g${gen}_$i", mapOf("data" to p)) }
        Firebase.putDoc(
            token, uid, "meta",
            mapOf("gen" to gen, "parts" to parts.size.toLong(), "size" to bytes.size.toLong(), "device" to Profile.deviceId(context), "format" to 1L),
        )
        // Old parts are removed only after the new copy is complete.
        if (oldMeta != null) {
            val oldGen = oldMeta["gen"] as? Long ?: 0L
            val oldParts = (oldMeta["parts"] as? Long ?: 0L).toInt()
            if (oldGen != gen) repeat(oldParts) { runCatching { Firebase.deleteDoc(token, uid, "g${oldGen}_$it") } }
        }
        prefs.edit().putLong("remote_gen", gen).apply()
    }

    private fun download(token: String, uid: String, meta: Map<String, Any>): JSONObject {
        val gen = meta["gen"] as? Long ?: 0L
        val parts = (meta["parts"] as? Long ?: 0L).toInt()
        val out = ByteArrayOutputStream()
        repeat(parts) { i ->
            val d = Firebase.getDoc(token, uid, "g${gen}_$i") ?: throw AccountException("Облачная копия повреждена или ещё записывается — попробуйте позже")
            out.write(d["data"] as? ByteArray ?: ByteArray(0))
        }
        return JSONObject(gunzip(out.toByteArray()))
    }

    // ---- snapshot ------------------------------------------------------------------------------

    private suspend fun buildSnapshot(): JSONObject {
        val tracks = db.tracks().getAll()
        val trackById = tracks.associateBy { it.id }
        val videos = db.videos().getAll()
        val videoById = videos.associateBy { it.id }
        val items = db.playlists().getAllItems().groupBy { it.playlistId }
        val folders = PlaylistFolders.all(context)
        val skins = PlaylistSkins.all(context)
        val online = db.onlinePlaylists().getAll()

        val playlists = JSONArray()
        db.playlists().getAllPlaylists().forEach { p ->
            val arr = JSONArray()
            items[p.id].orEmpty().forEach { it ->
                if (p.kind == PlaylistKind.VIDEO) videoById[it.mediaId]?.let { v -> arr.put(videoJson(v)) }
                else trackById[it.mediaId]?.let { t -> arr.put(trackJson(t)) }
            }
            pendingArray("pl_${p.id}")?.let { extra -> for (i in 0 until extra.length()) arr.put(extra.get(i)) }
            playlists.put(
                JSONObject().put("n", p.name).put("k", p.kind).put("c", p.createdAt)
                    .put("f", folders[p.id] ?: "").put("s", skins[p.id] ?: "").put("i", arr),
            )
        }
        val favTracks = JSONArray()
        tracks.filter { it.favorite }.forEach { favTracks.put(trackJson(it)) }
        val onlineArr = JSONArray()
        online.forEach { o ->
            when (o.source) {
                SOURCE_CLOUD -> playlists.put(
                    JSONObject().put("n", o.name).put("k", PlaylistKind.MUSIC).put("c", o.createdAt).put("f", "").put("s", "")
                        .put("i", JSONArray().apply { PlaylistTransfer.decode(o.itemsJson).forEach { put(onlineJson(it)) } }),
                )
                SOURCE_CLOUD_FAV -> PlaylistTransfer.decode(o.itemsJson).forEach { favTracks.put(onlineJson(it)) }
                else -> onlineArr.put(
                    JSONObject().put("n", o.name).put("src", o.source).put("url", o.sourceUrl).put("cover", o.coverUrl ?: "")
                        .put("items", o.itemsJson).put("c", o.createdAt),
                )
            }
        }
        val favVideos = JSONArray()
        videos.filter { it.favorite }.forEach { favVideos.put(videoJson(it)) }
        pendingArray("fav_videos")?.let { for (i in 0 until it.length()) favVideos.put(it.get(i)) }

        val plays = JSONArray()
        tracks.filter { it.playCount > 0 }.sortedByDescending { it.lastPlayedAt }.take(3000).forEach {
            plays.put(trackJson(it).put("pc", it.playCount).put("lp", it.lastPlayedAt))
        }
        val videoPos = JSONArray()
        videos.filter { it.lastPositionMs > 0 }.sortedByDescending { it.lastWatchedAt }.take(500).forEach {
            videoPos.put(videoJson(it).put("p", it.lastPositionMs).put("w", it.lastWatchedAt))
        }
        val listens = JSONArray()
        db.listens().recent(1500).forEach {
            listens.put(JSONObject().put("k", it.mediaKey).put("t", it.title).put("a", it.artist).put("o", it.online).put("s", it.startedAt).put("m", it.listenedMs))
        }
        return JSONObject()
            .put("v", 1)
            .put("playlists", playlists)
            .put("favTracks", favTracks)
            .put("favVideos", favVideos)
            .put("online", onlineArr)
            .put("plays", plays)
            .put("videoPos", videoPos)
            .put("listens", listens)
            .put("movies", JSONArray(movies.exportJson()))
    }

    private fun isEmptySnapshot(s: JSONObject): Boolean =
        listOf("playlists", "favTracks", "favVideos", "online", "plays", "listens", "movies").all { (s.optJSONArray(it)?.length() ?: 0) == 0 }

    private fun trackJson(t: TrackEntity) = JSONObject()
        .put("t", t.title).put("a", t.artist).put("d", t.durationMs)
        .put("u", if (t.uri.startsWith("http")) t.uri else "").put("f", t.displayName)

    private fun videoJson(v: VideoEntity) = JSONObject().put("t", v.title).put("d", v.durationMs)

    private fun onlineJson(o: OnlineItem) = JSONObject()
        .put("t", o.title).put("a", o.artist).put("d", o.durationSec * 1000L)
        .put("u", if (o.pageUrl.startsWith("http")) o.pageUrl else "").put("f", "").put("th", o.thumbnail ?: "")

    private fun pendingArray(key: String): JSONArray? =
        pending.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() }?.takeIf { it.length() > 0 }

    // ---- applying the cloud copy ---------------------------------------------------------------

    /** Applies [s] to this phone. Returns a short summary. */
    private suspend fun apply(s: JSONObject, merge: Boolean): String {
        val tracks = db.tracks().getAll()
        val videos = db.videos().getAll()
        val tm = TrackIndex(tracks)
        val vm = videos.groupBy { key(it.title) }
        fun findVideo(o: JSONObject): VideoEntity? {
            val c = vm[key(o.optString("t"))].orEmpty()
            val d = o.optLong("d")
            return c.minByOrNull { kotlin.math.abs(it.durationMs - d) }
        }
        val now = System.currentTimeMillis()
        val newFolders = if (merge) PlaylistFolders.all(context).toMutableMap() else mutableMapOf()
        val newSkins = if (merge) PlaylistSkins.all(context).toMutableMap() else mutableMapOf()
        val pend = pending.edit()
        var playlistsAdded = 0
        var playOnline = 0

        db.withTransaction {
            val existingLocal = db.playlists().getAllPlaylists()
            val existingOnline = db.onlinePlaylists().getAll()
            if (!merge) {
                db.playlists().deleteAllItems()
                db.playlists().deleteAllPlaylistRows()
                db.onlinePlaylists().deleteAll()
                db.tracks().clearFavorites()
                db.videos().clearFavorites()
                pend.clear()
            }
            val takenNames = if (merge) existingLocal.map { it.kind to it.name.lowercase() }.toMutableSet() else mutableSetOf()
            val takenOnline = if (merge) existingOnline.map { it.name.lowercase() }.toMutableSet() else mutableSetOf()

            val pls = s.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until pls.length()) {
                val p = pls.getJSONObject(i)
                val name = p.optString("n").ifBlank { "Плейлист" }
                val kind = p.optInt("k")
                if (merge && ((kind to name.lowercase()) in takenNames || name.lowercase() in takenOnline)) continue
                val arr = p.optJSONArray("i") ?: JSONArray()
                val found = ArrayList<Long>()
                val missing = JSONArray()
                for (j in 0 until arr.length()) {
                    val o = arr.getJSONObject(j)
                    val id = if (kind == PlaylistKind.VIDEO) findVideo(o)?.id else tm.find(o)?.id
                    if (id != null) found += id else missing.put(o)
                }
                playlistsAdded++
                if (kind == PlaylistKind.MUSIC && found.isEmpty() && missing.length() > 0) {
                    val items = onlineItems(missing)
                    db.onlinePlaylists().insert(
                        OnlinePlaylistEntity(
                            name = name, source = SOURCE_CLOUD, sourceUrl = "", coverUrl = items.firstNotNullOfOrNull { it.thumbnail },
                            itemsJson = PlaylistTransfer.encode(items), itemCount = items.size, createdAt = p.optLong("c", now),
                        ),
                    )
                    playOnline++
                    takenOnline += name.lowercase()
                    continue
                }
                val id = db.playlists().insertPlaylist(PlaylistEntity(name = name, kind = kind, createdAt = p.optLong("c", now), updatedAt = now))
                if (found.isNotEmpty()) {
                    db.playlists().insertItems(found.mapIndexed { pos, m -> PlaylistItemEntity(playlistId = id, mediaId = m, position = pos) })
                }
                if (missing.length() > 0) pend.putString("pl_$id", missing.toString())
                p.optString("f").takeIf { it.isNotBlank() }?.let { newFolders[id] = it }
                p.optString("s").takeIf { it.isNotBlank() }?.let { newSkins[id] = it }
                takenNames += kind to name.lowercase()
            }

            val fav = s.optJSONArray("favTracks") ?: JSONArray()
            val favMissing = JSONArray()
            for (i in 0 until fav.length()) {
                val o = fav.getJSONObject(i)
                val t = tm.find(o)
                if (t != null) db.tracks().setFavorite(t.id, true) else favMissing.put(o)
            }
            if (favMissing.length() > 0) {
                val items = onlineItems(favMissing)
                val old = if (merge) db.onlinePlaylists().getAll().firstOrNull { it.source == SOURCE_CLOUD_FAV } else null
                val all = (old?.let { PlaylistTransfer.decode(it.itemsJson) }.orEmpty() + items).distinctBy { key(it.title) + "|" + key(it.artist) }
                old?.let { db.onlinePlaylists().delete(it.id) }
                db.onlinePlaylists().insert(
                    OnlinePlaylistEntity(
                        name = "Избранное из облака", source = SOURCE_CLOUD_FAV, sourceUrl = "", coverUrl = all.firstNotNullOfOrNull { it.thumbnail },
                        itemsJson = PlaylistTransfer.encode(all), itemCount = all.size, createdAt = now,
                    ),
                )
            }

            val favV = s.optJSONArray("favVideos") ?: JSONArray()
            val favVMissing = if (merge) pendingArray("fav_videos") ?: JSONArray() else JSONArray()
            for (i in 0 until favV.length()) {
                val o = favV.getJSONObject(i)
                val v = findVideo(o)
                if (v != null) db.videos().setFavorite(v.id, true) else favVMissing.put(o)
            }
            if (favVMissing.length() > 0) pend.putString("fav_videos", favVMissing.toString()) else pend.remove("fav_videos")

            val online = s.optJSONArray("online") ?: JSONArray()
            for (i in 0 until online.length()) {
                val o = online.getJSONObject(i)
                val name = o.optString("n")
                if (merge && name.lowercase() in takenOnline) continue
                val itemsJson = o.optString("items", "[]")
                db.onlinePlaylists().insert(
                    OnlinePlaylistEntity(
                        name = name, source = o.optString("src"), sourceUrl = o.optString("url"), coverUrl = o.optString("cover").ifBlank { null },
                        itemsJson = itemsJson, itemCount = PlaylistTransfer.decode(itemsJson).size, createdAt = o.optLong("c", now),
                    ),
                )
                takenOnline += name.lowercase()
            }

            val plays = s.optJSONArray("plays") ?: JSONArray()
            for (i in 0 until plays.length()) {
                val o = plays.getJSONObject(i)
                tm.find(o)?.let { db.tracks().mergePlayStats(it.id, o.optInt("pc"), o.optLong("lp")) }
            }
            val pos = s.optJSONArray("videoPos") ?: JSONArray()
            for (i in 0 until pos.length()) {
                val o = pos.getJSONObject(i)
                val v = findVideo(o) ?: continue
                if (o.optLong("w") > v.lastWatchedAt) db.videos().savePosition(v.id, o.optLong("p"), o.optLong("w"))
            }
            val listens = s.optJSONArray("listens") ?: JSONArray()
            if (listens.length() > 0) {
                var since = Long.MAX_VALUE
                for (i in 0 until listens.length()) since = minOf(since, listens.getJSONObject(i).optLong("s"))
                val have = db.listens().startTimes(since).toHashSet()
                for (i in 0 until listens.length()) {
                    val o = listens.getJSONObject(i)
                    if (o.optLong("s") in have) continue
                    db.listens().insert(
                        ListenEntity(mediaKey = o.optString("k"), title = o.optString("t"), artist = o.optString("a"), online = o.optBoolean("o"), startedAt = o.optLong("s"), listenedMs = o.optLong("m")),
                    )
                }
            }
        }
        pend.commit()
        PlaylistFolders.replaceAll(context, newFolders)
        PlaylistSkins.replaceAll(context, newSkins)
        s.optJSONArray("movies")?.let { movies.importJson(it.toString(), merge) }

        return buildString {
            append("Загружено из облака")
            if (playlistsAdded > 0) append(": плейлистов — $playlistsAdded")
            if (playOnline > 0) append(", из них $playOnline будут играть онлайн (этих песен нет на телефоне)")
        }
    }

    private fun onlineItems(arr: JSONArray): List<OnlineItem> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val t = o.optString("t")
        val a = o.optString("a")
        OnlineItem(
            pageUrl = o.optString("u").takeIf { it.startsWith("http") }
                ?: ("ytsearch1:" + listOf(a, t).filter { it.isNotBlank() && it != "<unknown>" }.joinToString(" - ") + " audio"),
            title = t,
            artist = a,
            durationSec = (o.optLong("d") / 1000).toInt(),
            thumbnail = o.optString("th").ifBlank { null },
        )
    }

    /** Finds the same song in this phone's library: by address, by "artist + title", by file name. */
    internal class TrackIndex(tracks: List<TrackEntity>) {
        private val byUri = tracks.associateBy { it.uri }
        private val byKey = tracks.groupBy { key(it.title) + "|" + key(it.artist) }
        private val byTitle = tracks.groupBy { key(it.title) }
        private val byFile = tracks.filter { it.displayName.isNotBlank() }.groupBy { it.displayName.lowercase() }

        fun find(o: JSONObject): TrackEntity? {
            val u = o.optString("u")
            if (u.isNotBlank()) byUri[u]?.let { return it }
            val d = o.optLong("d")
            fun List<TrackEntity>.closest() = minByOrNull { kotlin.math.abs(it.durationMs - d) }
            val t = key(o.optString("t"))
            val a = key(o.optString("a"))
            byKey["$t|$a"]?.closest()?.let { return it }
            // Same title with an unknown/blank artist on one side, and nearly the same length.
            if (t.isNotBlank()) byTitle[t]?.filter { (a.isBlank() || key(it.artist).isBlank()) && (d <= 0 || kotlin.math.abs(it.durationMs - d) < 4000) }?.closest()?.let { return it }
            val f = o.optString("f").lowercase()
            if (f.isNotBlank()) byFile[f]?.closest()?.let { return it }
            return null
        }
    }

    companion object {
        const val SOURCE_CLOUD = "cloud"
        const val SOURCE_CLOUD_FAV = "cloud-fav"
        private const val PART = 700_000

        internal fun key(s: String): String {
            val noBrackets = s.lowercase().replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            val plain = Normalizer.normalize(noBrackets, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").replace('ё', 'е')
            val k = plain.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
            return if (k == "unknown" || k == "неизвестен" || k == "неизвестный исполнитель") "" else k
        }

        internal fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        internal fun gunzip(bytes: ByteArray): String = GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

        internal fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
