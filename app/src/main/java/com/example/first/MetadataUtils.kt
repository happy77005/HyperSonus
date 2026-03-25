package com.example.first

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

object MetadataUtils {
    fun getAlbumArt(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    fun getAudioProperties(context: Context, uri: Uri): Pair<Int?, Int?> {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val format = extractor.getTrackFormat(0) // Usually the first track is audio
            val sampleRate = if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) 
                format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) else null
            val bitrate = if (format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) 
                format.getInteger(android.media.MediaFormat.KEY_BIT_RATE) else null
            Pair(bitrate, sampleRate)
        } catch (e: Exception) {
            Pair(null, null)
        } finally {
            extractor.release()
        }
    }
}
