package com.aerolite.continueplayer.data.database

import androidx.room.TypeConverter
import com.aerolite.continueplayer.data.MediaType

class MediaTypeConverter {
    @TypeConverter
    fun fromMediaType(type: MediaType): String {
        return type.name
    }

    @TypeConverter
    fun toMediaType(value: String): MediaType {
        return MediaType.valueOf(value)
    }
}
