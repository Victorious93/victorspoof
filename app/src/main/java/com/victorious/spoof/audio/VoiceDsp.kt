package com.victorious.spoof.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Voice-changer presets. [id] is the persisted / exported value; keep it stable. */
enum class VoicePreset(val id: String, val label: String, val blurb: String) {
    NORMAL("Normal", "Normal", "Your own voice, untouched."),
    CHIPMUNK("Chipmunk", "Chipmunk", "Pitch up ~1.6x."),
    DEEP("Deep", "Deep", "Pitch down ~0.7x."),
    ROBOT("Robot", "Robot", "Ring-modulated metallic robot."),
    ECHO("Echo", "Echo", "Canyon echo, 280 ms feedback delay."),
    ALIEN("Alien", "Alien", "Pitch up + warble."),
    ;
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NORMAL
        val ids = entries.map { it.id }.toSet()
    }
}

/** Mono 16-bit PCM processor. Stateful: one instance per call. Allocation-free on the audio thread after construction. */
interface AudioEffect { fun process(buf: ShortArray, n: Int) }

object Effects {
    fun forPreset(p: VoicePreset, sampleRate: Int): AudioEffect = when (p) {
        VoicePreset.NORMAL -> object : AudioEffect { override fun process(buf: ShortArray, n: Int) {} }
        VoicePreset.CHIPMUNK -> PitchShifter(sampleRate, 1.6)
        VoicePreset.DEEP -> PitchShifter(sampleRate, 0.7)
        VoicePreset.ROBOT -> Chain(RingModulator(sampleRate, 55.0, 1.0), Comb(sampleRate, 0.006, 0.5))
        VoicePreset.ECHO -> Echo(sampleRate, 0.28, 0.45, 0.5)
        VoicePreset.ALIEN -> Chain(PitchShifter(sampleRate, 1.25), RingModulator(sampleRate, 7.0, 0.35))
    }
}

class Chain(private vararg val fx: AudioEffect) : AudioEffect {
    override fun process(buf: ShortArray, n: Int) = fx.forEach { it.process(buf, n) }
}

internal fun clip16(v: Double): Short = when { v > 32767 -> 32767; v < -32768 -> -32768; else -> v.toInt() }.toShort()

/**
 * Delay-line ("Doppler") pitch shifter: two read taps sweep through a short window at rate (1 - ratio),
 * crossfaded with a raised-cosine so the wrap-around is inaudible. Cheap, low-latency (~window), real-time.
 */
class PitchShifter(sampleRate: Int, private val ratio: Double, windowSec: Double = 0.040) : AudioEffect {
    private val win = (sampleRate * windowSec).toInt().coerceAtLeast(64)
    private val size = win * 2 + 4
    private val ring = DoubleArray(size)
    private var w = 0
    private var phase = 0.0 // 0..1 over the window

    private fun read(delay: Double): Double {
        var pos = w - delay
        while (pos < 0) pos += size
        val i = pos.toInt(); val f = pos - i
        return ring[i % size] * (1 - f) + ring[(i + 1) % size] * f
    }

    override fun process(buf: ShortArray, n: Int) {
        val step = (1.0 - ratio) / win
        for (k in 0 until n) {
            ring[w] = buf[k].toDouble()
            val p1 = phase; val p2 = (phase + 0.5) % 1.0
            val d1 = 1 + p1 * win; val d2 = 1 + p2 * win
            val g1 = 0.5 - 0.5 * cos(2 * PI * p1); val g2 = 0.5 - 0.5 * cos(2 * PI * p2)
            buf[k] = clip16(read(d1) * g1 + read(d2) * g2)
            phase += step; if (phase >= 1) phase -= 1; if (phase < 0) phase += 1
            w = (w + 1) % size
        }
    }
}

class RingModulator(private val sampleRate: Int, private val freq: Double, private val depth: Double) : AudioEffect {
    private var t = 0L
    override fun process(buf: ShortArray, n: Int) {
        for (k in 0 until n) {
            val m = (1 - depth) + depth * sin(2 * PI * freq * t++ / sampleRate)
            buf[k] = clip16(buf[k] * m)
        }
        if (t > sampleRate.toLong() * 3600) t = 0
    }
}

class Comb(sampleRate: Int, delaySec: Double, private val fb: Double) : AudioEffect {
    private val d = DoubleArray((sampleRate * delaySec).toInt().coerceAtLeast(1)); private var i = 0
    override fun process(buf: ShortArray, n: Int) {
        for (k in 0 until n) { val y = buf[k] + fb * d[i]; d[i] = y; i = (i + 1) % d.size; buf[k] = clip16(y * 0.7) }
    }
}

class Echo(sampleRate: Int, delaySec: Double, private val feedback: Double, private val mix: Double) : AudioEffect {
    private val d = DoubleArray((sampleRate * delaySec).toInt().coerceAtLeast(1)); private var i = 0
    override fun process(buf: ShortArray, n: Int) {
        for (k in 0 until n) {
            val dry = buf[k].toDouble(); val wet = d[i]
            d[i] = dry + wet * feedback; i = (i + 1) % d.size
            buf[k] = clip16(dry + wet * mix)
        }
    }
}

/** Simple analysis helper used by tests and the level meter. */
object Analysis {
    fun zeroCrossings(b: ShortArray, from: Int = 0, to: Int = b.size): Int {
        var c = 0; for (k in from + 1 until to) if ((b[k - 1] < 0) != (b[k] < 0)) c++; return c
    }
    fun peak(b: ShortArray) = b.maxOf { abs(it.toInt()) }
}
