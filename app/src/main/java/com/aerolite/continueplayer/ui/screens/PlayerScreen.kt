package com.aerolite.continueplayer.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.aerolite.continueplayer.data.MediaType
import com.aerolite.continueplayer.ui.PlayerViewModel
import com.aerolite.continueplayer.ui.components.LyricsOverlay
import com.aerolite.continueplayer.ui.components.SubtitleTrackDialog
import com.aerolite.continueplayer.ui.components.VideoSubtitleOverlay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    isInPipMode: Boolean = false
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val isBuffering by viewModel.isBuffering.collectAsState()
    val playbackError by viewModel.playbackError.collectAsState()
    val player by viewModel.player.collectAsState()
    val title by viewModel.displayTitle.collectAsState()
    val resizeMode by viewModel.resizeMode.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val mediaType by viewModel.mediaType.collectAsState()

    // Lyrics and Subtitle states
    val subtitleTracks by viewModel.subtitleTracks.collectAsState()
    val isSubtitleDisabled by viewModel.isSubtitleDisabled.collectAsState()
    val lyricData by viewModel.lyricData.collectAsState()
    val currentLyricIndex by viewModel.currentLyricIndex.collectAsState()
    val isLyricsVisible by viewModel.isLyricsVisible.collectAsState()

    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showFullScreenLyrics by remember { mutableStateOf(false) }

    val currentLyricLineText = remember(lyricData, currentLyricIndex) {
        if (currentLyricIndex >= 0 && lyricData != null && currentLyricIndex < lyricData!!.entries.size) {
            lyricData!!.entries[currentLyricIndex].text
        } else {
            null
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.loadExternalFile(context, it)
        }
    }

    var isLandscape by remember {
        mutableStateOf(context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    val toggleOrientation = {
        val newOrientation = if (isLandscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        activity?.requestedOrientation = newOrientation
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    DisposableEffect(isInPipMode) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (!isInPipMode) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            val window = activity?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var brightness by remember {
        mutableStateOf(activity?.window?.attributes?.screenBrightness ?: -1f)
    }

    var playerViewInstance by remember { mutableStateOf<PlayerView?>(null) }

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    playerViewInstance?.showController()
                }
            )
        }
        .pointerInput(Unit) {
            var startVolume = 0
            var startBrightness = 0f
            var totalDragY = 0f
            var dragType = 0

            detectVerticalDragGestures(
                onDragStart = { offset ->
                    val width = size.width
                    val height = size.height
                    val sideMargin = width * 0.2f
                    val topMargin = height * 0.1f

                    dragType = when {
                        offset.y < topMargin -> 0
                        offset.x < sideMargin -> 1
                        offset.x > width - sideMargin -> 2
                        else -> 0
                    }

                    if (dragType != 0) {
                        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                        if (startBrightness < 0) startBrightness = 0.5f
                        totalDragY = 0f
                    }
                },
                onVerticalDrag = { change, dragAmount ->
                    if (dragType == 0) return@detectVerticalDragGestures

                    change.consume()
                    totalDragY -= dragAmount

                    val dragRatio = if (size.height > 0) totalDragY / size.height else 0f

                    if (dragType == 1) {
                        activity?.let { act ->
                            val newBrightness = (startBrightness + dragRatio).coerceIn(0.01f, 1f)
                            brightness = newBrightness
                            val lp = act.window.attributes
                            lp.screenBrightness = newBrightness
                            act.window.attributes = lp
                        }
                    } else if (dragType == 2) {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val volumeDelta = (dragRatio * maxVol).toInt()
                        val newVol = (startVolume + volumeDelta).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            newVol,
                            0
                        )
                    }
                }
            )
        }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = !isInPipMode
                    this.resizeMode = resizeMode

                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.argb(170, 0, 0, 0),
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                android.graphics.Color.BLACK,
                                null
                            )
                        )
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    }
                    playerViewInstance = this
                }
            },
            update = { view ->
                view.player = player
                view.useController = !isInPipMode
                view.resizeMode = resizeMode
                view.subtitleView?.apply {
                    setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.argb(170, 0, 0, 0),
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                            android.graphics.Color.BLACK,
                            null
                        )
                    )
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                }
                playerViewInstance = view
            },
            modifier = Modifier.fillMaxSize()
        )

        if ((isBuffering || player == null) && !isInPipMode && playbackError == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (playbackError != null && !isInPipMode) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = playbackError!!,
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(
                    onClick = {
                        player?.let {
                            it.prepare()
                            it.play()
                        }
                    }
                ) {
                    Text("Retry")
                }
            }
        }

        // Single-line Video Subtitle Overlay at the bottom of the screen (for Video mode)
        if (mediaType == MediaType.VIDEO && !isInPipMode && isLyricsVisible && !showFullScreenLyrics) {
            VideoSubtitleOverlay(
                currentText = currentLyricLineText,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSubtitleClick = {
                    showFullScreenLyrics = true
                }
            )
        }

        if (!isInPipMode && title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Text(
                    text = title,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row {
                    // Subtitle Button
                    IconButton(
                        onClick = { showSubtitleDialog = true }
                    ) {
                        val hasActiveSubtitle = !isSubtitleDisabled && subtitleTracks.any { it.isSelected }
                        val subtitleTint = if (hasActiveSubtitle) MaterialTheme.colorScheme.primary else Color.White
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = "Subtitles",
                            tint = subtitleTint
                        )
                    }

                    // Lyrics Button
                    IconButton(
                        onClick = {
                            if (mediaType == MediaType.VIDEO && isLyricsVisible && !showFullScreenLyrics) {
                                showFullScreenLyrics = true
                            } else {
                                viewModel.toggleLyricsVisibility()
                            }
                        }
                    ) {
                        val hasLyrics = lyricData != null
                        val lyricsTint = when {
                            isLyricsVisible || showFullScreenLyrics -> MaterialTheme.colorScheme.primary
                            hasLyrics -> Color.White
                            else -> Color.White.copy(alpha = 0.5f)
                        }
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = "Lyrics",
                            tint = lyricsTint
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleRepeatMode() }
                    ) {
                        val icon = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        }
                        val tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.5f) else Color.White
                        Icon(
                            imageVector = icon,
                            contentDescription = "Repeat Mode",
                            tint = tint
                        )
                    }
                    IconButton(
                        onClick = { viewModel.toggleResizeMode() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = "Resize Mode",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = { toggleOrientation() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Rotate",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Subtitle Dialog
        if (showSubtitleDialog && !isInPipMode) {
            SubtitleTrackDialog(
                subtitleTracks = subtitleTracks,
                isSubtitleDisabled = isSubtitleDisabled,
                onSelectTrack = { track ->
                    viewModel.selectSubtitleTrack(track)
                },
                onLoadExternalFile = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = { showSubtitleDialog = false }
            )
        }

        // Full-screen Lyrics Overlay (For Audio mode or when manually requested in Video mode)
        if (!isInPipMode && ((mediaType == MediaType.AUDIO && isLyricsVisible) || showFullScreenLyrics)) {
            LyricsOverlay(
                lyricData = lyricData,
                currentIndex = currentLyricIndex,
                displayTitle = title,
                onLineClick = { timeMs -> viewModel.seekTo(timeMs) },
                onLoadExternalFile = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onClose = {
                    if (showFullScreenLyrics) {
                        showFullScreenLyrics = false
                    } else {
                        viewModel.toggleLyricsVisibility()
                    }
                }
            )
        }
    }
}
