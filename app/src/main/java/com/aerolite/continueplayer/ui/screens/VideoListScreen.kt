package com.aerolite.continueplayer.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import com.aerolite.continueplayer.ui.VideoListViewModel
import com.aerolite.continueplayer.ui.components.PlaylistItemView
import com.google.accompanist.permissions.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(
    viewModel: VideoListViewModel,
    onVideoClick: (String, String, Long) -> Unit, // uri, name, size
    modifier: Modifier = Modifier
) {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionState = rememberMultiplePermissionsState(permission)
    val videoPlaylist by viewModel.videoPlaylist.collectAsState()
    val musicPlaylist by viewModel.musicPlaylist.collectAsState()
    val history by viewModel.history.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            uris.forEach { viewModel.addToPlaylist(it) }
        }
    )

    LaunchedEffect(Unit) {
        if (!permissionState.allPermissionsGranted) {
            permissionState.launchMultiplePermissionRequest()
        }
        viewModel.loadData()
    }

    Scaffold(
        topBar = {
            Column {
                if (isSearchActive) {
                    SearchTopBar(
                        query = searchQuery,
                        onQueryChange = { viewModel.updateSearchQuery(it) },
                        onClose = {
                            isSearchActive = false
                            viewModel.updateSearchQuery("")
                        }
                    )
                } else {
                    LargeTopAppBar(
                        title = { 
                            Text(
                                "ContinuePlayer",
                                style = MaterialTheme.typography.headlineLarge
                            ) 
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                            titleContentColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                
                if (!isSearchActive) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("视频 (${videoPlaylist.size})") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("音乐 (${musicPlaylist.size})") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("历史") }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { 
                filePickerLauncher.launch(arrayOf("video/*", "audio/*"))
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add to playlist")
            }
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            val displayList = when (selectedTab) {
                0 -> videoPlaylist
                1 -> musicPlaylist
                else -> history
            }
            
            val emptyText = when (selectedTab) {
                0 -> "视频播放列表为空"
                1 -> "音乐播放列表为空"
                else -> "暂无播放历史"
            }
            
            if (isLoading && displayList.isEmpty()) {
                CircularProgressIndicator()
            } else if (displayList.isEmpty()) {
                Text(emptyText)
            } else {
                val filteredList = if (searchQuery.isBlank()) {
                    displayList
                } else {
                    displayList.filter { it.name.contains(searchQuery, ignoreCase = true) }
                }

                if (filteredList.isEmpty() && searchQuery.isNotBlank()) {
                    Text("未找到 matching \"$searchQuery\" 的文件")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredList, key = { it.mediaUri + selectedTab }) { item ->
                            PlaylistItemView(
                                item = item,
                                onClick = { onVideoClick(item.mediaUri, item.name, item.size) },
                                onRemove = if (selectedTab == 0 || selectedTab == 1) {
                                    { viewModel.removeFromPlaylist(item) }
                                } else {
                                    { viewModel.removeFromHistory(item) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search playlist...") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                )
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        }
    )
}
