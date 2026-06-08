package com.example.data.dao

import androidx.room.*
import com.example.data.model.RecentTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentTrackDao {
    @Query("SELECT * FROM recent_tracks ORDER BY playedAt DESC LIMIT 20")
    fun getRecentTracks(): Flow<List<RecentTrack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentTrack(track: RecentTrack)

    @Query("DELETE FROM recent_tracks")
    suspend fun clearAllRecentTracks()
}
