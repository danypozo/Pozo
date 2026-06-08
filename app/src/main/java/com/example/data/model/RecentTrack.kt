package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_tracks")
data class RecentTrack(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val playlistId: Int,
    val fileSize: Long = 0,
    val durationText: String = "00:00",
    val playedAt: Long = System.currentTimeMillis(),
    val logoUri: String? = null
)

