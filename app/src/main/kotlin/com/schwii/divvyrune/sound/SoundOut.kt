package com.schwii.divvyrune.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Plays a [Synth] through the phone's media stream, on a thread of its own, while [start]ed. Needs no permissions.
 */
class SoundOut {
    @Suppress("DEPRECATION")
    val synth = Synth(AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 8000..192000 } ?: 48000)
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "divvyrune-sound").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    @Synchronized fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val rate = synth.rate
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, Synth.BLOCK * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrNull() ?: run { running = false; return }
        val buf = FloatArray(Synth.BLOCK * 2)
        try {
            track.play()
            while (running) {
                synth.render(buf, Synth.BLOCK)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }
}
