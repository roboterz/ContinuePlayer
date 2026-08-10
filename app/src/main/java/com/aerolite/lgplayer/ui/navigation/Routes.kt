package com.aerolite.lgplayer.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Route {
    @Serializable
    data object VideoList : Route

    @Serializable
    data class Player(
        val videoUri: String,
        val title: String? = null,
        val fileSize: Long? = null
    ) : Route
}
