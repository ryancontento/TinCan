package io.github.ryancontento.tincan.attach

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.io.File

/** Accepts files dragged from Explorer or a file manager, read the same way Attach reads them. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.acceptFileDrops(drops: FileDrops): Modifier {
    val scope = rememberCoroutineScope()
    val target = remember(drops) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = drops.hover(true)
            override fun onExited(event: DragAndDropEvent) = drops.hover(false)
            override fun onEnded(event: DragAndDropEvent) = drops.hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    ?.filterIsInstance<File>()
                    ?: return false
                scope.launch(Dispatchers.IO) { drops.drop(files.mapNotNull(::readForAttaching)) }
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )
}
