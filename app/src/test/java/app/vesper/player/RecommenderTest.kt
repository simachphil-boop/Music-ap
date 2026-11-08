package app.vesper.player

import app.vesper.player.data.assistant.Recommender
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.media.SmartLibrary
import app.vesper.player.data.wave.SoundProfile
import app.vesper.player.ui.theme.ThemeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecommenderTest {
    private fun track(id: Long, artist: String, title: String, genre: String = "", sec: Long = 200, fav: Boolean = false, plays: Int = 0, size: Long = 1000) =
        TrackEntity(
            id = id, uri = "content://media/$id", title = title, artist = artist, album = "A", genre = genre,
            durationMs = sec * 1000, trackNumber = 1, year = 2000, mimeType = "audio/mpeg", sizeBytes = size,
            dateAdded = id, source = 0, folder = "Music/", displayName = "$title.mp3", favorite = fav, playCount = plays,
        )

    private fun profile(e: Float, t: Float = e) = SoundProfile(energy = e, tempo = t, brightness = 0.5f, dynamics = 0.5f, estimated = false)

    private val lib = (1L..40L).map { i -> track(i, "Artist ${i % 8}", "Song $i", if (i % 2L == 0L) "Rock" else "Jazz") }
    private val profiles = lib.associate { it.id to profile((it.id % 10) / 10f) }

    @Test
    fun understandsActivitiesAndLength() {
        val walk = Recommender.understand("Подбери музыку для прогулки на час", lib, null)
        assertEquals("для прогулки", walk.label)
        assertEquals(60, walk.minutes)
        val gym = Recommender.understand("плейлист для тренировки 30 минут", lib, null)
        assertTrue(gym.energy!!.start >= 0.6f)
        assertEquals(30, gym.minutes)
        val similar = Recommender.understand("что-нибудь похожее на этот трек", lib, 5L)
        assertEquals(5L, similar.seedTrackId)
        val artist = Recommender.understand("хочу artist 3", lib, null)
        assertEquals(listOf("Artist 3"), artist.artists)
    }

    @Test
    fun calmRequestPicksQuietTracksWithReasons() {
        val req = Recommender.understand("спокойную музыку для вечера", lib, null)
        val s = Recommender.recommend(req, lib, profiles, emptySet(), useHistory = true, random = Random(1))
        assertTrue(s.picks.isNotEmpty())
        val avg = s.picks.map { profiles[it.track.id]!!.energy }.average()
        assertTrue("average energy $avg", avg < 0.5)
        assertTrue(s.picks.all { it.reasons.isNotEmpty() })
        // never the same artist twice in a row
        s.picks.zipWithNext().forEach { (a, b) -> assertFalse(a.track.artist == b.track.artist) }
    }

    @Test
    fun recentlySuggestedArePushedBack() {
        val req = Recommender.understand("бодрое", lib, null)
        val first = Recommender.recommend(req, lib, profiles, emptySet(), true, random = Random(2)).picks.map { it.track.id }
        val second = Recommender.recommend(req, lib, profiles, first.toSet(), true, random = Random(3)).picks.map { it.track.id }
        val overlap = first.intersect(second.toSet()).size
        assertTrue("overlap $overlap of ${first.size}", overlap < first.size)
    }

    @Test
    fun dedupeKeepsOneCopyButNotRemixes() {
        val a = track(1, "Кино", "Кукушка", sec = 397, size = 5_000)
        val b = track(2, "КИНО", "Кукушка", sec = 398, size = 9_000)
        val remix = track(3, "Кино", "Кукушка", sec = 520)
        val out = SmartLibrary.dedupe(listOf(a, b, remix))
        assertEquals(setOf(2L, 3L), out.map { it.id }.toSet())
    }

    @Test
    fun themeModes() {
        assertEquals("gothic", ThemeRegistry.resolve("gothic", 0, systemDark = false).id)
        assertEquals("minimal_light", ThemeRegistry.resolve("gothic", 1, systemDark = false).id)
        assertEquals("gothic", ThemeRegistry.resolve("gothic", 1, systemDark = true).id)
        assertEquals("gothic", ThemeRegistry.resolve("minimal_light", 1, systemDark = true).id)
        assertEquals("minimal_light", ThemeRegistry.resolve("forest", 2, systemDark = true).id)
    }
}
