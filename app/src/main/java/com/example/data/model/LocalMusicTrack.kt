package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_music_tracks")
data class LocalMusicTrack(
    @PrimaryKey val filePath: String,
    val title: String,
    val artist: String = "Desconocido",
    val album: String = "Desconocido",
    val genre: String = "Desconocido",
    val year: String = "Desconocido",
    val durationText: String = "00:00",
    val size: Long = 0,
    val dateModified: Long = 0,
    val logoUri: String? = null
)
