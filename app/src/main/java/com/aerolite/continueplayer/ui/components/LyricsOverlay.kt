package com.aerolite.continueplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerolite.continueplayer.data.lyrics.LyricData

@Composable
fun LyricsOverlay(
    lyricData: LyricData?,
    currentIndex: Int,
    displayTitle: String,
    onLineClick: (Long) -> Unit,
    onLoadExternalFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Smooth scroll to active line
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && lyricData != null && currentIndex < lyricData.entries.size) {
            val targetScrollIndex = maxOf(0, currentIndex - 2)
            listState.animateScrollToItem(targetScrollIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭歌词",
                        tint = Color.White
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = lyricData?.title ?: displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!lyricData?.artist.isNullOrEmpty()) {
                        Text(
                            text = lyricData.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onLoadExternalFile) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = "加载外部歌词",
                        tint = Color.White
                    )
                }
            }

            // Lyrics List or Empty State
            if (lyricData == null || lyricData.entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "暂无匹配歌词",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(
                            onClick = onLoadExternalFile,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.DarkGray,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp).padding(end = 4.dp)
                            )
                            Text(
                                text = "选择外部 LRC / Subtitle 歌词",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 120.dp)
                ) {
                    itemsIndexed(lyricData.entries) { index, entry ->
                        val isSelected = index == currentIndex
                        val color = if (isSelected) {
                            Color(0xFFFFD54F) // Vibrant bright gold for high contrast on dark overlay
                        } else {
                            Color.White.copy(alpha = 0.65f)
                        }
                        val fontSize = if (isSelected) 22.sp else 16.sp
                        val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLineClick(entry.timeMs) }
                                .padding(vertical = 10.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = entry.text,
                                color = color,
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                textAlign = TextAlign.Center,
                                lineHeight = (fontSize.value * 1.3).sp
                            )
                        }
                    }
                }
            }
        }
    }
}
