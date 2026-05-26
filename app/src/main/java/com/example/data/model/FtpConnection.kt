package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ftp_connections")
data class FtpConnection(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val host: String,
    val port: Int = 21,
    val username: String = "anonymous",
    val password: String = "",
    val defaultPath: String = "/",
    val musicFolder: String = "/"
)
