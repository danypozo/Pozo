package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.FtpConnection
import kotlinx.coroutines.flow.Flow

@Dao
interface FtpConnectionDao {
    @Query("SELECT * FROM ftp_connections ORDER BY name ASC")
    fun getAllConnections(): Flow<List<FtpConnection>>

    @Query("SELECT * FROM ftp_connections ORDER BY name ASC")
    suspend fun getAllConnectionsOnce(): List<FtpConnection>

    @Query("SELECT * FROM ftp_connections WHERE id = :id LIMIT 1")
    suspend fun getConnectionById(id: Int): FtpConnection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnection(connection: FtpConnection): Long

    @Update
    suspend fun updateConnection(connection: FtpConnection)

    @Delete
    suspend fun deleteConnection(connection: FtpConnection)
}
