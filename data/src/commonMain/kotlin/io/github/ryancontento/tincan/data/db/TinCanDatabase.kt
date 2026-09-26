package io.github.ryancontento.tincan.data.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.ryancontento.tincan.data.appDataDir
import kotlinx.coroutines.Dispatchers

// v2: per-conversation temperature and num_ctx, pinning, image attachments. Additive, so automatic.
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
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
        .setDriver(SecureDeleteDriver(BundledSQLiteDriver()))
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** secure_delete is per connection and Room pools several, so it is set as each one opens. */
private class SecureDeleteDriver(private val delegate: SQLiteDriver) : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection =
        delegate.open(fileName).also { it.execSQL("PRAGMA secure_delete = ON") }
}

const val DATABASE_FILE_NAME = "tincan.db"
