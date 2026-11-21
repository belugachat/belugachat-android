package com.belugachat.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belugachat.app.R
import com.belugachat.app.data.ConversationEntity
import com.belugachat.app.ui.components.ConfirmDialog
import com.belugachat.app.ui.components.ListRow
import com.belugachat.app.ui.components.TextFieldDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun bucket(conv: ConversationEntity): String {
    if (conv.pinned) return "Pinned"
    val day = Instant.ofEpochMilli(conv.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> "Previous 7 days"
        day.isAfter(today.minusDays(30)) -> "Previous 30 days"
        else -> "Older"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatDrawer(
    conversations: List<ConversationEntity>,
    searchResults: List<ConversationEntity>?,
    currentId: String?,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (ConversationEntity, String) -> Unit,
    onPin: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    onSettings: () -> Unit,
    onMarketplace: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_beluga_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("Beluga Chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onNew) { Icon(Icons.Outlined.Add, "New chat") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onSearch(it) },
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; onSearch("") }) { Icon(Icons.Outlined.Close, "Clear") } },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        val list = searchResults ?: conversations
        LazyColumn(Modifier.weight(1f)) {
            if (list.isEmpty()) {
                item {
                    Text(
                        if (searchResults != null) "No chats match." else "Your chats will appear here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
            val groups = if (searchResults != null) mapOf("Results" to list) else list.groupBy(::bucket)
            groups.forEach { (label, items) ->
                item(key = "h-$label") {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                }
                items(items, key = { it.id }) { conv ->
                    val selected = conv.id == currentId
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                            .combinedClickable(onClick = { onOpen(conv.id) }, onLongClick = { menuFor = conv.id })
                            .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (conv.pinned) {
                            Icon(Icons.Outlined.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            conv.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            IconButton(onClick = { menuFor = conv.id }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.MoreVert, "Chat options", Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = menuFor == conv.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                    onClick = { menuFor = null; renaming = conv })
                                DropdownMenuItem(text = { Text(if (conv.pinned) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                    onClick = { menuFor = null; onPin(conv) })
                                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menuFor = null; deleting = conv })
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        ListRow("Marketplace", "Skills and MCP servers", Icons.Outlined.Storefront, onClick = onMarketplace)
        ListRow("Settings", "Providers, prompt, tools", Icons.Outlined.Settings, onClick = onSettings)
        Spacer(Modifier.size(8.dp))
    }

    renaming?.let { conv ->
        TextFieldDialog("Rename chat", conv.title, "Title", onConfirm = { onRename(conv, it) }, onDismiss = { renaming = null })
    }
    deleting?.let { conv ->
        ConfirmDialog("Delete chat?", "“${conv.title}” and all its branches will be deleted.", "Delete", { onDelete(conv) }, { deleting = null })
    }
}
