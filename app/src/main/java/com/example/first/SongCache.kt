package com.example.first

import android.content.Context
import android.net.Uri
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

class SongCache(private val context: Context) {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriAdapter())
        .create()
    private val cacheFile = File(context.filesDir, "song_cache.json")

    fun saveSongs(songs: List<Song>) {
        // Legacy support? No, we should prefer store. 
        // But for compatibility I'll keep it.
        try {
            val json = gson.toJson(songs)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveStore(store: SongStore) {
        try {
            val json = gson.toJson(store)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadStore(): SongStore? {
        return try {
            if (cacheFile.exists()) {
                val json = cacheFile.readText()
                val type = object : TypeToken<SongStore>() {}.type
                gson.fromJson<SongStore>(json, type)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearCache() {
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
    }

    fun loadSongs(): List<Song>? {
        return try {
            if (cacheFile.exists()) {
                val json = cacheFile.readText()
                // Try reading as SongStore first
                try {
                    val storeType = object : TypeToken<SongStore>() {}.type
                    val store = gson.fromJson<SongStore>(json, storeType)
                    if (store?.songs != null) return store.songs
                } catch (e: Exception) {
                    // Fallback to legacy list
                }
                
                val type = object : TypeToken<List<Song>>() {}.type
                gson.fromJson<List<Song>>(json, type)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private class UriAdapter : JsonSerializer<Uri>, JsonDeserializer<Uri> {
        override fun serialize(src: Uri, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            return JsonPrimitive(src.toString())
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Uri {
            return Uri.parse(json.asString)
        }
    }
}
