package com.example.data.repository

import com.example.data.dao.PlaylistDao
import com.example.data.model.Playlist
import com.example.data.model.PlaylistItem
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(private val playlistDao: PlaylistDao) {

    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    fun getItemsForPlaylist(playlistId: Int): Flow<List<PlaylistItem>> {
        return playlistDao.getItemsForPlaylist(playlistId)
    }

    suspend fun createPlaylist(name: String): Long {
        return playlistDao.insertPlaylist(Playlist(name = name))
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        playlistDao.deletePlaylist(playlist)
    }

    suspend fun addToPlaylist(playlistId: Int, fileName: String, filePath: String, fileSize: Long) {
        val item = PlaylistItem(
            playlistId = playlistId,
            fileName = fileName,
            filePath = filePath,
            fileSize = fileSize
        )
        playlistDao.insertPlaylistItem(item)
    }

    suspend fun removeFromPlaylist(item: PlaylistItem) {
        playlistDao.deletePlaylistItem(item)
    }

    suspend fun clearPlaylist(playlistId: Int) {
        playlistDao.clearPlaylist(playlistId)
    }
}
