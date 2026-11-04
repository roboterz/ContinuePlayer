package com.aerolite.lgplayer.data

import android.content.Context
import android.net.Uri

enum class MediaType {
    VIDEO, AUDIO
}

data class MediaFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val duration: Long,
    val size: Long,
    val type: MediaType
)

fun determineMediaType(context: Context, uri: Uri, name: String? = null): MediaType {
    val uriString = uri.toString().lowercase()
    if (uriString.contains("/video/") || uriString.contains("content://media/external/video")) {
        return MediaType.VIDEO
    }
    if (uriString.contains("/audio/") || uriString.contains("content://media/external/audio")) {
        return MediaType.AUDIO
    }

    val mimeType = try {
        context.contentResolver.getType(uri)
    } catch (_: Exception) {
        null
    }
    if (mimeType != null) {
        if (mimeType.startsWith("video/")) return MediaType.VIDEO
        if (mimeType.startsWith("audio/")) return MediaType.AUDIO
    }

    val fileName = name ?: uri.lastPathSegment ?: ""
    val lowerName = fileName.lowercase()
    val audioExtensions = setOf(
        "mp3", "m4a", "wav", "flac", "aac", "ogg", "oga", "opus", 
        "wma", "m4p", "mid", "midi", "amr", "aiff", "ape", "alac"
    )
    val ext = lowerName.substringAfterLast('.', "")
    if (ext in audioExtensions) {
        return MediaType.AUDIO
    }

    val videoExtensions = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", 
        "m4v", "ts", "vob", "mpg", "mpeg", "rmvb", "asf", "divx"
    )
    if (ext in videoExtensions) {
        return MediaType.VIDEO
    }

    return MediaType.VIDEO
}

