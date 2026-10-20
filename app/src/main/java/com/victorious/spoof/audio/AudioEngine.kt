package com.victorious.spoof.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.victorious.spoof.data.VoicePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the live voice preset, the soundboard mixer and the clip cache shared by the UI and [VsAudioDevice]. */
@Singleton
class AudioEngine @Inject constructor(@ApplicationContext private val ctx: Context, private val prefs: VoicePrefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val preset: StateFlow<VoicePreset> = prefs.preset.stateIn(scope, SharingStarted.Eagerly, VoicePreset.NORMAL)
    val mixer = SoundMixer()
    private val cache = mutableMapOf<Clip, ShortArray>()
    @Volatile private var spokenSting: ShortArray? = null

    fun setPreset(p: VoicePreset) { scope.launch { prefs.setPreset(p) } }
    fun device() = VsAudioDevice({ preset.value }, mixer)

    @Synchronized fun pcm(c: Clip): ShortArray = cache.getOrPut(c) {
        val base = ClipSynth.render(c, VsAudioDevice.SAMPLE_RATE)
        if (c == Clip.SPOOFED_STING) spokenSting?.let { ClipSynth.mixInto(base, it, 0.9) } ?: base else base
    }

    /** Injects a clip into the outgoing call audio (heard by your friend). */
    fun play(c: Clip) = mixer.play(pcm(c))
    fun stingDurationMs() = pcm(Clip.SPOOFED_STING).size * 1000L / VsAudioDevice.SAMPLE_RATE

    /**
     * Generates the spoken "You've been VictorSpoofed!" line with the on-device TTS engine (no bundled third-party
     * audio). If no engine is installed the jingle-only sting is used.
     */
    fun prepareSpokenSting() {
        if (spokenSting != null) return
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { t.shutdown(); return@TextToSpeech }
            t.language = Locale.US
            val out = File(ctx.cacheDir, "sting.wav")
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { t.shutdown() }
                override fun onDone(id: String?) {
                    runCatching { readWav(out) }.getOrNull()?.let { (pcm, rate) ->
                        spokenSting = ClipSynth.resample(pcm, rate, VsAudioDevice.SAMPLE_RATE)
                        synchronized(this@AudioEngine) { cache.remove(Clip.SPOOFED_STING) }
                    }
                    t.shutdown()
                }
            })
            t.synthesizeToFile("You've been Victor Spoofed!", null, out, "sting")
        }
    }

    /** Minimal PCM16 mono WAV reader for TTS output. */
    private fun readWav(f: File): Pair<ShortArray, Int> = RandomAccessFile(f, "r").use { raf ->
        val bytes = ByteArray(raf.length().toInt()).also { raf.readFully(it) }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12; var rate = 22050; var channels = 1
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4); val len = bb.getInt(p + 4)
            if (id == "fmt ") { channels = bb.getShort(p + 10).toInt(); rate = bb.getInt(p + 12) }
            if (id == "data") {
                val n = minOf(len, bytes.size - p - 8) / 2 / channels
                return@use ShortArray(n) { bb.getShort(p + 8 + it * 2 * channels) } to rate
            }
            p += 8 + len + (len and 1)
        }
        error("no data chunk")
    }
}
