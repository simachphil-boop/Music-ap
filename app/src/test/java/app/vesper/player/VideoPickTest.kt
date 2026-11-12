package app.vesper.player

import app.vesper.player.data.transfer.PlaylistSource
import app.vesper.player.playback.YouTubeFast
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPickTest {
    private fun video(itag: Int, mime: String, height: Int, bitrate: Long, fps: Int = 30) = JSONObject()
        .put("itag", itag).put("mimeType", mime).put("height", height).put("bitrate", bitrate).put("fps", fps).put("url", "https://v/$itag")

    private fun audio(itag: Int, mime: String, bitrate: Long, default: Boolean? = null) = JSONObject()
        .put("itag", itag).put("mimeType", mime).put("bitrate", bitrate).put("url", "https://a/$itag")
        .apply { if (default != null) put("audioTrack", JSONObject().put("audioIsDefault", default)) }

    private val formats = JSONArray()
        .put(video(313, "video/webm; codecs=\"vp9\"", 2160, 18_000_000))
        .put(video(248, "video/webm; codecs=\"vp9\"", 1080, 2_600_000))
        .put(video(137, "video/mp4; codecs=\"avc1.640028\"", 1080, 4_300_000))
        .put(video(299, "video/mp4; codecs=\"avc1.64002a\"", 1080, 6_500_000, fps = 60))
        .put(video(399, "video/mp4; codecs=\"av01.0.08M.08\"", 1080, 2_000_000))
        .put(video(136, "video/mp4; codecs=\"avc1.4d401f\"", 720, 2_300_000))
        .put(video(134, "video/mp4; codecs=\"avc1.4d401e\"", 360, 600_000))
        .put(audio(251, "audio/webm; codecs=\"opus\"", 160_000))
        .put(audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", 130_000))

    @Test fun prefersH264AtTheWantedHeight() {
        val (picture, sound, heights) = YouTubeFast.choose(formats, 1080, mp4Only = false)!!
        assertEquals(137, picture.getInt("itag")) // H.264, 30 fps — not VP9, AV1 or the 60 fps twin
        assertEquals(140, sound.getInt("itag"))
        assertEquals(listOf(2160, 1080, 720, 360), heights)
    }

    @Test fun staysUnderTheLimit() {
        assertEquals(136, YouTubeFast.choose(formats, 720, false)!!.first.getInt("itag"))
        assertEquals(134, YouTubeFast.choose(formats, 480, false)!!.first.getInt("itag"))
        // Nothing that small: the lowest available is still better than nothing.
        assertEquals(134, YouTubeFast.choose(formats, 144, false)!!.first.getInt("itag"))
    }

    @Test fun mp4OnlyRefusesWhatCannotBeSavedAsIs() {
        // 2160p exists only as VP9 → the quick download route steps aside.
        assertNull(YouTubeFast.choose(formats, 2160, mp4Only = true))
        assertEquals(137, YouTubeFast.choose(formats, 1080, mp4Only = true)!!.first.getInt("itag"))
    }

    @Test fun keepsOriginalLanguage() {
        val dubbed = JSONArray()
            .put(video(136, "video/mp4; codecs=\"avc1.4d401f\"", 720, 2_300_000))
            .put(audio(1, "audio/mp4; codecs=\"mp4a.40.2\"", 190_000, default = false))
            .put(audio(2, "audio/mp4; codecs=\"mp4a.40.2\"", 130_000, default = true))
        assertEquals(2, YouTubeFast.choose(dubbed, 720, false)!!.second.getInt("itag"))
    }

    @Test fun sourceLabelsAreHuman() {
        assertEquals("Из аккаунта", PlaylistSource.label("cloud"))
        assertEquals("Собрано подбором · играет онлайн", PlaylistSource.caption("assistant"))
        assertEquals("Перенесено из Spotify", PlaylistSource.caption("Spotify"))
    }
}
