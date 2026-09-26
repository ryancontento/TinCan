package io.github.ryancontento.tincan.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.ryancontento.tincan.data.db.DATABASE_FILE_NAME
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Builds a real version-1 database from the committed v1 schema, the file every
 * existing install has, then opens it with the current code.
 */
class MigrationTest {

    @Test
    fun a_version_1_database_opens_with_its_history_intact() = runTest {
        val dir = File(System.getProperty("java.io.tmpdir"), "tincan-migrate-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            createVersion1Database(File(dir, DATABASE_FILE_NAME))

            val repo = createChatRepository(dir.absolutePath)
            try {
                val conversation = repo.observeConversations().first().single()
                assertEquals("Written by v1", conversation.title)
                assertFalse(conversation.pinned)
                assertNull(conversation.temperature)
                assertNull(conversation.numCtx)

                assertEquals(listOf("hello from v1"), repo.historyFor(conversation.id).map { it.content })

                // The new table exists and takes rows.
                val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
                repo.appendUserMessage(conversation.id, "and now a picture", TEST_SERVER, listOf(ImageAttachment("image/png", png)))
                assertEquals(1, repo.historyFor(conversation.id).last().images.size)
            } finally {
                repo.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createVersion1Database(file: File) {
        val schema = Json.parseToJsonElement(File(SCHEMA_V1).readText()).jsonObject["database"]!!.jsonObject
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            schema["entities"]!!.jsonArray.forEach { entity ->
                val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                connection.execSQL(entity.jsonObject.createSql(table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { connection.execSQL(it.jsonObject.createSql(table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = 1")

            connection.execSQL(
                "INSERT INTO conversations (id, title, defaultModelId, backendId, systemPrompt, createdAt, updatedAt) " +
                    "VALUES (1, 'Written by v1', 'phi4', 'srv-test', NULL, 1000, 1000)",
            )
            connection.execSQL(
                "INSERT INTO messages (conversationId, role, content, status, createdAt) " +
                    "VALUES (1, 'USER', 'hello from v1', 'COMPLETE', 1001)",
            )
        } finally {
            connection.close()
        }
    }

    private fun JsonObject.createSql(table: String) =
        this["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    private companion object {
        // Tests run from the module directory.
        const val SCHEMA_V1 = "schemas/io.github.ryancontento.tincan.data.db.TinCanDatabase/1.json"
    }
}
