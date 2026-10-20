package com.victorious.spoof.audio

import org.junit.Assert.*
import org.junit.Test

class SoundboardTest {
    @Test fun everyClipRendersNonSilentAndShort() {
        for (c in Clip.entries) {
            val pcm = ClipSynth.render(c, 48_000)
            assertTrue(c.name, pcm.size in 4_800..48_000 * 4)
            assertTrue("${c.name} silent", Analysis.peak(pcm) > 3000)
        }
    }
    @Test fun stingIsIncludedAndNamed() { assertEquals("You've been VictorSpoofed!", Clip.SPOOFED_STING.label) }

    @Test fun mixerAddsClipOnTopOfMicAndDrains() {
        val m = SoundMixer(); m.volume = 1.0
        var done = false; m.onFinished = { done = true }
        m.play(ShortArray(700) { 1000 })
        val f1 = ShortArray(480) { 50 }; m.mixInto(f1, 480)
        assertEquals(1050, f1[0].toInt())
        val f2 = ShortArray(480); m.mixInto(f2, 480)
        assertEquals(1000, f2[219].toInt()); assertEquals(0, f2[220].toInt())
        assertTrue(done); assertFalse(m.isPlaying())
    }
    @Test fun mixerClipsInsteadOfWrapping() {
        val m = SoundMixer(); m.volume = 1.0; m.play(ShortArray(10) { 30000 })
        val f = ShortArray(10) { 30000 }; m.mixInto(f, 10)
        assertEquals(32767, f[0].toInt())
    }
    @Test fun resampleChangesLength() {
        assertEquals(48_000, ClipSynth.resample(ShortArray(22_050) { 1 }, 22_050, 48_000).size)
    }
}
