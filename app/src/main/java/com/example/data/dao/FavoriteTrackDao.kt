package com.example.data.dao

import androidx.room.*
import com.example.data.model.FavoriteTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteTrackDao {
    @Query("SELECT * FROM favorite_tracks ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteTrack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(track: FavoriteTrack)

    @Query("DELETE FROM favorite_tracks WHERE filePath = :path")
    suspend fun deleteFavoriteByPath(path: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE filePath = :path LIMIT 1)")
    suspend fun isFavorite(path: String): Boolean
}
