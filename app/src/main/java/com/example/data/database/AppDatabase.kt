package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.FtpConnectionDao
import com.example.data.dao.PlaylistDao
import com.example.data.dao.LocalMusicTrackDao
import com.example.data.dao.FavoriteTrackDao
import com.example.data.dao.RecentTrackDao
import com.example.data.model.FtpConnection
import com.example.data.model.Playlist
import com.example.data.model.PlaylistItem
import com.example.data.model.LocalMusicTrack
import com.example.data.model.FavoriteTrack
import com.example.data.model.RecentTrack

@Database(
    entities = [
        FtpConnection::class,
        Playlist::class,
        PlaylistItem::class,
        LocalMusicTrack::class,
        FavoriteTrack::class,
        RecentTrack::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ftpConnectionDao(): FtpConnectionDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun localMusicTrackDao(): LocalMusicTrackDao
    abstract fun favoriteTrackDao(): FavoriteTrackDao
    abstract fun recentTrackDao(): RecentTrackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ftp_hub_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
