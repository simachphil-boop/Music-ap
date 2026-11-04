package app.vesper.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        VideoEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        QueueItemEntity::class,
        TrackFeaturesEntity::class,
        TrackStatsEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class VesperDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun videos(): VideoDao
    abstract fun playlists(): PlaylistDao
    abstract fun queue(): QueueDao
    abstract fun wave(): WaveDao

    companion object {
        /** v2 adds the Wave tables; existing library, playlists and history are kept. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_features` (`trackId` INTEGER NOT NULL, `energy` REAL NOT NULL, " +
                        "`dynamics` REAL NOT NULL, `brightness` REAL NOT NULL, `bpm` REAL NOT NULL, " +
                        "`tempoConfidence` REAL NOT NULL, `failed` INTEGER NOT NULL, `analyzedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`trackId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_stats` (`trackId` INTEGER NOT NULL, `skips` INTEGER NOT NULL, " +
                        "`completions` INTEGER NOT NULL, `banned` INTEGER NOT NULL, PRIMARY KEY(`trackId`))",
                )
            }
        }

        fun build(context: Context): VesperDatabase =
            Room.databaseBuilder(context, VesperDatabase::class.java, "vesper.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
