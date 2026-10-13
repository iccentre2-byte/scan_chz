package com.tsd.czsetcollector

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Optional audio is isolated from scanning/storage. Only resumed activities own audio resources. */
class ScanFeedback(context:Context,sound:Boolean,voice:Boolean) {
    private val appContext=context.applicationContext
    private val handler=Handler(Looper.getMainLooper())
    private var soundEnabled=sound
    private var voiceEnabled=voice
    private var active=false
    private var generation=0
    private var tones:ToneGenerator?=null
    private var speech:TextToSpeech?=null
    private var russianReady=false
    var voiceStatus="Голос ещё не проверен."
        private set

    fun configure(sound:Boolean,voice:Boolean) {
        if(sound==soundEnabled && voice==voiceEnabled)return
        val wasActive=active;pause();soundEnabled=sound;voiceEnabled=voice
        if(wasActive)resume()
    }
    fun resume() {
        if(active)return
        active=true
        if(soundEnabled)tones=runCatching{ToneGenerator(AudioManager.STREAM_MUSIC,55)}.getOrNull()
        voiceStatus=if(voiceEnabled)"Проверяется русский голос. Сканирование доступно." else "Голос отключён."
        if(!voiceEnabled)return
        val requested=++generation
        try {
            speech=TextToSpeech(appContext){status->handler.post{
                if(!active || generation!=requested)return@post
                val engine=speech ?: return@post
                russianReady=runCatching {
                    if(status!=TextToSpeech.SUCCESS)return@runCatching false
                    val russian=Locale("ru","RU")
                    if(engine.setLanguage(russian)<TextToSpeech.LANG_AVAILABLE)return@runCatching false
                    val installed=engine.voices?.firstOrNull{it.locale.language=="ru" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features}
                    if(installed!=null && engine.setVoice(installed)!=TextToSpeech.SUCCESS)return@runCatching false
                    val chosen=engine.voice ?: return@runCatching false
                    if(chosen.locale.language!="ru" || chosen.isNetworkConnectionRequired || TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in chosen.features)return@runCatching false
                    engine.setSpeechRate(1.05f)==TextToSpeech.SUCCESS
                }.getOrDefault(false)
                voiceStatus=if(russianReady)"Русский голос готов." else "Русский голос не установлен или недоступен. Сканирование доступно. Короткие сигналы можно включить отдельно."
            }}
        } catch(e:Exception) {
            russianReady=false;voiceStatus="Голосовой движок недоступен. Сканирование доступно. Короткие сигналы можно включить отдельно."
        }
    }
    fun play(signal:ScanSignal) {
        if(!active)return
        if(soundEnabled)runCatching {
            val tone=when(signal){
                ScanSignal.OPENED->ToneGenerator.TONE_PROP_BEEP
                ScanSignal.COMPLETED->ToneGenerator.TONE_PROP_ACK
                ScanSignal.DUPLICATE->ToneGenerator.TONE_PROP_NACK
            }
            tones?.startTone(tone,when(signal){ScanSignal.OPENED->90;ScanSignal.COMPLETED->350;ScanSignal.DUPLICATE->160})
        }
        if(voiceEnabled && russianReady)runCatching {
            // Replace stale speech instead of letting a fast scan stream build an announcement backlog.
            val options=Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,0.8f)}
            if(speech?.speak(signal.spoken,TextToSpeech.QUEUE_FLUSH,options,"scan-${signal.name}")!=TextToSpeech.SUCCESS) {
                russianReady=false;voiceStatus="Русский голос недоступен. Сканирование доступно. Короткие сигналы можно включить отдельно."
            }
        }
    }
    fun pause() {
        active=false;generation++;russianReady=false
        handler.removeCallbacksAndMessages(null)
        runCatching{speech?.stop()};runCatching{speech?.shutdown()};speech=null
        runCatching{tones?.stopTone()};runCatching{tones?.release()};tones=null
    }
}
