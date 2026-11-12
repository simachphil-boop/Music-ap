package app.vesper.player

import app.vesper.player.data.wave.OnlineWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveMixTest {
    @Test fun threeByTasteThenOnePopular() {
        val mixed = OnlineWave.interleave(listOf("t1", "t2", "t3", "t4", "t5", "t6", "t7"), listOf("p1", "p2", "p3"))
        assertEquals(listOf("t1", "t2", "t3", "p1", "t4", "t5", "t6", "p2", "t7", "p3"), mixed)
    }

    @Test fun newListenerGetsPopular() {
        assertEquals(listOf("p1", "p2"), OnlineWave.interleave(emptyList(), listOf("p1", "p2")))
    }

    @Test fun remixIsASongButMixIsNot() {
        assertFalse(OnlineWave.isCompilation("Bad Guy (Remix)"))
        assertTrue(OnlineWave.isCompilation("Summer Mix 2026"))
        assertTrue(OnlineWave.isCompilation("Лучший микс для дороги"))
        assertTrue(OnlineWave.isCompilation("Rock Playlist"))
    }
}
