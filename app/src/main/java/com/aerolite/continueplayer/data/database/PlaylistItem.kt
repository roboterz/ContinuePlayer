package com.aerolite.continueplayer.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aerolite.continueplayer.data.MediaType

@Entity(tableName = "playlist")
data class PlaylistItem(
    @PrimaryKey val mediaUri: String,
    val name: String,
    val duration: Long,
    val size: Long,
    val type: MediaType,
    val addedAt: Long = System.currentTimeMillis()
)
