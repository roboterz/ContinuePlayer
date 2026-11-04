package com.aerolite.continueplayer.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aerolite.continueplayer.data.MediaType

@Entity(tableName = "playback_progress")
data class PlaybackProgress(
    @PrimaryKey val mediaUri: String, // This is the fingerprint
    val originalUri: String,        // This is the actual URI (content:// or file://)
    val name: String,
    val type: MediaType,
    val position: Long,
    val duration: Long,
    val lastUpdated: Long = System.currentTimeMillis()
)
