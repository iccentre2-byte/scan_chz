package com.tsd.czsetcollector

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator

class ScanFeedback(context: Context, private var soundEnabled: Boolean, private var voiceEnabled: Boolean) {
    private val appContext = context.applicationContext
    private var active = false
    private var tones: ToneGenerator? = null
    private var speech: MediaPlayer? = null

    fun configure(sound: Boolean, voice: Boolean) {
        if (sound == soundEnabled && voice == voiceEnabled) return
        val wasActive = active
        pause()
        soundEnabled = sound
        voiceEnabled = voice
        if (wasActive) resume()
    }

    fun resume() {
        if (active) return
        active = true
        if (soundEnabled) tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull()
    }

    fun play(signal: ScanSignal) {
        when (signal) {
            ScanSignal.OPENED -> play(ToneGenerator.TONE_PROP_BEEP, 90, R.raw.voice_set_opened)
            ScanSignal.COMPLETED -> play(ToneGenerator.TONE_PROP_ACK, 350, R.raw.voice_set_completed)
            ScanSignal.DUPLICATE -> play(ToneGenerator.TONE_PROP_NACK, 160, R.raw.voice_duplicate)
        }
    }

    fun warnBeforeReset() = play(ToneGenerator.TONE_PROP_BEEP2, 350, R.raw.voice_reset_warning)

    fun warnBeforeProfileDeletion() = play(ToneGenerator.TONE_PROP_BEEP2, 350, R.raw.voice_delete_profile_warning)

    private fun play(tone: Int, durationMs: Int, voiceResource: Int) {
        if (!active) return
        if (soundEnabled) runCatching { tones?.startTone(tone, durationMs) }
        stopSpeech()
        if (!voiceEnabled) return
        try {
            val player = MediaPlayer.create(appContext, voiceResource) ?: return
            speech = player
            player.apply {
                setVolume(0.8f, 0.8f)
                setOnCompletionListener { player -> if (speech === player) stopSpeech() }
                setOnErrorListener { player, _, _ ->
                    if (speech === player) stopSpeech()
                    true
                }
            }
            player.start()
        } catch (_: RuntimeException) {
            stopSpeech()
        }
    }

    fun stopSpeech() {
        val player = speech
        speech = null
        runCatching { player?.release() }
    }

    fun pause() {
        active = false
        stopSpeech()
        runCatching { tones?.stopTone() }
        runCatching { tones?.release() }
        tones = null
    }
}
