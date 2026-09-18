package io.github.ryancontento.tincan.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * AWT's native save dialog rather than Swing's JFileChooser: it is the one the
 * platform actually uses, so it looks right on Windows and on Linux desktops.
 */
class DesktopFileSaver : FileSaver {

    override suspend fun save(document: ExportDocument): String? {
        // Modal AWT dialogs must be opened on the event dispatch thread.
        val chosen = withContext(Dispatchers.Swing) {
            val dialog = FileDialog(null as Frame?, "Export conversation", FileDialog.SAVE).apply {
                file = document.fileName
                isVisible = true
            }
            dialog.directory?.let { directory -> dialog.file?.let { File(directory, it) } }
        } ?: return null

        return withContext(Dispatchers.IO) {
            chosen.writeText(document.content)
            chosen.absolutePath
        }
    }
}
