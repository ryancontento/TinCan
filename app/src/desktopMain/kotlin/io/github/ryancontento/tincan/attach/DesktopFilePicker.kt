package io.github.ryancontento.tincan.attach

import io.github.ryancontento.tincan.data.ImageAttachment
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
        // Modal AWT dialogs must be opened on the event dispatch thread.
        val files = withContext(Dispatchers.Swing) {
            val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true
            }
            dialog.files.orEmpty().toList()
        }
        return withContext(Dispatchers.IO) { files.mapNotNull(::readForAttaching) }
    }

    override fun clipboardImage(): PickedFile? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        when {
            // A screenshot, or an image copied from a browser.
            clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                val image = clipboard.getData(DataFlavor.imageFlavor) as Image
                PickedFile("pasted image.png", shrinkImage(image.toPng()))
            }
            // An image file copied in Explorer or a file manager.
            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val files = clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>
                files.firstOrNull()?.let(::readForAttaching)?.takeIf { ImageAttachment.sniffMimeType(it.bytes) != null }
            }
            else -> null
        }
    }.getOrNull()

    private fun Image.toPng(): ByteArray {
        val buffered = this as? BufferedImage ?: BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB)
            .also { it.createGraphics().apply { drawImage(this@toPng, 0, 0, null); dispose() } }
        return ByteArrayOutputStream().use { out -> ImageIO.write(buffered, "png", out); out.toByteArray() }
    }
}

/** Shared by dialog, clipboard and drop: images shrunk, and a file too big to be worth reading sent by size alone. */
fun readForAttaching(file: File): PickedFile? = runCatching {
    val size = file.length()
    if (size > SOURCE_LIMIT_BYTES) return@runCatching PickedFile(file.name, ByteArray(0), size)
    val bytes = file.readBytes()
    PickedFile(file.name, if (ImageAttachment.sniffMimeType(bytes) != null) shrinkImage(bytes) else bytes)
}.getOrNull()

/** Bigger than any accepted attachment, so a large photo can still be shrunk under the limit. */
private const val SOURCE_LIMIT_BYTES = 40L * 1024 * 1024
