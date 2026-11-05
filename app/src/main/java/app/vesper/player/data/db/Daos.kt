package app.vesper.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<TrackEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: TrackEntity): Long

    @Update
    suspend fun updateAll(items: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE tracks SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun markPlayed(id: Long, timestamp: Long)

    @Query("UPDATE tracks SET title = :title, artist = :artist, album = :album, genre = :genre, edited = 1 WHERE id = :id")
    suspend fun editInfo(id: Long, title: String, artist: String, album: String, genre: String)

    @Query("UPDATE tracks SET source = 2 WHERE id = :id")
    suspend fun markDownloaded(id: Long)

    /** Earlier versions stored downloads as plain imports; they live in MediaStore or a /Floya/ folder. */
    @Query("UPDATE tracks SET source = 2 WHERE source = 1 AND (uri LIKE 'content://media/%' OR uri LIKE 'file://%/Floya/%')")
    suspend fun migrateDownloadFlags()

    @Query("UPDATE tracks SET lastPlayedAt = 0, playCount = 0")
    suspend fun clearHistory()
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun get(id: Long): VideoEntity?

    @Query("SELECT * FROM videos WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<VideoEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: VideoEntity): Long

    @Update
    suspend fun updateAll(items: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE videos SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE videos SET lastPositionMs = :positionMs, lastWatchedAt = :timestamp WHERE id = :id")
    suspend fun savePosition(id: Long, positionMs: Long, timestamp: Long)

    @Query("UPDATE videos SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE videos SET lastWatchedAt = 0, lastPositionMs = 0")
    suspend fun clearHistory()
}

@Dao
abstract class PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.kind, p.coverPath, p.createdAt, p.updatedAt,
               (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount
        FROM playlists p
        ORDER BY p.updatedAt DESC
        """,
    )
    abstract fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    abstract fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position ASC")
    abstract fun observeAllItems(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    abstract suspend fun getItems(playlistId: Long): List<PlaylistItemEntity>

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    abstract suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylist(id: Long)

    @Insert
    abstract suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    abstract suspend fun deleteItem(itemId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    abstract suspend fun clearItems(playlistId: Long)

    @Query("UPDATE playlist_items SET position = :position WHERE id = :itemId")
    abstract suspend fun setItemPosition(itemId: Long, position: Int)

    @Query("UPDATE playlists SET updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun touch(id: Long, timestamp: Long)

    @Query(
        """
        DELETE FROM playlist_items WHERE playlistId IN (SELECT id FROM playlists WHERE kind = 0)
        AND mediaId NOT IN (SELECT id FROM tracks)
        """,
    )
    abstract suspend fun pruneMissingTracks()

    @Query(
        """
        DELETE FROM playlist_items WHERE playlistId IN (SELECT id FROM playlists WHERE kind = 1)
        AND mediaId NOT IN (SELECT id FROM videos)
        """,
    )
    abstract suspend fun pruneMissingVideos()

    @Transaction
    open suspend fun reorder(itemIdsInOrder: List<Long>) {
        itemIdsInOrder.forEachIndexed { index, itemId -> setItemPosition(itemId, index) }
    }
}

@Dao
abstract class QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    abstract suspend fun getAll(): List<QueueItemEntity>

    @Query("DELETE FROM queue_items")
    abstract suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<QueueItemEntity>)

    @Transaction
    open suspend fun replace(items: List<QueueItemEntity>) {
        clear()
        if (items.isNotEmpty()) insertAll(items)
    }
}

@Dao
interface WaveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeatures(features: TrackFeaturesEntity)

    @Query("SELECT * FROM track_features")
    suspend fun allFeatures(): List<TrackFeaturesEntity>

    @Query("SELECT COUNT(*) FROM track_features f INNER JOIN tracks t ON t.id = f.trackId")
    fun observeAnalyzedCount(): Flow<Int>

    @Query(
        """
        SELECT t.* FROM tracks t LEFT JOIN track_features f ON f.trackId = t.id
        WHERE f.trackId IS NULL
        ORDER BY t.favorite DESC, t.lastPlayedAt DESC, t.playCount DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingTracks(limit: Int): List<TrackEntity>

    @Query("DELETE FROM track_features")
    suspend fun clearFeatures()

    @Query("SELECT * FROM track_stats")
    suspend fun allStats(): List<TrackStatsEntity>

    @Query("INSERT OR IGNORE INTO track_stats (trackId, skips, completions, banned) VALUES (:trackId, 0, 0, 0)")
    suspend fun ensureStats(trackId: Long)

    @Query("UPDATE track_stats SET skips = skips + 1 WHERE trackId = :trackId")
    suspend fun incrementSkips(trackId: Long)

    @Query("UPDATE track_stats SET completions = completions + 1 WHERE trackId = :trackId")
    suspend fun incrementCompletions(trackId: Long)

    @Query("UPDATE track_stats SET banned = :banned WHERE trackId = :trackId")
    suspend fun setBanned(trackId: Long, banned: Boolean)

    @Query("DELETE FROM track_stats")
    suspend fun clearStats()

    @Query("SELECT * FROM track_features WHERE trackId = :trackId")
    suspend fun features(trackId: Long): TrackFeaturesEntity?

    @Query("SELECT COUNT(*) FROM track_stats WHERE banned = 1")
    fun observeBannedCount(): Flow<Int>
}
