package io.github.ryancontento.tincan.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Desktop takes an absolute file path. Android's builder needs a Context
 * instead, which is the whole reason this is an expect/actual rather than
 * shared code — and why :data is excluded from the Android CI check until v2
 * supplies that half.
 */
internal actual fun databaseBuilder(directory: String): RoomDatabase.Builder<TinCanDatabase> {
    val file = File(directory, DATABASE_FILE_NAME)
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<TinCanDatabase>(name = file.absolutePath)
}
