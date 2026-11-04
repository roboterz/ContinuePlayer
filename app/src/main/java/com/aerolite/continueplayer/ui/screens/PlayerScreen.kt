package com.aerolite.continueplayer.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.media3.common.Player
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
import androidx.media3.ui.PlayerView
import com.aerolite.continueplayer.ui.PlayerViewModel
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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

    var isLandscape by remember { 
        mutableStateOf(context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    // Toggle orientation function
    val toggleOrientation = {
        val newOrientation = if (isLandscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        activity?.requestedOrientation = newOrientation
        // isLandscape will be updated via configuration change automatically
    }

    // Update isLandscape when configuration changes
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    // Manage system bars visibility for immersive playback
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
    
    // Brightness state (0.0 to 1.0)
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
            var dragType = 0 // 0: None, 1: Brightness, 2: Volume

            detectVerticalDragGestures(
                onDragStart = { offset ->
                    val width = size.width
                    val height = size.height
                    // Define active zones (left 20% and right 20%)
                    val sideMargin = width * 0.2f
                    // Avoid the very top to not interfere with status bar (top 10%)
                    val topMargin = height * 0.1f

                    dragType = when {
                        offset.y < topMargin -> 0 // Ignore if started too high
                        offset.x < sideMargin -> 1 // Left side: Brightness
                        offset.x > width - sideMargin -> 2 // Right side: Volume
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
                        // 亮度控制
                        activity?.let { act ->
                            val newBrightness = (startBrightness + dragRatio).coerceIn(0.01f, 1f)
                            brightness = newBrightness
                            val lp = act.window.attributes
                            lp.screenBrightness = newBrightness
                            act.window.attributes = lp
                        }
                    } else if (dragType == 2) {
                        // 音量控制
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val volumeDelta = (dragRatio * maxVol).toInt()
                        val newVol = (startVolume + volumeDelta).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            newVol,
                            0 // Removed FLAG_SHOW_UI to be less intrusive, or keep it if preferred
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
                    playerViewInstance = this
                }
            },
            update = { view ->
                view.player = player
                view.useController = !isInPipMode
                view.resizeMode = resizeMode
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
    }
}
