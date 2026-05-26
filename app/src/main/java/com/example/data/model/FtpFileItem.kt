package com.example.data.model

data class FtpFileItem(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long,
    val extension: String = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
) {
    val isAudio: Boolean
        get() = extension in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")

    val isImage: Boolean
        get() = extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    val isVideo: Boolean
        get() = extension in setOf("mp4", "mkv", "avi", "mov")

    val isDocument: Boolean
        get() = extension in setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar")
}
