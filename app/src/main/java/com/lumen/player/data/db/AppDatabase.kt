package com.lumen.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun serverType(value: ServerType): String = value.name

    @TypeConverter
    fun toServerType(value: String): ServerType = ServerType.valueOf(value)
}

@Database(
    entities = [PlaybackStateEntity::class, PlaylistEntity::class, PlaylistItemEntity::class, ServerEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playbackStates(): PlaybackStateDao
    abstract fun playlists(): PlaylistDao
    abstract fun servers(): ServerDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lumen.db").build()
    }
}
