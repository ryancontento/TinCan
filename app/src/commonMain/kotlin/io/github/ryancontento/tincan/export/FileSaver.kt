package io.github.ryancontento.tincan.export

/**
 * The seam for the one part of export that cannot be shared: a file dialog
 * here, the Storage Access Framework on Android.
 */
interface FileSaver {
    /** Returns where it was written, or null if the user cancelled. */
    suspend fun save(document: ExportDocument): String?
}
