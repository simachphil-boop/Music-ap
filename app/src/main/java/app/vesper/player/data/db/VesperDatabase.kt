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
        OnlinePlaylistEntity::class,
        ListenEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class VesperDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun videos(): VideoDao
    abstract fun playlists(): PlaylistDao
    abstract fun queue(): QueueDao
    abstract fun wave(): WaveDao
    abstract fun onlinePlaylists(): OnlinePlaylistDao
    abstract fun listens(): ListenDao

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

        /** v3 adds playlists transferred from other services. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `online_playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `coverUrl` TEXT, " +
                        "`itemsJson` TEXT NOT NULL, `itemCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        /** v4 adds the listening log for statistics. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listen_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`mediaKey` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `online` INTEGER NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listen_log_startedAt` ON `listen_log` (`startedAt`)")
            }
        }

        fun build(context: Context): VesperDatabase =
            Room.databaseBuilder(context, VesperDatabase::class.java, app.vesper.player.data.account.Profile.dbName())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}
