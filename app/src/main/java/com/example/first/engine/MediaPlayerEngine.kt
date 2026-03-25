package com.example.first.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log

class MediaPlayerEngine : IAudioEngine {
    private var mediaPlayer: MediaPlayer? = null
    private val TAG = "MediaPlayerEngine"
    
    private var onPreparedListener: (() -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((Int, Int) -> Unit)? = null

    init {
        mediaPlayer = MediaPlayer()
    }

    override fun setDataSource(context: Context, uri: Uri): Boolean {
        return try {
            mediaPlayer?.reset()
            mediaPlayer?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mediaPlayer?.setDataSource(context, uri)
            mediaPlayer?.setOnPreparedListener { onPreparedListener?.invoke() }
            mediaPlayer?.setOnCompletionListener { onCompletionListener?.invoke() }
            mediaPlayer?.setOnErrorListener { _, what, extra -> 
                onErrorListener?.invoke(what, extra)
                false
            }
            mediaPlayer?.prepareAsync()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting data source", e)
            false
        }
    }

    override fun play() {
        mediaPlayer?.start()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun resume() {
        mediaPlayer?.start()
    }

    override fun stop() {
        mediaPlayer?.stop()
    }

    override fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
    }

    override fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun setOnPreparedListener(listener: () -> Unit) {
        onPreparedListener = listener
    }

    override fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    override fun setOnErrorListener(listener: (Int, Int) -> Unit) {
        onErrorListener = listener
    }

    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    override fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    override fun getDuration(): Int = mediaPlayer?.duration ?: 0

    override fun getAudioSessionId(): Int = mediaPlayer?.audioSessionId ?: 0

    override fun setNextEngine(nextEngine: IAudioEngine?) {
        if (nextEngine is MediaPlayerEngine) {
            mediaPlayer?.setNextMediaPlayer(nextEngine.mediaPlayer)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = speed
                    mp.playbackParams = params
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting playback speed", e)
            }
        }
    }

    override fun getActualSampleRate(): Int = mediaPlayer?.playbackParams?.audioFallbackMode ?: 0
    override fun getActualChannelCount(): Int = 2
    override fun probeSampleRates(deviceId: Int): IntArray = intArrayOf(44100, 48000)
    override fun setDeviceId(deviceId: Int) {}
    override fun getResamplerName(): String = "Android MediaPlayer (System Default)"
    override fun setResampleEngine(enabled: Boolean) {}
}
