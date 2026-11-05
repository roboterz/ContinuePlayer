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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aerolite.continueplayer.PlaybackService
import com.aerolite.continueplayer.data.determineMediaType
import com.aerolite.continueplayer.data.database.ContinuePlayerDatabase
import com.aerolite.continueplayer.data.database.PlaybackProgress
import com.aerolite.continueplayer.data.lyrics.LrcParser
import com.aerolite.continueplayer.data.lyrics.LyricData
import com.aerolite.continueplayer.data.lyrics.SubtitleTrack
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

    private val _mediaType = MutableStateFlow(com.aerolite.continueplayer.data.MediaType.VIDEO)
    val mediaType: StateFlow<com.aerolite.continueplayer.data.MediaType> = _mediaType.asStateFlow()

    // Subtitle & Lyric States
    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    private val _isSubtitleDisabled = MutableStateFlow(false)
    val isSubtitleDisabled: StateFlow<Boolean> = _isSubtitleDisabled.asStateFlow()

    private val _lyricData = MutableStateFlow<LyricData?>(null)
    val lyricData: StateFlow<LyricData?> = _lyricData.asStateFlow()

    private val _currentLyricIndex = MutableStateFlow(-1)
    val currentLyricIndex: StateFlow<Int> = _currentLyricIndex.asStateFlow()

    private val _isLyricsVisible = MutableStateFlow(false)
    val isLyricsVisible: StateFlow<Boolean> = _isLyricsVisible.asStateFlow()

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

        override fun onTracksChanged(tracks: Tracks) {
            updateSubtitleTracks(tracks)
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
                updateSubtitleTracks(controller.currentTracks)
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

    fun toggleLyricsVisibility() {
        _isLyricsVisible.value = !_isLyricsVisible.value
    }

    fun seekTo(positionMs: Long) {
        _player.value?.seekTo(positionMs)
        val lyrics = _lyricData.value
        if (lyrics != null) {
            _currentLyricIndex.value = lyrics.findCurrentIndex(positionMs)
        }
    }

    fun selectSubtitleTrack(track: SubtitleTrack?) {
        val p = _player.value ?: return
        if (track == null) {
            _isSubtitleDisabled.value = true
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            _isSubtitleDisabled.value = false
            val tracks = p.currentTracks
            if (track.groupIndex < tracks.groups.size) {
                val group = tracks.groups[track.groupIndex]
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                    )
                    .build()
            }
        }
        updateSubtitleTracks(p.currentTracks)
    }

    fun loadExternalFile(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                persistUriPermission(context, uri)
                val fileName = getFileName(context, uri)
                val ext = fileName.substringAfterLast('.', "").lowercase()

                if (ext == "lrc") {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val parsed = LrcParser.parse(stream)
                        _lyricData.value = parsed
                        _isLyricsVisible.value = true
                    }
                } else if (ext in setOf("srt", "vtt", "ass", "ssa", "ttml", "xml")) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val parsed = LrcParser.parse(stream)
                        if (parsed.entries.isNotEmpty()) {
                            _lyricData.value = parsed
                        }
                    }

                    val mimeType = when (ext) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }

                    withContext(Dispatchers.Main) {
                        addExternalSubtitleToPlayer(uri, mimeType, fileName)
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val parsed = LrcParser.parse(stream)
                        if (parsed.entries.isNotEmpty()) {
                            _lyricData.value = parsed
                            _isLyricsVisible.value = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Error loading external file: ${e.message}", e)
            }
        }
    }

    private fun addExternalSubtitleToPlayer(uri: Uri, mimeType: String, label: String) {
        val p = _player.value ?: return
        val currentMediaItem = p.currentMediaItem ?: return
        val currentPos = p.currentPosition
        val playWhenReady = p.playWhenReady

        val subConfig = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage("und")
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        val existingConfigs = currentMediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
        val updatedConfigs = existingConfigs + subConfig

        val newMediaItem = currentMediaItem.buildUpon()
            .setSubtitleConfigurations(updatedConfigs)
            .build()

        p.setMediaItem(newMediaItem, currentPos)
        p.playWhenReady = playWhenReady
        p.prepare()
    }

    private fun updateSubtitleTracks(tracks: Tracks) {
        val p = _player.value ?: return
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        _isSubtitleDisabled.value = textDisabled

        val list = mutableListOf<SubtitleTrack>()
        var counter = 1
        for ((groupIndex, group) in tracks.groups.withIndex()) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = !textDisabled && group.isTrackSelected(i)
                    val lang = format.language?.uppercase()
                    val label = format.label ?: if (!lang.isNullOrEmpty()) "Subtitle ($lang)" else "Subtitle #$counter"
                    val id = "${groupIndex}_$i"
                    list.add(
                        SubtitleTrack(
                            id = id,
                            name = label,
                            language = format.language,
                            isSelected = isSelected,
                            groupIndex = groupIndex,
                            trackIndex = i
                        )
                    )
                    counter++
                }
            }
        }
        _subtitleTracks.value = list
    }

    fun load(uri: String, title: String?, fileSize: Long? = null) {
        var finalUriString = uri
        val parsedUri = Uri.parse(uri)

        persistUriPermission(getApplication(), parsedUri)

        if (!isMediaStoreUri(parsedUri)) {
            val resolvedUri = resolveStableUri(getApplication(), parsedUri, title, fileSize)
            if (resolvedUri != null) {
                finalUriString = resolvedUri.toString()
            }
        } else if (!canReadUri(getApplication(), parsedUri)) {
            val resolvedUri = resolveStableUri(getApplication(), parsedUri, title, fileSize)
            if (resolvedUri != null) {
                finalUriString = resolvedUri.toString()
            }
        }

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

    private fun persistUriPermission(context: Context, uri: Uri) {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {}
        }
    }

    private fun performLoad(player: Player, uri: String) {
        _lyricData.value = null
        _currentLyricIndex.value = -1
        _mediaType.value = determineMediaType(getApplication(), Uri.parse(uri), _displayTitle.value)

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

        autoSearchLocalSubtitlesAndLyrics(getApplication(), Uri.parse(uri), _displayTitle.value)
    }

    private fun autoSearchLocalSubtitlesAndLyrics(context: Context, mediaUri: Uri, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val baseName = title.substringBeforeLast('.')
            if (baseName.isEmpty()) return@launch

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$baseName.%")
            val collection = MediaStore.Files.getContentUri("external")

            try {
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val id = cursor.getLong(idCol)
                        val fileUri = ContentUris.withAppendedId(collection, id)

                        if (ext == "lrc" && _lyricData.value == null) {
                            context.contentResolver.openInputStream(fileUri)?.use { stream ->
                                val parsed = LrcParser.parse(stream)
                                if (parsed.entries.isNotEmpty()) {
                                    _lyricData.value = parsed
                                }
                            }
                        } else if (ext in setOf("srt", "vtt", "ass", "ssa") && _lyricData.value == null) {
                            context.contentResolver.openInputStream(fileUri)?.use { stream ->
                                val parsed = LrcParser.parse(stream)
                                if (parsed.entries.isNotEmpty()) {
                                    _lyricData.value = parsed
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PlayerViewModel", "Auto search subtitle/lyric failed: ${e.message}")
            }
        }
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
            var lastSaveTime = 0L
            while (isActive) {
                val p = _player.value
                val key = currentPlaybackKey
                if (p != null && p.isPlaying) {
                    val lyrics = _lyricData.value
                    if (lyrics != null && lyrics.entries.isNotEmpty()) {
                        val idx = lyrics.findCurrentIndex(p.currentPosition)
                        if (idx != _currentLyricIndex.value) {
                            _currentLyricIndex.value = idx
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (key != null && now - lastSaveTime >= 2000) {
                        lastSaveTime = now
                        saveProgress(p, key)
                    }
                }
                delay(250)
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
