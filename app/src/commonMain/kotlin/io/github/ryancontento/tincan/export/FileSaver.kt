package io.github.ryancontento.tincan.export

/** The platform seam: a file dialog on desktop, the Storage Access Framework on Android. */
interface FileSaver {
    /** Returns where it was written, or null if the user cancelled. */
    suspend fun save(document: ExportDocument): String?
}
