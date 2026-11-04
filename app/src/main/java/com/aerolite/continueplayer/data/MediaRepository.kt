package com.aerolite.continueplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {
    suspend fun getAllMedia(): List<MediaFile> = withContext(Dispatchers.IO) {
        val mediaFiles = mutableListOf<MediaFile>()
        
        // Fetch Videos
        mediaFiles.addAll(fetchMedia(MediaType.VIDEO))
        
        // Fetch Audio
        mediaFiles.addAll(fetchMedia(MediaType.AUDIO))
        
        mediaFiles.sortedByDescending { it.id } // Simple sorting by ID (usually corresponds to date added)
    }

    private fun fetchMedia(type: MediaType): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val collection = if (type == MediaType.VIDEO) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = if (type == MediaType.VIDEO) {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )
        } else {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE
            )
        }

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(if (type == MediaType.VIDEO) MediaStore.Video.Media._ID else MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(if (type == MediaType.VIDEO) MediaStore.Video.Media.DISPLAY_NAME else MediaStore.Audio.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(if (type == MediaType.VIDEO) MediaStore.Video.Media.DURATION else MediaStore.Audio.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(if (type == MediaType.VIDEO) MediaStore.Video.Media.SIZE else MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown"
                val duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)

                val contentUri = ContentUris.withAppendedId(collection, id)
                list.add(MediaFile(id, contentUri, name, duration, size, type))
            }
        }
        return list
    }
}
