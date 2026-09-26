package io.github.ryancontento.tincan.attach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** The native open dialog, for the same reason export uses the native save dialog. */
class DesktopFilePicker : FilePicker {

    override suspend fun pick(): List<PickedFile> {
        val files = withContext(Dispatchers.Swing) {
            val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true
            }
            dialog.files.orEmpty().toList()
        }
        return withContext(Dispatchers.IO) { files.mapNotNull(::read) }
    }

    override fun clipboardImage(): PickedFile? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        when {
            // A screenshot, or an image copied from a browser.
            clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                val image = clipboard.getData(DataFlavor.imageFlavor) as Image
                PickedFile("pasted image.png", image.toPng())
            }
            // An image file copied in Explorer or a file manager.
            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val files = clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>
                files.firstOrNull()?.let(::read)?.takeIf { it.bytes.isImage() }
            }
            else -> null
        }
    }.getOrNull()

    /** Reads only files small enough to attach; a huge one is reported by size rather than loaded. */
    private fun read(file: File): PickedFile? = runCatching {
        val size = file.length()
        if (size > READ_LIMIT) PickedFile(file.name, ByteArray(0), size) else PickedFile(file.name, file.readBytes())
    }.getOrNull()

    private fun ByteArray.isImage() = io.github.ryancontento.tincan.data.ImageAttachment.sniffMimeType(this) != null

    private fun Image.toPng(): ByteArray {
        val buffered = this as? BufferedImage ?: BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB)
            .also { it.createGraphics().apply { drawImage(this@toPng, 0, 0, null); dispose() } }
        return ByteArrayOutputStream().use { out -> ImageIO.write(buffered, "png", out); out.toByteArray() }
    }

    private companion object {
        // The largest thing classify() accepts; anything bigger is rejected there by its size.
        const val READ_LIMIT = io.github.ryancontento.tincan.data.ImageAttachment.MAX_BYTES
    }
}
