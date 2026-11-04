package com.aerolite.continueplayer.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import android.provider.MediaStore
import android.content.ContentUris
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aerolite.continueplayer.PlaybackService
import com.aerolite.continueplayer.data.determineMediaType
import com.aerolite.continueplayer.data.database.ContinuePlayerDatabase
import com.aerolite.continueplayer.data.database.PlaybackProgress
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = ContinuePlayerDatabase.getDatabase(application)
    private val playbackDao = database.playbackDao()
    private var progressJob: Job? = null
    
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _displayTitle = MutableStateFlow("Loading...")
    val displayTitle: StateFlow<String> = _displayTitle.asStateFlow()

    private val _resizeMode = MutableStateFlow(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
    val resizeMode: StateFlow<Int> = _resizeMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private var currentUri: String? = null
    private var currentPlaybackKey: String? = null
    private var hasResumedProgress = false
    private var controllerFuture: ListenableFuture<MediaController>? = null
    
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                _playbackError.value = null
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mediaMetadata.title?.let { _displayTitle.value = it.toString() }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _playbackError.value = "Playback error: ${error.localizedMessage ?: "Unknown error"}"
            _isPlaying.value = false
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
            
            val p = _player.value
            val key = currentPlaybackKey
            if (playbackState == Player.STATE_READY && !hasResumedProgress && p != null && key != null) {
                hasResumedProgress = true
                viewModelScope.launch {
                    val savedProgress = playbackDao.getProgress(key)
                    savedProgress?.let {
                        if (it.position < p.duration && it.position > 0) {
                            p.seekTo(it.position)
                        }
                    }
                }
            }
        }
    }

    init {
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get() ?: return@addListener
                _player.value = controller
                _repeatMode.value = controller.repeatMode
                controller.addListener(playerListener)
                currentUri?.let { uri -> 
                    performLoad(controller, uri) 
                }
            } catch (e: Exception) {
                _player.value = null
            }
        }, MoreExecutors.directExecutor())

        startProgressSaving()
    }

    fun toggleResizeMode() {
        _resizeMode.value = when (_resizeMode.value) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun toggleRepeatMode() {
        val nextMode = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        _player.value?.repeatMode = nextMode
    }

    fun load(uri: String, title: String?, fileSize: Long? = null) {
        var finalUriString = uri
        val parsedUri = Uri.parse(uri)
        
        // Try to persist permission
        persistUriPermission(getApplication(), parsedUri)
        
        // Proactively try to find a stable MediaStore URI if it's not already one.
        // This ensures "permanent" access regardless of temporary grants.
        if (!isMediaStoreUri(parsedUri)) {
            val resolvedUri = resolveStableUri(getApplication(), parsedUri, title, fileSize)
            if (resolvedUri != null) {
                Log.d("PlayerViewModel", "Proactively resolved $uri to stable URI $resolvedUri")
                finalUriString = resolvedUri.toString()
            }
        } else if (!canReadUri(getApplication(), parsedUri)) {
            // If it IS a MediaStore URI but we can't read it (rare), try re-resolving
            val resolvedUri = resolveStableUri(getApplication(), parsedUri, title, fileSize)
            if (resolvedUri != null) {
                finalUriString = resolvedUri.toString()
            }
        }

        // If it's the same URI and we're already playing/buffering, don't interrupt.
        val currentPlayer = _player.value
        if (currentUri == finalUriString && currentPlayer != null && 
            currentPlayer.playbackState != Player.STATE_IDLE && 
            currentPlayer.playerError == null) {
            return
        }
        
        currentUri = finalUriString
        _playbackError.value = null
        val finalParsedUri = Uri.parse(finalUriString)
        _displayTitle.value = title ?: getFileName(getApplication(), finalParsedUri)
        currentPlaybackKey = getFileFingerprint(getApplication(), finalParsedUri)
        hasResumedProgress = false
        
        if (currentPlayer != null) {
            performLoad(currentPlayer, finalUriString)
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.scheme == "content" && uri.authority == "media"
    }

    private fun canReadUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {}
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveStableUri(context: Context, uri: Uri, title: String?, fileSize: Long?): Uri? {
        var name = ""
        var size = fileSize ?: -1L
        
        // Try to get info even if AFDs fail, maybe query works
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: ""
                    if (size <= 0) size = cursor.getLong(1)
                }
            }
        } catch (e: Exception) {}
        
        if (name.isEmpty()) name = title ?: uri.lastPathSegment ?: ""
        if (name.isEmpty() || size <= 0) return null

        Log.d("PlayerViewModel", "Searching MediaStore for $name with size $size")

        // Query MediaStore for a match
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        val selectionArgs = arrayOf(name, size.toString())

        // Try Video first, then Audio
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

    private fun persistUriPermission(context: Context, uri: Uri) {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Log.d("PlayerViewModel", "Persisted permission for $uri")
            } catch (e: Exception) {
                Log.w("PlayerViewModel", "Could not persist permission for $uri: ${e.message}")
            }
        }
    }

    private fun performLoad(player: Player, uri: String) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(currentPlaybackKey ?: uri)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(_displayTitle.value)
                    .build()
            )
            .build()
        
        player.setMediaItem(mediaItem, true)
        player.prepare()
        player.playWhenReady = true
    }

    private fun getFileFingerprint(context: Context, uri: Uri): String {
        var name = ""
        var size = -1L
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                size = afd.length
            }
        } catch (e: Exception) {}
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0) ?: ""
            }
        } catch (e: Exception) {}
        if (name.isEmpty()) name = uri.lastPathSegment ?: "unknown"
        if (size <= 0 && uri.scheme == "file") {
            try { size = java.io.File(uri.path!!).length() } catch (e: Exception) {}
        }
        return "stable_${name}_$size"
    }

    private fun getFileName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        } catch (e: Exception) {}
        return uri.lastPathSegment ?: "Unknown"
    }

    private fun startProgressSaving() {
        progressJob = viewModelScope.launch {
            while (isActive) {
                val p = _player.value
                val key = currentPlaybackKey
                if (p != null && p.isPlaying && key != null) {
                    saveProgress(p, key)
                }
                delay(2000)
            }
        }
    }

    private suspend fun saveProgress(player: Player, key: String) {
        val currentPosition = player.currentPosition
        val duration = player.duration
        val uri = currentUri
        if (duration > 0 && uri != null) {
            val type = determineMediaType(getApplication(), Uri.parse(uri), _displayTitle.value)
            
            playbackDao.saveProgress(
                PlaybackProgress(
                    mediaUri = key,
                    originalUri = uri,
                    name = _displayTitle.value,
                    type = type,
                    position = currentPosition,
                    duration = duration
                )
            )
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        _player.value?.let { p ->
            val key = currentPlaybackKey
            val uri = currentUri
            if (key != null && uri != null) {
                val currentPosition = p.currentPosition
                val duration = p.duration
                if (duration > 0) {
                    val type = determineMediaType(getApplication(), Uri.parse(uri), _displayTitle.value)
                    
                    val progress = PlaybackProgress(
                        mediaUri = key,
                        originalUri = uri,
                        name = _displayTitle.value,
                        type = type,
                        position = currentPosition,
                        duration = duration
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        playbackDao.saveProgress(progress)
                    }
                }
            }
            p.removeListener(playerListener)
        }
        _player.value = null
        controllerFuture?.cancel(true)
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}
