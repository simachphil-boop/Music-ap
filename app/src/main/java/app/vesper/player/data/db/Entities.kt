package app.vesper.player.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a media item came from. MediaStore items are re-synced; imported items are kept as-is. */
object MediaSource {
    const val MEDIA_STORE = 0
    const val IMPORTED = 1
}

object PlaylistKind {
    const val MUSIC = 0
    const val VIDEO = 1
}

@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val source: Int,
    val folder: String,
    val displayName: String,
    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
    val edited: Boolean = false,
)

@Entity(tableName = "videos", indices = [Index(value = ["uri"], unique = true)])
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val dateAdded: Long,
    val source: Int,
    val folder: String,
    val favorite: Boolean = false,
    val lastPositionMs: Long = 0,
    val lastWatchedAt: Long = 0,
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (lastPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: Int,
    val coverPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val mediaId: Long,
    val position: Int,
)

/** Persisted playback queue (physical play order). */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val trackId: Long,
)

/** Playlist row with aggregate info for list screens. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val kind: Int,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val itemCount: Int,
)
