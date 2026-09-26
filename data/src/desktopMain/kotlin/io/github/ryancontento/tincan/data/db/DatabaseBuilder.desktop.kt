package io.github.ryancontento.tincan.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/** Desktop takes a file path; Android needs a Context, hence expect/actual (and :data skips Android CI until v2). */
internal actual fun databaseBuilder(directory: String): RoomDatabase.Builder<TinCanDatabase> {
    val file = File(directory, DATABASE_FILE_NAME)
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<TinCanDatabase>(name = file.absolutePath)
}
