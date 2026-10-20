package com.victorious.spoof.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.twilio.voice.AudioDevice
import com.twilio.voice.AudioDeviceContext
import com.twilio.voice.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.AudioFormat as AF

/**
 * Custom Twilio Voice [AudioDevice]: the SDK hands us the capture/render lifecycle and we feed it 10 ms PCM frames.
 *
 * Capture path: AudioRecord (VOICE_COMMUNICATION, with platform AEC/NS when available) -> voice preset DSP ->
 * soundboard mix -> AudioDevice.audioDeviceWriteCaptureData. Render path: audioDeviceReadRenderData -> AudioTrack.
 *
 * SDK limits (documented in MANUAL.md): replacing the audio device disables the SDK's built-in WebRTC audio
 * processing, so echo cancellation relies on the Android platform effects; the device must be set before
 * Voice.connect and can't be swapped mid-call (the preset itself can change mid-call).
 */
class VsAudioDevice(
    private val presetProvider: () -> VoicePreset,
    private val mixer: SoundMixer,
) : AudioDevice {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME = SAMPLE_RATE / 100 // 10 ms
        private const val TAG = "VsAudioDevice"
    }

    private val format = AudioFormat(SAMPLE_RATE, AudioFormat.AUDIO_SAMPLE_MONO)
    @Volatile private var capturing = false
    @Volatile private var rendering = false
    private var captureThread: Thread? = null
    private var renderThread: Thread? = null

    override fun getCapturerFormat() = format
    override fun onInitCapturer() = true
    override fun getRendererFormat() = format
    override fun onInitRenderer() = true

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before any call can start.
    override fun onStartCapturing(ctx: AudioDeviceContext): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AF.CHANNEL_IN_MONO, AF.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AF.CHANNEL_IN_MONO, AF.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8))
        } catch (e: Exception) { Log.e(TAG, "AudioRecord failed", e); return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        capturing = true
        captureThread = Thread({
            val pcm = ShortArray(FRAME)
            val out = ByteBuffer.allocateDirect(FRAME * 2).order(ByteOrder.nativeOrder())
            var preset = presetProvider(); var fx = Effects.forPreset(preset, SAMPLE_RATE)
            rec.startRecording()
            try {
                while (capturing) {
                    var got = 0
                    while (got < FRAME && capturing) { val r = rec.read(pcm, got, FRAME - got); if (r <= 0) break; got += r }
                    if (got < FRAME) continue
                    val now = presetProvider()
                    if (now != preset) { preset = now; fx = Effects.forPreset(now, SAMPLE_RATE) }
                    fx.process(pcm, FRAME)
                    mixer.mixInto(pcm, FRAME)
                    out.clear(); out.asShortBuffer().put(pcm); out.limit(FRAME * 2)
                    AudioDevice.audioDeviceWriteCaptureData(ctx, out)
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }, "vs-capture").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    override fun onStopCapturing(): Boolean {
        capturing = false; captureThread?.join(500); captureThread = null; return true
    }

    override fun onStartRendering(ctx: AudioDeviceContext): Boolean {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AF.CHANNEL_OUT_MONO, AF.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AF.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AF.CHANNEL_OUT_MONO).setEncoding(AF.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, FRAME * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { Log.e(TAG, "AudioTrack failed", e); return false }
        rendering = true
        renderThread = Thread({
            val buf = ByteBuffer.allocateDirect(FRAME * 2).order(ByteOrder.nativeOrder())
            val bytes = ByteArray(FRAME * 2)
            track.play()
            try {
                while (rendering) {
                    buf.clear()
                    AudioDevice.audioDeviceReadRenderData(ctx, buf)
                    buf.rewind(); buf.get(bytes)
                    track.write(bytes, 0, bytes.size) // blocking write paces the loop at real time
                }
            } finally { runCatching { track.stop() }; track.release() }
        }, "vs-render").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    override fun onStopRendering(): Boolean {
        rendering = false; renderThread?.join(500); renderThread = null; return true
    }
}
