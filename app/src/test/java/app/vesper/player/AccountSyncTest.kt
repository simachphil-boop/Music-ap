package app.vesper.player

import app.vesper.player.data.account.CloudSync
import app.vesper.player.data.account.Firebase
import app.vesper.player.data.account.Profile
import app.vesper.player.data.db.TrackEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSyncTest {
    private fun track(id: Long, title: String, artist: String, dur: Long, uri: String = "content://media/$id", file: String = "") =
        TrackEntity(id, uri, title, artist, "", "", dur, 0, 0, "audio/mpeg", 0, 0, 0, "", file)

    @Test fun gzipRoundTrip() {
        val text = "{\"playlists\":[{\"n\":\"Для прогулки\"}]}".repeat(200)
        assertEquals(text, CloudSync.gunzip(CloudSync.gzip(text)))
        assertTrue(CloudSync.gzip(text).size < text.toByteArray().size)
    }

    @Test fun matchesSameSongOnAnotherPhone() {
        val idx = CloudSync.TrackIndex(
            listOf(
                track(1, "Bohemian Rhapsody (Remastered 2011)", "Queen", 354_000),
                track(2, "Кино — Группа крови", "<unknown>", 285_000, file = "kino.mp3"),
                track(3, "Звезда по имени Солнце", "Кино", 225_000),
            ),
        )
        fun q(t: String, a: String, d: Long, f: String = "") = JSONObject().put("t", t).put("a", a).put("d", d).put("u", "").put("f", f)
        assertEquals(1L, idx.find(q("Bohemian Rhapsody", "QUEEN", 355_000))?.id)
        assertEquals(3L, idx.find(q("Звёзда по имени солнце", "кино", 0))?.id)
        assertEquals(2L, idx.find(q("x", "y", 0, "KINO.mp3"))?.id)
        assertNull(idx.find(q("Другая песня", "Кино", 200_000)))
    }

    @Test fun profilesKeepDataApart() {
        assertEquals("vesper.db", Profile.dbName(Profile.GUEST))
        assertEquals("vesper_abc123.db", Profile.dbName("abc123"))
        assertEquals("floya_wave_abc123", Profile.prefs("floya_wave", "abc123"))
        assertEquals("floya_wave", Profile.prefs("floya_wave", Profile.GUEST))
        assertEquals("vesper_ab.db", Profile.dbName("a/../b"))
    }

    @Test fun firebaseErrorsAreReadable() {
        assertEquals("Аккаунт с этой почтой уже есть — войдите", Firebase.explain(400, """{"error":{"message":"EMAIL_EXISTS"}}"""))
        assertEquals("Неверная почта или пароль", Firebase.explain(400, """{"error":{"message":"INVALID_LOGIN_CREDENTIALS"}}"""))
        assertTrue(Firebase.explain(400, """{"error":{"message":"WEAK_PASSWORD : Password should be at least 6 characters"}}""").startsWith("Пароль слишком простой"))
        assertEquals("Сервер временно недоступен, попробуйте позже", Firebase.explain(503, ""))
    }
}
