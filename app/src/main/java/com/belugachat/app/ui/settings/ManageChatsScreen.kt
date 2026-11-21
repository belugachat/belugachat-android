package com.belugachat.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belugachat.app.data.ConversationEntity
import com.belugachat.app.ui.components.ConfirmDialog
import com.belugachat.app.ui.components.EmptyState
import com.belugachat.app.ui.components.SubScreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ManageChatsScreen(vm: ManageViewModel, onBack: () -> Unit) {
    val conversations by vm.conversations.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = rememberNotices(vm)
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }
    val allSelected = conversations.isNotEmpty() && conversations.all { it.id in selected }

    SubScreen("Manage chats", onBack, snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Select conversations to delete. Deleting a chat removes all of its messages and branches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        selected = if (allSelected) emptySet() else conversations.map { it.id }.toSet()
                    },
                    enabled = conversations.isNotEmpty(),
                ) {
                    Text(if (allSelected) "Clear selection" else "Select all")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${selected.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (conversations.isEmpty()) {
                EmptyState(
                    Icons.Outlined.ChatBubbleOutline,
                    "No chats yet",
                    "Start a conversation and it will appear here.",
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(conversations, key = { it.id }) { conversation ->
                        val checked = conversation.id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - conversation.id else selected + conversation.id
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (checked) selected - conversation.id else selected + conversation.id
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conversation.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    chatSubtitle(conversation),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (conversation.pinned) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Outlined.PushPin,
                                    "Pinned",
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 56.dp),
                        )
                    }
                }
                if (selected.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${selected.size} chat${if (selected.size == 1) "" else "s"} selected",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "This can't be undone.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = { confirming = true },
                                enabled = !busy,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirming) {
        val count = selected.size
        ConfirmDialog(
            "Delete $count chat${if (count == 1) "" else "s"}?",
            "All messages and branches in the selected conversations will be permanently deleted.",
            "Delete",
            {
                vm.deleteChats(selected)
                selected = emptySet()
            },
            { confirming = false },
        )
    }
}

private fun chatSubtitle(conversation: ConversationEntity): String {
    val date = Instant.ofEpochMilli(conversation.updatedAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    return buildList {
        if (conversation.pinned) add("Pinned")
        add("Updated $date")
    }.joinToString(" · ")
}
