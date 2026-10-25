package com.lumen.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE uri = :uri")
    suspend fun get(uri: String): PlaybackStateEntity?

    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)

    /** 끝까지(95% 이상) 본 항목은 이어보기 목록에서 뺀다. */
    @Query(
        "SELECT * FROM playback_state WHERE positionMs > 5000 AND (durationMs <= 0 OR positionMs < durationMs * 0.95) " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    fun observeContinue(limit: Int = 20): Flow<List<PlaybackStateEntity>>

    @Query("SELECT * FROM playback_state")
    fun observeAll(): Flow<List<PlaybackStateEntity>>

    @Query("DELETE FROM playback_state WHERE uri = :uri")
    suspend fun delete(uri: String)
}

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val itemCount: Int,
    val videoCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id, p.name, p.createdAt, COUNT(i.id) AS itemCount, " +
            "COALESCE(SUM(CASE WHEN i.isVideo THEN 1 ELSE 0 END), 0) AS videoCount " +
            "FROM playlist p LEFT JOIN playlist_item i ON i.playlistId = p.id GROUP BY p.id ORDER BY p.createdAt DESC",
    )
    fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_item WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_item WHERE playlistId = :playlistId ORDER BY sortOrder")
    suspend fun items(playlistId: Long): List<PlaylistItemEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist_item WHERE playlistId = :playlistId")
    suspend fun maxOrder(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_item WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("UPDATE playlist_item SET sortOrder = :order WHERE id = :itemId")
    suspend fun setOrder(itemId: Long, order: Int)

    @Transaction
    suspend fun reorder(itemIds: List<Long>) {
        itemIds.forEachIndexed { index, id -> setOrder(id, index) }
    }
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM server ORDER BY name")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM server WHERE id = :id")
    suspend fun get(id: Long): ServerEntity?

    @Upsert
    suspend fun upsert(server: ServerEntity): Long

    @Query("DELETE FROM server WHERE id = :id")
    suspend fun delete(id: Long)
}
