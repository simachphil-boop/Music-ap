package app.vesper.player

import app.vesper.player.data.download.Downloader
import app.vesper.player.data.movies.MovieListParser
import app.vesper.player.data.transfer.ImportedTrack
import app.vesper.player.data.transfer.TrackListParser
import app.vesper.player.data.transfer.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportAndMatchTest {

    @Test
    fun plainTextListWithNumbersAndDurations() {
        val text = """
            1. Кино — Группа крови 4:46
            2) Miles Davis - So What
            Земфира – Хочешь? (3:23)
            # comment
            Bohemian Rhapsody by Queen
            Просто название
        """.trimIndent()
        val list = TrackListParser.parse(text)
        assertEquals(5, list.size)
        assertEquals(ImportedTrack("Кино", "Группа крови", 286), list[0])
        assertEquals("Miles Davis", list[1].artist)
        assertEquals("So What", list[1].title)
        assertEquals(203, list[2].durationSec)
        assertEquals("Queen", list[3].artist)
        assertEquals("", list[4].artist)
    }

    @Test
    fun csvExportWithQuotes() {
        val csv = "Track Name,Artist Name(s),Duration (ms)\n\"Hello, World\",Some Band,215000\nNumb,Linkin Park,185000\n"
        val list = TrackListParser.parse(csv, "export.csv")
        assertEquals(2, list.size)
        assertEquals("Hello, World", list[0].title)
        assertEquals("Some Band", list[0].artist)
        assertEquals(215, list[0].durationSec)
    }

    @Test
    fun russianSemicolonTable() {
        val csv = "Исполнитель;Название\nКино;Звезда по имени Солнце\nСплин;Выхода нет"
        val list = TrackListParser.parse(csv)
        assertEquals(2, list.size)
        assertEquals("Сплин", list[1].artist)
    }

    @Test
    fun jsonExportLikeYandex() {
        val json = """{"tracks":[{"title":"Белые розы","artists":[{"name":"Ласковый май"}],"durationMs":250000},
            {"title":"Кукушка","artists":[{"name":"Кино"}],"durationMs":397000}],"title":"Мой плейлист"}"""
        val list = TrackListParser.parse(json, "data.json")
        assertEquals(2, list.size)
        assertEquals("Ласковый май", list[0].artist)
        assertEquals(397, list[1].durationSec)
    }

    @Test
    fun matcherPrefersOriginalOverCoverAndLive() {
        val want = ImportedTrack("Кино", "Группа крови", 286)
        val original = TrackMatcher.score(want, "Группа крови", "Кино", 285)
        val live = TrackMatcher.score(want, "Группа крови (Live)", "Кино", 300)
        val cover = TrackMatcher.score(want, "Группа крови кавер", "Some Guy", 250)
        val other = TrackMatcher.score(want, "Звезда по имени Солнце", "Кино", 225)
        assertTrue("original $original", original >= TrackMatcher.SURE)
        assertTrue(original > live)
        assertTrue(cover < TrackMatcher.SURE)
        assertTrue("other $other", other < TrackMatcher.MAYBE)
    }

    @Test
    fun keyIgnoresCaseAccentsBrackets() {
        assertEquals(TrackListParser.key("Beyoncé – Halo (Remastered 2011)"), TrackListParser.key("BEYONCE halo"))
        assertEquals("еж", TrackListParser.key("Ёж"))
    }

    @Test
    fun movieLines() {
        val list = MovieListParser.parse(
            """
            Интерстеллар (2014)
            Брат, 1997
            https://www.kinopoisk.ru/film/326/
            3. Амели
            """.trimIndent(),
        )
        assertEquals(4, list.size)
        assertEquals("Интерстеллар", list[0].title)
        assertEquals(2014, list[0].year)
        assertEquals(1997, list[1].year)
        assertEquals(326L, list[2].kinopoiskId)
        assertNull(list[3].year)
        assertEquals("Амели", list[3].title)
    }

    @Test
    fun movieTableAndJson() {
        val t = MovieListParser.parse("Название;Год\nМатрица;1999\nОстров;2006")
        assertEquals(2, t.size)
        assertEquals(1999, t[0].year)
        val j = MovieListParser.parse("""{"films":[{"nameRu":"Москва слезам не верит","year":1979,"filmId":42664}]}""", "kp.json")
        assertEquals(1, j.size)
        assertEquals(42664L, j[0].kinopoiskId)
    }

    @Test
    fun ytDlpSpeedLine() {
        assertEquals((1.2 * 1024 * 1024).toLong(), Downloader.parseSpeed("[download]  42.0% of 5.31MiB at  1.20MiB/s ETA 00:03"))
        assertEquals(512L * 1024, Downloader.parseSpeed("[download] 10% of 1MiB at 512.00KiB/s ETA 00:01"))
        assertNull(Downloader.parseSpeed("[ExtractAudio] Destination: x.m4a"))
    }
}
