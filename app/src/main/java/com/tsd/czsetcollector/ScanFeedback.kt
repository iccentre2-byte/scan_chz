package com.tsd.czsetcollector

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

internal enum class ScanVoice { ORIGINAL, FEMALE }

/** Only Android's installed, offline Russian voices are eligible. */
internal class ScanFeedback(
    context: Context,
    private var soundEnabled: Boolean,
    private var voiceEnabled: Boolean,
    private var choice: ScanVoice
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var generation = 0
    private var tones: ToneGenerator? = null
    private var speech: TextToSpeech? = null
    private var original: Voice? = null
    private var female: Voice? = null
    private var checking = false

    fun configure(sound: Boolean, voice: Boolean, selected: ScanVoice) {
        if (sound != soundEnabled) {
            soundEnabled = sound
            releaseTone()
            if (active) startToneGenerator()
        }
        stopSpeech()
        voiceEnabled = voice
        choice = selected
    }

    fun resume() {
        if (active) return
        active = true
        startToneGenerator()
        checking = true
        val requested = ++generation
        try {
            speech = TextToSpeech(appContext) { status -> handler.post {
                if (!active || generation != requested) return@post
                checking = false
                val engine = speech ?: return@post
                if (status == TextToSpeech.SUCCESS) runCatching { loadRussianVoices(engine) }
            } }
        } catch (_: RuntimeException) {
            checking = false
        }
    }

    private fun loadRussianVoices(engine: TextToSpeech) {
        if (engine.setLanguage(Locale("ru", "RU")) < TextToSpeech.LANG_AVAILABLE) return
        val localVoices = engine.voices.orEmpty().filter(::offlineRussian)
        if (engine.setSpeechRate(1.05f) != TextToSpeech.SUCCESS) return
        // Keep the same selection order as in 1.3.4 for the original voice.
        original = localVoices.firstOrNull() ?: engine.voice?.takeIf(::offlineRussian)
        // Offline Russian female voice IDs for Speech Services by Google.
        if (engine.defaultEngine == "com.google.android.tts") {
            female = listOf("ru-ru-x-rue-local", "ru-ru-x-ruc-local", "ru-ru-x-dfc-local")
                .firstNotNullOfOrNull { id -> localVoices.firstOrNull { it.name == id } }
        }
    }

    private fun offlineRussian(voice: Voice) = voice.locale.language == "ru" &&
        !voice.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features

    private fun voice(selected: ScanVoice) = if (selected == ScanVoice.FEMALE) female else original

    fun status(selected: ScanVoice): String = when {
        checking -> "Проверяется установленный русский голос."
        voice(selected) != null -> "Русский голос готов."
        selected == ScanVoice.FEMALE -> "Установите женский русский голос в системных настройках синтеза речи Google."
        else -> "Системный русский голос недоступен. Установите русские голосовые данные на ТСД."
    }

    fun available(selected: ScanVoice) = voice(selected) != null

    fun preview(selected: ScanVoice): Boolean = speak(selected, "Набор открыт. Набор собран. Дубль.")

    fun play(signal: ScanSignal) {
        when (signal) {
            ScanSignal.OPENED -> play(ToneGenerator.TONE_PROP_BEEP, 90, signal.spoken)
            ScanSignal.COMPLETED -> play(ToneGenerator.TONE_PROP_ACK, 350, signal.spoken)
            ScanSignal.DUPLICATE -> play(ToneGenerator.TONE_PROP_NACK, 250, signal.spoken)
        }
    }

    fun warnBeforeReset() = play(ToneGenerator.TONE_PROP_BEEP2, 350,
        "Внимание. Сброс удалит текущий набор и все отсканированные пачки из него.")

    fun warnBeforeProfileDeletion() = play(ToneGenerator.TONE_PROP_BEEP2, 350,
        "Внимание. Выбранный профиль и его шаблон будут удалены.")

    private fun play(tone: Int, durationMs: Int, text: String) {
        if (!active) return
        if (soundEnabled) runCatching { tones?.startTone(tone, durationMs) }
        if (voiceEnabled) speak(choice, text)
    }

    private fun speak(selected: ScanVoice, text: String): Boolean {
        if (!active) return false
        val engine = speech ?: return false
        val russian = voice(selected) ?: return false
        return runCatching {
            if (engine.voice != russian && engine.setVoice(russian) != TextToSpeech.SUCCESS) return false
            val options = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.8f) }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, options, "scan-$selected") == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    private fun startToneGenerator() {
        if (soundEnabled) tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull()
    }

    private fun releaseTone() {
        runCatching { tones?.stopTone() }
        runCatching { tones?.release() }
        tones = null
    }

    fun stopSpeech() { runCatching { speech?.stop() } }

    fun pause() {
        active = false
        generation++
        checking = false
        handler.removeCallbacksAndMessages(null)
        stopSpeech()
        runCatching { speech?.shutdown() }
        speech = null
        original = null
        female = null
        releaseTone()
    }
}
