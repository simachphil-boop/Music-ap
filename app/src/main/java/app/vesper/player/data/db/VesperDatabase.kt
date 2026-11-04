package app.vesper.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        VideoEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        QueueItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VesperDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun videos(): VideoDao
    abstract fun playlists(): PlaylistDao
    abstract fun queue(): QueueDao

    companion object {
        fun build(context: Context): VesperDatabase =
            Room.databaseBuilder(context, VesperDatabase::class.java, "vesper.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
