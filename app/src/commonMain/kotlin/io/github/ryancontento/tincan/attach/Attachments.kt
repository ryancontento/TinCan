package io.github.ryancontento.tincan.attach

import io.github.ryancontento.tincan.data.ImageAttachment

/** A file the user picked or pasted, before it is judged. Too-large files arrive with no bytes read. */
class PickedFile(val name: String, val bytes: ByteArray, val sizeBytes: Long = bytes.size.toLong())

/** The platform half: a file dialog here, the Storage Access Framework on Android. */
interface FilePicker {
    /** Empty if the dialog was dismissed. */
    suspend fun pick(): List<PickedFile>

    /** An image on the clipboard, or null so an ordinary text paste goes ahead. */
    fun clipboardImage(): PickedFile?
}

sealed interface Attachment {
    class Image(val image: ImageAttachment, val name: String) : Attachment

    /** Text goes into the message itself, so the user can see and trim what is sent. */
    class Text(val block: String) : Attachment

    class Rejected(val reason: String) : Attachment
}

/** Images by their bytes; anything else is accepted only if it is plainly text. */
fun classify(file: PickedFile): Attachment {
    if (file.sizeBytes > ImageAttachment.MAX_BYTES) {
        return Attachment.Rejected("${file.name} is over ${ImageAttachment.MAX_BYTES / (1024 * 1024)} MB")
    }
    ImageAttachment.sniffMimeType(file.bytes)?.let { mime ->
        return Attachment.Image(ImageAttachment(mime, file.bytes), file.name)
    }
    if (file.sizeBytes > MAX_TEXT_BYTES) return Attachment.Rejected("${file.name} is over ${MAX_TEXT_BYTES / 1024} KB of text")
    val text = decodeText(file.bytes) ?: return Attachment.Rejected("${file.name} is not an image or a text file")
    return Attachment.Text(codeBlock(file.name, text))
}

/** Strict UTF-8, and no NUL bytes: binary files often decode "successfully" otherwise. */
internal fun decodeText(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()?.removePrefix("﻿")
}

/** A fence one backtick longer than any run inside the file, so a file holding ``` stays intact. */
internal fun codeBlock(name: String, text: String): String {
    val longestRun = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    val language = languageFor(name).orEmpty()
    return "`$name`\n$fence$language\n${text.trimEnd()}\n$fence"
}

internal fun languageFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "cs" -> "csharp"
    "py" -> "python"
    "js", "mjs" -> "javascript"
    "ts" -> "typescript"
    "rs" -> "rust"
    "go" -> "go"
    "c", "h" -> "c"
    "cpp", "hpp", "cc" -> "cpp"
    "sh", "bash" -> "bash"
    "ps1" -> "powershell"
    "json" -> "json"
    "yml", "yaml" -> "yaml"
    "toml" -> "toml"
    "xml" -> "xml"
    "html" -> "html"
    "css" -> "css"
    "sql" -> "sql"
    "md" -> "markdown"
    else -> null
}

/** Big enough for most source files; bigger crowds the context window on a local model. */
const val MAX_TEXT_BYTES = 200 * 1024
