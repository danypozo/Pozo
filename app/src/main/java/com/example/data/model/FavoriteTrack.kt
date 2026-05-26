package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_tracks")
data class FavoriteTrack(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val isLocal: Boolean,
    val artist: String = "Desconocido",
    val addedAt: Long = System.currentTimeMillis()
)
