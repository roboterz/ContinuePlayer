package com.aerolite.lgplayer.data.database

import androidx.room.*
import com.aerolite.lgplayer.data.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY addedAt DESC")
    fun getAllItems(): Flow<List<PlaylistItem>>

    @Query("SELECT * FROM playlist WHERE type = :type ORDER BY addedAt DESC")
    fun getItemsByType(type: MediaType): Flow<List<PlaylistItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItem(item: PlaylistItem)

    @Delete
    suspend fun removeItem(item: PlaylistItem)

    @Query("DELETE FROM playlist WHERE mediaUri = :uri")
    suspend fun removeItemByUri(uri: String)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist WHERE mediaUri = :uri)")
    suspend fun isItemInPlaylist(uri: String): Boolean
}
