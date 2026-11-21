package com.belugachat.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belugachat.app.R
import com.belugachat.app.data.Role
import com.belugachat.app.ui.components.ConfirmDialog
import com.belugachat.app.ui.components.TextFieldDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onConnectDeepSeek: () -> Unit,
    onAddProvider: () -> Unit,
    onOpenMarketplace: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }

    val conversations by vm.conversations.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val conversationId by vm.conversationId.collectAsState()
    val conversation by vm.conversation.collectAsState()
    val thread by vm.thread.collectAsState()
    val running by vm.running.collectAsState()
    val models by vm.models.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    val reasoning by vm.reasoning.collectAsState()
    val providers by vm.providers.collectAsState()
    val draft by vm.draft.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val attaching by vm.attaching.collectAsState()
    val skills by vm.skills.collectAsState()
    val settings by vm.settings.collectAsState()

    var showModels by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.notices.collect { snackbar.showSnackbar(it) } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ChatDrawer(
                conversations = conversations,
                searchResults = searchResults,
                currentId = conversationId,
                onSearch = vm::search,
                onOpen = { vm.open(it); scope.launch { drawer.close() } },
                onNew = { vm.newChat(); scope.launch { drawer.close() } },
                onRename = vm::rename,
                onPin = vm::togglePin,
                onDelete = vm::delete,
                onSettings = { scope.launch { drawer.close() }; onOpenSettings() },
                onMarketplace = { scope.launch { drawer.close() }; onOpenMarketplace() },
            )
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Outlined.Menu, "Chats")
                        }
                    },
                    title = {
                        Surface(
                            onClick = { showModels = true },
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.height(34.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_beluga_mark),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    selected?.model?.displayName ?: "Choose a model",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::newChat) {
                            Icon(Icons.Outlined.Add, "New chat")
                        }
                        if (conversation != null) {
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Outlined.MoreVert, "More")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    val conv = conversation
                                    DropdownMenuItem(
                                        text = { Text("Chat instructions") },
                                        leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                                        onClick = { menu = false; editingPrompt = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                        onClick = { menu = false; renaming = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (conv?.pinned == true) "Unpin" else "Pin") },
                                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                        onClick = { menu = false; conv?.let(vm::togglePin) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share as Markdown") },
                                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                        onClick = {
                                            menu = false
                                            scope.launch {
                                                val (title, text) = vm.exportMarkdown() ?: return@launch
                                                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                                                    .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
                                                context.startActivity(Intent.createChooser(intent, "Share chat"))
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        onClick = { menu = false; deleting = true },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            modifier = Modifier.imePadding(),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f)) {
                    if (thread.isEmpty()) {
                        Welcome(
                            hasProviders = providers.isNotEmpty(),
                            hasModels = models.isNotEmpty(),
                            typing = draft.isNotBlank(),
                            onConnectDeepSeek = onConnectDeepSeek,
                            onChooseProvider = onAddProvider,
                            onOpenProviders = onOpenProviders,
                            onSuggestion = { vm.draft.value = it },
                        )
                    } else {
                        Thread(vm, thread, running)
                    }
                }
                Composer(
                    text = draft,
                    onTextChange = { vm.draft.value = it },
                    attachments = attachments,
                    attaching = attaching,
                    skills = skills,
                    running = running,
                    reasoning = reasoning,
                    selectedPreset = selected?.provider?.preset,
                    selectedModelId = selected?.model?.modelId,
                    onSelectReasoning = vm::setReasoning,
                    onToggleDeepSeekReasoning = {
                        if (!vm.toggleDeepSeekReasoning()) showModels = true
                    },
                    onAttach = vm::attach,
                    onRemove = vm::removeAttachment,
                    onSend = vm::send,
                    onStop = vm::stop,
                )
            }
        }
    }

    if (showModels) {
        ModelSheet(models, selected, reasoning, vm::selectModel, vm::setReasoning, onOpenProviders) { showModels = false }
    }
    val conv = conversation
    if (renaming && conv != null) {
        TextFieldDialog("Rename chat", conv.title, "Title", onConfirm = { vm.rename(conv, it) }, onDismiss = { renaming = false })
    }
    if (editingPrompt && conv != null) {
        TextFieldDialog(
            "Chat instructions", conv.systemPrompt ?: settings.systemPrompt, "System prompt for this chat",
            singleLine = false, onConfirm = { vm.setChatPrompt(if (it.trim() == settings.systemPrompt.trim()) null else it) },
            onDismiss = { editingPrompt = false },
        )
    }
    if (deleting && conv != null) {
        ConfirmDialog("Delete chat?", "“${conv.title}” and all its branches will be deleted.", "Delete", { vm.delete(conv) }, { deleting = false })
    }
}

@Composable
private fun Thread(vm: ChatViewModel, thread: List<ThreadItem>, running: Boolean) {
    // Reversed layout keeps the newest message anchored to the bottom while it streams
    // and when the keyboard opens; scrolling up stays put.
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(thread.size) { if (thread.isNotEmpty()) list.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, reverseLayout = true, contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
            items(thread.asReversed(), key = { it.message.id }) { item ->
                if (item.message.role == Role.USER) {
                    UserMessage(item, busy = running, onEdit = { vm.edit(item.message.id, it) }, onSwitch = { vm.switchVersion(item.message.id, it) })
                } else {
                    AssistantMessage(
                        item, busy = running,
                        onRegenerate = { vm.regenerate(item.message.id) },
                        onBranch = { vm.branch(item.message.id) },
                        onSwitch = { vm.switchVersion(item.message.id, it) },
                        onApprove = vm::approve,
                    )
                }
            }
        }
        if (!atBottom) {
            SmallFloatingActionButton(
                onClick = { scope.launch { list.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) { Icon(Icons.Outlined.ArrowDownward, "Jump to latest") }
        }
    }
}

@Composable
private fun Welcome(
    hasProviders: Boolean,
    hasModels: Boolean,
    typing: Boolean,
    onConnectDeepSeek: () -> Unit,
    onChooseProvider: () -> Unit,
    onOpenProviders: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.ic_beluga_badge),
            contentDescription = "Beluga Chat",
            modifier = Modifier.size(60.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            if (hasModels) "How can I help?" else "Welcome to Beluga Chat",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        if (!hasProviders) {
            Text(
                "Bring your own API key. Private, local, no daily limits.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(20.dp))
            Button(
                onClick = onConnectDeepSeek,
                modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
            ) {
                Icon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connect DeepSeek")
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = onChooseProvider,
                modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
            ) {
                Text("Choose another provider")
            }
            Spacer(Modifier.size(6.dp))
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://belugachat.app/guides/deepseek")))
                    }
                },
            ) {
                Text("Need an API key? 2-minute setup guide")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(14.dp))
            }
        } else if (!hasModels) {
            Text(
                "Switch on at least one model for your provider to start chatting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(20.dp))
            Button(onClick = onOpenProviders) {
                Icon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose models")
            }
        } else if (!typing) {
            Spacer(Modifier.size(16.dp))
            listOf(
                "Explain a concept simply",
                "Summarize a document I attach",
                "Search the web for today's top news",
                "/goal Plan a product launch end to end",
                "/deepsearch Compare the best phones for me",
            ).forEach { suggestion ->
                Surface(
                    onClick = { onSuggestion(suggestion) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(vertical = 4.dp),
                ) {
                    Text(suggestion, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}
