package com.victorious.spoof.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Soundboard clip catalog. Every clip is synthesized on-device from code: no third-party audio, nothing to license. */
enum class Clip(val label: String) {
    AIR_HORN("Air horn"), RIMSHOT("Rimshot"), SAD_TROMBONE("Sad trombone"), DRUMROLL("Drum roll"),
    BOING("Boing"), DUCK("Duck"), SPOOFED_STING("You've been VictorSpoofed!"),
}

/** Procedural clip generator (mono 16-bit). Pure Kotlin so it is unit-testable. */
object ClipSynth {
    fun render(c: Clip, sr: Int): ShortArray = when (c) {
        Clip.AIR_HORN -> tones(sr, 0.9, listOf(415.0, 523.0, 622.0), saw = true, attack = 0.01)
        Clip.RIMSHOT -> concat(drum(sr, 0.12, 180.0), silence(sr, 0.08), drum(sr, 0.12, 200.0), silence(sr, 0.05), cymbal(sr, 0.6))
        Clip.SAD_TROMBONE -> concat(slide(sr, 0.35, 392.0, 370.0), slide(sr, 0.35, 370.0, 349.0), slide(sr, 0.35, 349.0, 330.0), slide(sr, 1.0, 330.0, 300.0, vibrato = 6.0))
        Clip.DRUMROLL -> concat(*Array(24) { drum(sr, 0.045, 160.0 + it * 3, gain = 0.3 + it * 0.025) }, cymbal(sr, 0.8))
        Clip.BOING -> slide(sr, 0.6, 120.0, 520.0, vibrato = 18.0)
        Clip.DUCK -> concat(quack(sr), silence(sr, 0.12), quack(sr))
        // Fanfare sting. The spoken "You've been VictorSpoofed!" line is layered on top at runtime by on-device
        // TextToSpeech when available (see StingVoice); this jingle always plays even without a TTS engine.
        Clip.SPOOFED_STING -> concat(note(sr, 0.12, 523.0), note(sr, 0.12, 659.0), note(sr, 0.12, 784.0), note(sr, 0.5, 1047.0, vibrato = 5.0))
    }

    private fun env(i: Int, n: Int, sr: Int, attack: Double = 0.005, release: Double = 0.05): Double {
        val a = (attack * sr).toInt().coerceAtLeast(1); val r = (release * sr).toInt().coerceAtLeast(1)
        return minOf(1.0, i.toDouble() / a, (n - i).toDouble() / r)
    }
    private fun silence(sr: Int, s: Double) = ShortArray((sr * s).toInt())
    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }; return out
    }
    private fun note(sr: Int, s: Double, f: Double, vibrato: Double = 0.0) = slide(sr, s, f, f, vibrato)
    private fun slide(sr: Int, s: Double, f0: Double, f1: Double, vibrato: Double = 0.0): ShortArray {
        val n = (sr * s).toInt(); var ph = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / n
            val f = (f0 + (f1 - f0) * t) * (1 + if (vibrato > 0) 0.02 * sin(2 * PI * vibrato * i / sr) else 0.0)
            ph += 2 * PI * f / sr
            val v = 0.6 * sin(ph) + 0.25 * sin(2 * ph) + 0.1 * sin(3 * ph)
            clip16(v * 0.5 * 32767 * env(i, n, sr))
        }
    }
    private fun tones(sr: Int, s: Double, fs: List<Double>, saw: Boolean, attack: Double): ShortArray {
        val n = (sr * s).toInt()
        return ShortArray(n) { i ->
            var v = 0.0
            for (f in fs) { val p = (i * f / sr) % 1.0; v += if (saw) 2 * p - 1 else sin(2 * PI * p) }
            clip16(v / fs.size * 0.5 * 32767 * env(i, n, sr, attack))
        }
    }
    private fun drum(sr: Int, s: Double, f: Double, gain: Double = 0.8): ShortArray {
        val n = (sr * s).toInt(); val r = Random(f.toInt())
        return ShortArray(n) { i ->
            val t = i.toDouble() / sr
            clip16((sin(2 * PI * f * t) * 0.6 + (r.nextDouble() * 2 - 1) * 0.4) * exp(-t * 35) * gain * 32767)
        }
    }
    private fun cymbal(sr: Int, s: Double): ShortArray {
        val n = (sr * s).toInt(); val r = Random(7); var prev = 0.0
        return ShortArray(n) { i ->
            val x = r.nextDouble() * 2 - 1; val hp = x - prev; prev = x
            clip16(hp * exp(-i.toDouble() / sr * 6) * 0.3 * 32767)
        }
    }
    private fun quack(sr: Int): ShortArray {
        val n = (sr * 0.22).toInt(); var ph = 0.0
        return ShortArray(n) { i ->
            val f = 600.0 - 300.0 * i / n; ph += 2 * PI * f / sr
            val sq = if (sin(ph) > 0) 1.0 else -1.0
            clip16(sq * 0.3 * 32767 * env(i, n, sr) * (0.6 + 0.4 * sin(2 * PI * 30 * i / sr)))
        }
    }

    /** Linear resampler used for TTS output that arrives at the engine's rate. */
    fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val pos = i.toDouble() * from / to; val k = pos.toInt(); val f = pos - k
            val a = src[k.coerceAtMost(src.size - 1)]; val b = src[(k + 1).coerceAtMost(src.size - 1)]
            clip16(a * (1 - f) + b * f)
        }
    }

    fun mixInto(dst: ShortArray, src: ShortArray, gain: Double = 1.0): ShortArray {
        val out = if (src.size > dst.size) dst.copyOf(src.size) else dst.copyOf()
        for (i in src.indices) out[i] = clip16(out[i] + src[i] * gain)
        return out
    }
}

/**
 * Lock-protected mixer queue. The UI thread calls [play]; the capture thread calls [mixInto] every 10 ms,
 * adding queued clip samples on top of the (already voice-changed) microphone signal.
 */
class SoundMixer {
    private class Voice(val pcm: ShortArray, var pos: Int = 0)
    private val voices = ArrayList<Voice>()
    @Volatile var volume: Double = 0.8
    var onFinished: (() -> Unit)? = null

    @Synchronized fun play(pcm: ShortArray) { if (voices.size < 4) voices += Voice(pcm) }
    @Synchronized fun stopAll() { voices.clear() }
    @Synchronized fun isPlaying() = voices.isNotEmpty()

    @Synchronized fun mixInto(buf: ShortArray, n: Int) {
        if (voices.isEmpty()) return
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            val m = minOf(n, v.pcm.size - v.pos)
            for (k in 0 until m) buf[k] = clip16(buf[k] + v.pcm[v.pos + k] * volume)
            v.pos += m
            if (v.pos >= v.pcm.size) it.remove()
        }
        if (voices.isEmpty()) onFinished?.invoke()
    }
}
