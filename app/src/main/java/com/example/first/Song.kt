package com.example.first

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Int,
    val uri: Uri,
    val dateAdded: Long = 0,
    val path: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val size: Long = 0,
    val relativeDir: String? = null
)

data class Album(
    val name: String,
    val songs: List<Song>
)

data class SongStore(
    val scanType: String,
    val rootPath: String?,
    val songs: List<Song>,
    val lastUpdated: Long
)
