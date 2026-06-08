package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.model.FavoriteTrack
import com.example.ui.addPodmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TrashItem(
    val id: String,
    val type: String, // "recording", "podmark", "favorite"
    val originalPath: String,
    val displayName: String,
    val deletedAt: Long,
    val extraData: String // JSON payload
)

object TrashManager {
    private const val TAG = "TrashManager"
    private const val PREFS_NAME = "app_trash_prefs"
    private const val KEY_TRASH_LIST = "trash_items_list"
    private const val KEY_AUTO_DELETE = "trash_auto_delete_30_days"
    private const val KEY_TRASH_ENABLED = "trash_enabled_flag"

    fun isTrashEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TRASH_ENABLED, true)
    }

    fun setTrashEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_TRASH_ENABLED, enabled).apply()
    }

    fun getTrashItems(context: Context): List<TrashItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_TRASH_LIST, "[]") ?: "[]"
        val list = mutableListOf<TrashItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TrashItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        type = obj.optString("type", ""),
                        originalPath = obj.optString("originalPath", ""),
                        displayName = obj.optString("displayName", ""),
                        deletedAt = obj.optLong("deletedAt", 0L),
                        extraData = obj.optString("extraData", "{}")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing trash items", e)
        }
        return list
    }

    private fun saveTrashItems(context: Context, list: List<TrashItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("type", item.type)
            obj.put("originalPath", item.originalPath)
            obj.put("displayName", item.displayName)
            obj.put("deletedAt", item.deletedAt)
            obj.put("extraData", item.extraData)
            array.put(obj)
        }
        prefs.edit().putString(KEY_TRASH_LIST, array.toString()).apply()
    }

    private fun moveFileSafely(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) {
            return true
        }
        return try {
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            src.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Fallback file move failed", e)
            if (dest.exists()) dest.delete()
            false
        }
    }

    // Move a physical recording file to Trash
    fun trashRecording(context: Context, file: File): Boolean {
        try {
            if (!file.exists()) return false
            if (!isTrashEnabled(context)) {
                return file.delete()
            }
            val id = UUID.randomUUID().toString()
            val trashDir = File(context.filesDir, "trash_recordings")
            if (!trashDir.exists()) {
                trashDir.mkdirs()
            }
            val destinationFile = File(trashDir, id)
            
            // Move physical file safely
            if (moveFileSafely(file, destinationFile)) {
                val list = getTrashItems(context).toMutableList()
                list.add(
                    TrashItem(
                        id = id,
                        type = "recording",
                        originalPath = file.absolutePath,
                        displayName = file.name,
                        deletedAt = System.currentTimeMillis(),
                        extraData = "{}"
                    )
                )
                saveTrashItems(context, list)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error trashing recording file", e)
        }
        return false
    }

    // Move a Podmark to Trash
    fun trashPodmark(context: Context, trackName: String, filePath: String, positionMs: Long, timestampText: String, createdAt: String) {
        if (!isTrashEnabled(context)) {
            return
        }
        val id = UUID.randomUUID().toString()
        val extra = JSONObject().apply {
            put("trackName", trackName)
            put("filePath", filePath)
            put("positionMs", positionMs)
            put("timestampText", timestampText)
            put("createdAt", createdAt)
        }.toString()

        val list = getTrashItems(context).toMutableList()
        list.add(
            TrashItem(
                id = id,
                type = "podmark",
                originalPath = filePath,
                displayName = "$trackName @ $timestampText",
                deletedAt = System.currentTimeMillis(),
                extraData = extra
            )
        )
        saveTrashItems(context, list)
    }

    // Move a FavoriteTrack to Trash
    fun trashFavorite(context: Context, filePath: String, fileName: String, isLocal: Boolean, artist: String) {
        if (!isTrashEnabled(context)) {
            return
        }
        val id = UUID.randomUUID().toString()
        val extra = JSONObject().apply {
            put("filePath", filePath)
            put("fileName", fileName)
            put("isLocal", isLocal)
            put("artist", artist)
        }.toString()

        val list = getTrashItems(context).toMutableList()
        list.add(
            TrashItem(
                id = id,
                type = "favorite",
                originalPath = filePath,
                displayName = fileName,
                deletedAt = System.currentTimeMillis(),
                extraData = extra
            )
        )
        saveTrashItems(context, list)
    }

    // Restore an item from Trash
    fun restoreItem(context: Context, item: TrashItem, scope: CoroutineScope) {
        try {
            when (item.type) {
                "recording" -> {
                    val trashFile = File(File(context.filesDir, "trash_recordings"), item.id)
                    if (trashFile.exists()) {
                        val originalFile = File(item.originalPath)
                        val parent = originalFile.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }
                        if (!trashFile.renameTo(originalFile)) {
                            try {
                                trashFile.inputStream().use { input ->
                                    originalFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                trashFile.delete()
                            } catch (e: Exception) {
                                Log.e(TAG, "Fallback restore copy file failed", e)
                            }
                        }
                    }
                }
                "podmark" -> {
                    val obj = JSONObject(item.extraData)
                    addPodmark(
                        context,
                        obj.optString("trackName"),
                        obj.optString("filePath"),
                        obj.optLong("positionMs"),
                        obj.optString("timestampText")
                    )
                }
                "favorite" -> {
                    val obj = JSONObject(item.extraData)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            db.favoriteTrackDao().insertFavorite(
                                FavoriteTrack(
                                    filePath = obj.optString("filePath"),
                                    fileName = obj.optString("fileName"),
                                    isLocal = obj.optBoolean("isLocal"),
                                    artist = obj.optString("artist")
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restoring favorite", e)
                        }
                    }
                }
            }
            // Remove from trash list
            val list = getTrashItems(context).filter { it.id != item.id }
            saveTrashItems(context, list)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring item", e)
        }
    }

    // Delete item permanently
    fun deletePermanently(context: Context, item: TrashItem) {
        try {
            if (item.type == "recording") {
                val trashFile = File(File(context.filesDir, "trash_recordings"), item.id)
                if (trashFile.exists()) {
                    trashFile.delete()
                }
            }
            val list = getTrashItems(context).filter { it.id != item.id }
            saveTrashItems(context, list)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting permanently", e)
        }
    }

    // Empty entire trash
    fun emptyTrash(context: Context) {
        try {
            val trashDir = File(context.filesDir, "trash_recordings")
            if (trashDir.exists() && trashDir.isDirectory) {
                trashDir.listFiles()?.forEach { it.delete() }
            }
            saveTrashItems(context, emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Error emptying trash", e)
        }
    }

    // Auto delete after 30 days logic
    fun checkAndAutoDelete(context: Context) {
        if (!isAutoDeleteEnabled(context)) return
        val list = getTrashItems(context)
        val thirtyDaysMs = 30L * 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        val toKeep = mutableListOf<TrashItem>()

        for (item in list) {
            if (now - item.deletedAt > thirtyDaysMs) {
                // Permanently delete file if recording
                if (item.type == "recording") {
                    val trashFile = File(File(context.filesDir, "trash_recordings"), item.id)
                    if (trashFile.exists()) {
                        trashFile.delete()
                    }
                }
            } else {
                toKeep.add(item)
            }
        }
        saveTrashItems(context, toKeep)
    }

    fun isAutoDeleteEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_DELETE, true) // Enable by default
    }

    fun setAutoDeleteEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_DELETE, enabled).apply()
    }
}
