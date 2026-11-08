package app.vesper.player

import app.vesper.player.data.assistant.MusicAssistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRequestTest {
    @Test fun cleansCommands() {
        assertEquals("спокойным инструментальным джазом для расслабления или сна",
            MusicAssistant.cleanRequest("собери мне плейлист со спокойным инструментальным джазом для расслабления или сна"))
        assertEquals("для прогулки", MusicAssistant.cleanRequest("Для прогулки"))
        assertTrue(MusicAssistant.wantsPlaylist("собери мне плейлист"))
    }

    @Test fun detectsSeries() {
        assertEquals("prison break", MusicAssistant.screenTitle("собери мне плейлист, со всеми сериями и сезонами сериала prison break"))
        assertEquals("Интерстеллар", MusicAssistant.screenTitle("фильм «Интерстеллар»"))
        assertNull(MusicAssistant.screenTitle("спокойный джаз для сна"))
    }
}
