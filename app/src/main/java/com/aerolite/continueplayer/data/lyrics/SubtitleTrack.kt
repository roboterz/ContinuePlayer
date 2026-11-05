package com.aerolite.continueplayer.data.lyrics

data class SubtitleTrack(
    val id: String,
    val name: String,
    val language: String?,
    val isSelected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int
)
