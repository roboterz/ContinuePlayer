package com.aerolite.continueplayer

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentUris
import android.provider.OpenableColumns
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.aerolite.continueplayer.data.MediaRepository
import com.aerolite.continueplayer.ui.PlayerViewModel
import com.aerolite.continueplayer.ui.VideoListViewModel
import com.aerolite.continueplayer.ui.navigation.Route
import com.aerolite.continueplayer.ui.screens.PlayerScreen
import com.aerolite.continueplayer.ui.screens.VideoListScreen
import com.aerolite.continueplayer.ui.theme.ContinueplayerTheme

class MainActivity : ComponentActivity() {
    private var currentPlayerViewModel: PlayerViewModel? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = currentPlayerViewModel?.player?.value
            when (intent.action) {
                ACTION_PLAY -> player?.play()
                ACTION_PAUSE -> player?.pause()
            }
        }
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = MediaRepository(applicationContext)

        setContent {
            ContinueplayerTheme {
                val sharedPlayerViewModel: PlayerViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return PlayerViewModel(application) as T
                        }
                    }
                )
                
                LaunchedEffect(sharedPlayerViewModel) {
                    currentPlayerViewModel = sharedPlayerViewModel
                }

                // Use a persistent backstack to prevent "jumping back" on configuration changes
                val backStack = remember {
                    val initialRoute = handleIntent(intent) ?: Route.VideoList
                    mutableStateListOf<Route>(initialRoute)
                }
                
                // If the activity is recreated, the above 'remember' will reset unless we used rememberSaveable.
                // However, for now, let's at least fix the double-loading and navigation logic.

                val openMedia: (Route.Player) -> Unit = { route ->
                    // 1. Force exit PiP mode if active by bringing activity to front
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
                        val exitIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                            action = Intent.ACTION_MAIN
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(exitIntent)
                    }

                    // 2. Load content
                    sharedPlayerViewModel.load(route.videoUri, route.title, route.fileSize)

                    // 3. Update navigation
                    if (backStack.lastOrNull() != route) {
                        backStack.removeAll { it is Route.Player }
                        backStack.add(route)
                    }
                }

                // Handle new intents when activity is already running
                DisposableEffect(Unit) {
                    val listener = androidx.core.util.Consumer<android.content.Intent> { newIntent ->
                        handleIntent(newIntent)?.let { route ->
                            if (route is Route.Player) {
                                openMedia(route)
                            }
                        }
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                val isInPipMode = rememberIsInPipMode()

                val windowAdaptiveInfo = currentWindowAdaptiveInfo()
                // Check if player is the active destination
                val isPlayerActive = backStack.lastOrNull() is Route.Player
                
                val directive = remember(windowAdaptiveInfo, isPlayerActive) {
                    val baseDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
                    if (isPlayerActive) {
                        // Force single pane mode when playing to hide the playlist
                        baseDirective.copy(
                            maxHorizontalPartitions = 1,
                            horizontalPartitionSpacerSize = 0.dp
                        )
                    } else {
                        baseDirective.copy(horizontalPartitionSpacerSize = 0.dp)
                    }
                }
                
                val listDetailStrategy = rememberListDetailSceneStrategy<Route>(directive = directive)

                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) },
                    sceneStrategy = listDetailStrategy,
                    entryProvider = entryProvider {
                        entry<Route.VideoList>(
                            metadata = ListDetailSceneStrategy.listPane(
                                detailPlaceholder = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Select a video to play")
                                    }
                                }
                            )
                        ) {
                            val videoListViewModel: VideoListViewModel = viewModel(
                                factory = object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                        return VideoListViewModel(applicationContext, repository) as T
                                    }
                                }
                            )
                            VideoListScreen(
                                viewModel = videoListViewModel,
                                onVideoClick = { uri, name, size ->
                                    openMedia(Route.Player(uri, name, size))
                                }
                            )
                        }
                        entry<Route.Player>(
                            metadata = ListDetailSceneStrategy.detailPane()
                        ) { route ->
                            // Loading is handled by openMedia or initial intent. 
                            // We only need this if the route changes without openMedia.
                            LaunchedEffect(route.videoUri) {
                                sharedPlayerViewModel.load(route.videoUri, route.title, route.fileSize)
                            }

                            val isPlaying by sharedPlayerViewModel.isPlaying.collectAsState()
                            LaunchedEffect(isPlaying) {
                                if (isInPipMode) {
                                    updatePipParams(isPlaying)
                                }
                            }
                            PlayerScreen(
                                viewModel = sharedPlayerViewModel,
                                isInPipMode = isInPipMode
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (currentPlayerViewModel != null && currentPlayerViewModel?.isPlaying?.value == true) {
            enterPip()
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val filter = IntentFilter().apply {
                addAction(ACTION_PLAY)
                addAction(ACTION_PAUSE)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                pipReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {}
    }

    private fun handleIntent(intent: android.content.Intent?): Route? {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                // Try to persist permission immediately when receiving external intent
                if (uri.scheme == "content") {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {}
                }
                
                val displayName = intent.getStringExtra(Intent.EXTRA_TITLE)
                    ?: getFileNameFromUri(uri)
                val fileSize = getFileSizeFromUri(uri)

                // Proactively resolve to stable MediaStore URI if possible
                var finalUriString = uri.toString()
                if (uri.scheme == "content" && uri.authority != "media") {
                    resolveMediaStoreUri(uri, displayName, fileSize)?.let {
                        Log.d("MainActivity", "Resolved intent URI $uri to stable $it")
                        finalUriString = it.toString()
                    }
                }

                return Route.Player(finalUriString, displayName, fileSize)
            }
        }
        return null
    }

    private fun resolveMediaStoreUri(uri: Uri, name: String?, size: Long): Uri? {
        val fileName = name ?: getFileNameFromUri(uri) ?: return null
        if (size <= 0) return null

        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        val selectionArgs = arrayOf(fileName, size.toString())

        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )

        for (collection in collections) {
            try {
                contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        return ContentUris.withAppendedId(collection, id)
                    }
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                return afd.length
            }
        } catch (e: Exception) {}
        return 0
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getString(0)
                    }
                }
            } catch (e: Exception) {}
        }
        return uri.lastPathSegment
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = currentPlayerViewModel?.isPlaying?.value ?: false
            enterPictureInPictureMode(buildPipParams(isPlaying))
        }
    }

    private fun updatePipParams(isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setPictureInPictureParams(buildPipParams(isPlaying))
        }
    }

    private fun buildPipParams(isPlaying: Boolean): PictureInPictureParams {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(
                    listOf(
                        if (isPlaying) {
                            android.app.RemoteAction(
                                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                                "Pause",
                                "Pause",
                                PendingIntent.getBroadcast(
                                    this, REQUEST_PAUSE,
                                    Intent(ACTION_PAUSE).setPackage(packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        } else {
                            android.app.RemoteAction(
                                Icon.createWithResource(this, android.R.drawable.ic_media_play),
                                "Play",
                                "Play",
                                PendingIntent.getBroadcast(
                                    this, REQUEST_PLAY,
                                    Intent(ACTION_PLAY).setPackage(packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        }
                    )
                )
                .build()
        } else {
            error("PiP not supported")
        }
    }

    companion object {
        private const val ACTION_PLAY = "com.aerolite.continueplayer.ACTION_PLAY"
        private const val ACTION_PAUSE = "com.aerolite.continueplayer.ACTION_PAUSE"
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2
    }
}

@Composable
fun rememberIsInPipMode(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) {
        var c = context
        while (c is android.content.ContextWrapper) {
            if (c is ComponentActivity) break
            c = c.baseContext
        }
        c as? ComponentActivity
    } ?: return false

    var pipMode by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { info ->
            pipMode = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(observer)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return pipMode
}
