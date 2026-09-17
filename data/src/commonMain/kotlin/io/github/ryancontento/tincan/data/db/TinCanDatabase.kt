package io.github.ryancontento.tincan.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.ryancontento.tincan.data.appDataDir
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@ConstructedBy(TinCanDatabaseConstructor::class)
abstract class TinCanDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}

/**
 * Room generates the actual for this. The suppression is required, not a
 * workaround — the compiler cannot see the generated implementation at the
 * point it checks for a matching actual declaration.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object TinCanDatabaseConstructor : RoomDatabaseConstructor<TinCanDatabase> {
    override fun initialize(): TinCanDatabase
}

/** Supplies the platform's builder; the shared configuration is applied below. */
internal expect fun databaseBuilder(directory: String): RoomDatabase.Builder<TinCanDatabase>

internal fun createDatabase(directory: String = appDataDir()): TinCanDatabase =
    databaseBuilder(directory)
        // The bundled driver ships its own SQLite rather than relying on one
        // being present, which is what makes Room work off Android at all.
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

const val DATABASE_FILE_NAME = "tincan.db"
