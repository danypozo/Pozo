package com.example.data.dao

import androidx.room.*
import com.example.data.model.LocalMusicTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalMusicTrackDao {
    @Query("SELECT * FROM local_music_tracks ORDER BY title ASC")
    fun getAllLocalTracks(): Flow<List<LocalMusicTrack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<LocalMusicTrack>)

    @Query("DELETE FROM local_music_tracks")
    suspend fun clearAllLocalTracks()

    @Query("DELETE FROM local_music_tracks WHERE filePath = :path")
    suspend fun deleteTrackByPath(path: String)
}
