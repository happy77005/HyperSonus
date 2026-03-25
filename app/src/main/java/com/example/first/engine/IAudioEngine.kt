package com.example.first.engine

import android.content.Context
import android.net.Uri

interface IAudioEngine {
    // Media Control
    fun setDataSource(context: Context, uri: Uri): Boolean
    fun play()
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Int)
    fun release()
    
    // Callbacks
    fun setOnPreparedListener(listener: () -> Unit)
    fun setOnCompletionListener(listener: () -> Unit)
    fun setOnErrorListener(listener: (what: Int, extra: Int) -> Unit)
    
    // State Info
    fun isPlaying(): Boolean
    fun getCurrentPosition(): Int
    fun getDuration(): Int
    fun getAudioSessionId(): Int
    
    // Gapless support
    fun setNextEngine(nextEngine: IAudioEngine?)

    // Speed Control
    fun setPlaybackSpeed(speed: Float)

    // Technical Info for Bit-Perfect
    fun getActualSampleRate(): Int
    fun getActualChannelCount(): Int
    fun probeSampleRates(deviceId: Int): IntArray
    fun setDeviceId(deviceId: Int)
    fun getResamplerName(): String
    fun setResampleEngine(enabled: Boolean)
}
