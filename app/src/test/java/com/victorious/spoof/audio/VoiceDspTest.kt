package com.victorious.spoof.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceDspTest {
    private val sr = 48_000
    private fun sine(f: Double, sec: Double = 1.0, amp: Double = 8000.0) = ShortArray((sr * sec).toInt()) { (amp * sin(2 * PI * f * it / sr)).toInt().toShort() }
    /** Process in 10 ms frames, exactly like VsAudioDevice. */
    private fun run(fx: AudioEffect, x: ShortArray): ShortArray {
        val out = x.copyOf(); val frame = ShortArray(480)
        var i = 0
        while (i + 480 <= out.size) { out.copyInto(frame, 0, i, i + 480); fx.process(frame, 480); frame.copyInto(out, i); i += 480 }
        return out
    }
    private fun dominantHz(x: ShortArray): Double {
        val from = x.size / 4; val to = x.size // skip the warm-up window
        return Analysis.zeroCrossings(x, from, to) / 2.0 / ((to - from).toDouble() / sr)
    }

    @Test fun normalIsBitExact() {
        val x = sine(440.0); assertArrayEquals(x, run(Effects.forPreset(VoicePreset.NORMAL, sr), x))
    }
    @Test fun chipmunkRaisesPitch() {
        val hz = dominantHz(run(Effects.forPreset(VoicePreset.CHIPMUNK, sr), sine(300.0)))
        assertTrue("got $hz", hz in 420.0..540.0) // ~1.6x of 300
    }
    @Test fun deepLowersPitch() {
        val hz = dominantHz(run(Effects.forPreset(VoicePreset.DEEP, sr), sine(400.0)))
        assertTrue("got $hz", hz in 240.0..320.0) // ~0.7x of 400
    }
    @Test fun echoRepeatsImpulseAfter280ms() {
        val x = ShortArray(sr); x[0] = 10000
        val y = run(Effects.forPreset(VoicePreset.ECHO, sr), x)
        val d = (0.28 * sr).toInt()
        assertEquals(10000, y[0].toInt())
        assertTrue("echo at ${d}: ${y[d]}", y[d] > 2000)
        assertTrue(y[d / 2].toInt() == 0)
    }
    @Test fun robotModulatesAmplitude() {
        val y = run(Effects.forPreset(VoicePreset.ROBOT, sr), sine(1000.0))
        assertNotEquals(sine(1000.0).toList(), y.toList())
        assertTrue(Analysis.peak(y) <= 32767)
    }
    @Test fun everyPresetIsStableAndClipsSafely() {
        for (p in VoicePreset.entries) {
            val y = run(Effects.forPreset(p, sr), sine(220.0, amp = 32000.0))
            assertTrue(p.name, y.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
            assertTrue("${p.name} silent", Analysis.peak(y) > 1000)
        }
    }
    @Test fun presetIdsStable() {
        assertEquals(VoicePreset.ROBOT, VoicePreset.fromId("Robot"))
        assertEquals(VoicePreset.NORMAL, VoicePreset.fromId("bogus"))
        assertEquals(VoicePreset.NORMAL, VoicePreset.fromId(null))
    }
}
