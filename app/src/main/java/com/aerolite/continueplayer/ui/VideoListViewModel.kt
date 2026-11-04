package com.aerolite.continueplayer.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.content.ContentUris
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aerolite.continueplayer.data.MediaRepository
import com.aerolite.continueplayer.data.MediaType
import com.aerolite.continueplayer.data.determineMediaType
import com.aerolite.continueplayer.data.database.ContinuePlayerDatabase
import com.aerolite.continueplayer.data.database.PlaybackProgress
import com.aerolite.continueplayer.data.database.PlaylistItem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VideoListViewModel(
    private val context: Context,
    private val repository: MediaRepository
) : ViewModel() {
    private val database = ContinuePlayerDatabase.getDatabase(context)
    private val playlistDao = database.playlistDao()
    private val playbackDao = database.playbackDao()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val playlist: StateFlow<List<PlaylistItem>> = playlistDao.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val videoPlaylist: StateFlow<List<PlaylistItem>> = playlistDao.getItemsByType(MediaType.VIDEO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val musicPlaylist: StateFlow<List<PlaylistItem>> = playlistDao.getItemsByType(MediaType.AUDIO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // History now works independently of the playlist items
    val history: StateFlow<List<PlaylistItem>> = playbackDao.getAllHistoryFlow().map { progressList ->
        progressList.map { progress ->
            // Extract size from fingerprint (mediaUri) if possible
            val extractedSize = try {
                progress.mediaUri.substringAfterLast("_").toLong()
            } catch (e: Exception) {
                0L
            }
            
            PlaylistItem(
                mediaUri = progress.originalUri, // Use the real URI for playback
                name = progress.name,
                duration = progress.duration,
                size = extractedSize,
                type = progress.type,
                addedAt = progress.lastUpdated
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            // History is now handled by the flow
            _isLoading.value = false
        }
    }

    fun addToPlaylist(uri: Uri) {
        viewModelScope.launch {
            // Take persistable permission
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {}

            val name = getFileName(context, uri)
            val size = getFileSize(context, uri)
            
            // Proactively try to resolve to a stable MediaStore URI
            var finalUri = uri
            if (uri.scheme == "content" && uri.authority != "media") {
                val resolved = resolveStableUri(context, uri, name, size)
                if (resolved != null) {
                    Log.d("VideoListViewModel", "Resolved $uri to stable $resolved")
                    finalUri = resolved
                }
            }

            val type = determineMediaType(context, finalUri, name)
            
            playlistDao.addItem(
                PlaylistItem(
                    mediaUri = finalUri.toString(),
                    name = name,
                    duration = 0,
                    size = size,
                    type = type
                )
            )
        }
    }

    fun addToPlaylist(item: PlaylistItem) {
        viewModelScope.launch {
            val uri = Uri.parse(item.mediaUri)
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {}

            playlistDao.addItem(
                PlaylistItem(
                    mediaUri = item.mediaUri,
                    name = item.name,
                    duration = item.duration,
                    size = item.size,
                    type = item.type,
                    addedAt = System.currentTimeMillis()
                )
            )
            android.widget.Toast.makeText(context, "已添加到播放列表", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveStableUri(context: Context, uri: Uri, name: String, size: Long): Uri? {
        if (name.isEmpty() || size <= 0) return null

        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        val selectionArgs = arrayOf(name, size.toString())

        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )

        for (collection in collections) {
            try {
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        return ContentUris.withAppendedId(collection, id)
                    }
                }
            } catch (e: Exception) {}
        }
        return null
    }

    fun removeFromPlaylist(item: PlaylistItem) {
        viewModelScope.launch {
            playlistDao.removeItem(item)
        }
    }

    fun removeFromHistory(item: PlaylistItem) {
        viewModelScope.launch {
            // Find all progress entries with this original URI and delete them by PK (mediaUri)
            val entries = playbackDao.getProgressByOriginalUri(item.mediaUri)
            entries.forEach { entry ->
                playbackDao.deleteProgress(entry.mediaUri)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun getFileName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        } catch (e: Exception) {}
        return uri.lastPathSegment ?: "Unknown"
    }
    
    private fun getFileSize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                return afd.length
            }
        } catch (e: Exception) {}
        return 0
    }
}
