package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.attach.FileDrops
import io.github.ryancontento.tincan.attach.FilePicker
import io.github.ryancontento.tincan.export.FileSaver
import io.github.ryancontento.tincan.ui.TinDivider
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The main window: conversation rail, top bar, notices, transcript and composer. */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val fileSaver: FileSaver = koinInject()
    val filePicker: FilePicker = koinInject()
    val fileDrops: FileDrops = koinInject()
    val scope = rememberCoroutineScope()

    // TextFieldValue, because a new line goes in at the caret; saveable, so a trip to Settings keeps it.
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    // Only set mid-drag, so the saved width can arrive late without a flicker.
    var draggedWidth by remember { mutableStateOf<Dp?>(null) }
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    var settingsForConversation by remember { mutableStateOf<Long?>(null) }
    val dropHovering by fileDrops.hovering.collectAsState()

    fun attach(files: List<io.github.ryancontento.tincan.attach.PickedFile>) {
        val blocks = viewModel.attach(files)
        if (blocks.isNotEmpty()) draft = draft.withBlocksAppended(blocks)
    }

    LaunchedEffect(Unit) { viewModel.onShown() }
    LaunchedEffect(fileDrops) { fileDrops.files.collect(::attach) }

    Surface(
        Modifier.fillMaxSize().appShortcuts(
            isGenerating = state.isGenerating,
            onNewConversation = { viewModel.newConversation(); draft = TextFieldValue("") },
            onOpenModelPicker = { modelMenuOpen = true },
            onStop = viewModel::stop,
        ),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box {
            Row(Modifier.fillMaxSize()) {
                ConversationSidebar(
                    width = draggedWidth ?: state.settings.sidebarWidth.dp,
                    onWidthChange = { draggedWidth = it },
                    onWidthCommit = { viewModel.setSidebarWidth(it.value.toInt()) },
                    conversations = state.conversations,
                    activeId = state.activeConversationId,
                    enabled = !state.isGenerating,
                    search = state.search,
                    onSearchChange = viewModel::search,
                    onOpenHit = viewModel::openSearchHit,
                    onSelect = viewModel::select,
                    onNew = viewModel::newConversation,
                    onDelete = viewModel::deleteConversation,
                    onEditConversation = { viewModel.select(it); settingsForConversation = it },
                    onTogglePin = viewModel::setPinned,
                )

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ChatTopBar(
                        state = state,
                        modelMenuOpen = modelMenuOpen,
                        onModelMenuOpenChange = { modelMenuOpen = it },
                        onSelectModel = viewModel::selectModel,
                        onSelectServer = viewModel::selectServer,
                        onOpenConversation = { state.activeConversationId?.let { settingsForConversation = it } },
                        onExport = { viewModel.export(it, fileSaver) },
                        onReload = viewModel::refreshModels,
                        onOpenModels = onOpenModels,
                        onOpenSettings = onOpenSettings,
                    )
                    TinDivider()

                    // weight, not fillMaxHeight, or the column claims the parent again and pushes the composer off.
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                        ContextMeter(state)
                        state.notice?.let { notice ->
                            NoticeBar(
                                notice = notice,
                                onAction = {
                                    when (notice.action) {
                                        NoticeAction.RETRY -> viewModel.checkConnection()
                                        NoticeAction.CONTINUE -> viewModel.continueReply()
                                        NoticeAction.OPEN_SETTINGS -> onOpenSettings()
                                        null -> Unit
                                    }
                                },
                                onDismiss = viewModel::dismissNotice,
                            )
                        }
                        Transcript(
                            state = state,
                            editingMessageId = editingMessageId,
                            onStartEdit = { editingMessageId = it },
                            onCancelEdit = { editingMessageId = null },
                            onSubmitEdit = { id, text -> editingMessageId = null; viewModel.editAndResend(id, text) },
                            onContinue = viewModel::continueReply,
                            onRegenerate = viewModel::regenerateLastReply,
                            onScrolledToTarget = viewModel::scrolledToTarget,
                            modifier = Modifier.weight(1f),
                        )
                        Composer(
                            draft = draft,
                            onDraftChange = { draft = it },
                            draftImages = state.draftImages,
                            canSend = state.activeModel != null && !state.isGenerating,
                            isGenerating = state.isGenerating,
                            sendKey = state.settings.sendKey,
                            onSend = { if (viewModel.send(draft.text)) draft = TextFieldValue("") },
                            onStop = viewModel::stop,
                            onAttach = { scope.launch { attach(filePicker.pick()) } },
                            onPasteImage = { filePicker.clipboardImage()?.let { attach(listOf(it)); true } ?: false },
                            onRemoveImage = viewModel::removeDraftImage,
                        )
                    }
                }
            }
            if (dropHovering) DropOverlay()
        }
    }

    // Looked up in the live list, so the dialog follows a rename it just saved.
    settingsForConversation
        ?.let { id -> state.conversations.firstOrNull { it.id == id } }
        ?.let { conversation ->
            ConversationSettingsDialog(
                conversation = conversation,
                models = state.availableModels.map { it.id },
                globalSystemPrompt = state.settings.systemPrompt,
                globalTemperature = state.settings.temperature,
                globalNumCtx = state.settings.numCtx,
                onSave = { title, prompt, temperature, numCtx ->
                    viewModel.renameConversation(conversation.id, title)
                    viewModel.setConversationSystemPrompt(conversation.id, prompt)
                    viewModel.setConversationOptions(conversation.id, temperature, numCtx)
                },
                onSelectModel = viewModel::selectModel,
                onDismiss = { settingsForConversation = null },
            )
        }
}

/** Window-level keys, read in preview so they win before a focused text field swallows them. */
private fun Modifier.appShortcuts(
    isGenerating: Boolean,
    onNewConversation: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onStop: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val key = when (event.key) {
        Key.Escape -> ShortcutKey.ESCAPE
        Key.N -> ShortcutKey.N
        Key.K -> ShortcutKey.K
        else -> return@onPreviewKeyEvent false
    }
    when (
        appShortcutFor(
            key = key,
            isKeyDown = event.type == KeyEventType.KeyDown,
            isCtrlPressed = event.isCtrlPressed,
            isMetaPressed = event.isMetaPressed,
            isShiftPressed = event.isShiftPressed,
            isAltPressed = event.isAltPressed,
        )
    ) {
        AppShortcut.NEW_CONVERSATION -> { onNewConversation(); true }
        AppShortcut.FOCUS_MODEL_PICKER -> { onOpenModelPicker(); true }
        // Claimed only while generating, so Escape still closes menus otherwise.
        AppShortcut.STOP_GENERATION -> isGenerating.also { if (it) onStop() }
        null -> false
    }
}

@Composable
private fun DropOverlay() {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.85f))
            .padding(24.dp)
            .border(2.dp, colors.primary, MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        Text("Drop to attach", style = MaterialTheme.typography.titleMedium, color = colors.primary)
    }
}
