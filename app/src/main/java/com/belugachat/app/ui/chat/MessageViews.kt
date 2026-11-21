package com.belugachat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.belugachat.app.chat.MessageTree
import com.belugachat.app.container
import com.belugachat.app.data.MessageStatus
import com.belugachat.app.data.Part
import com.belugachat.app.data.ToolStatus
import com.belugachat.app.data.plainText
import com.belugachat.app.ui.markdown.Markdown
import com.belugachat.app.ui.theme.LocalDarkTheme
import com.belugachat.app.ui.theme.MonoStyle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun UserMessage(
    item: ThreadItem,
    busy: Boolean,
    onEdit: (String) -> Unit,
    onSwitch: (Int) -> Unit,
) {
    val text = item.parts.plainText()
    val files = item.parts.filterIsInstance<Part.File>()
    var editing by rememberSaveable(item.message.id) { mutableStateOf(false) }
    val dark = LocalDarkTheme.current
    val bubbleColor = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
        if (files.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                files.forEach { AttachmentPreview(it) }
            }
        }
        if (editing) {
            var value by rememberSaveable(item.message.id + "edit") { mutableStateOf(text) }
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 10, shape = RoundedCornerShape(18.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { editing = false; onEdit(value) }, enabled = value.isNotBlank() && !busy) { Text("Send") }
                }
            }
        } else if (text.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = bubbleColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
                    modifier = Modifier.widthIn(max = maxWidth * 0.82f),
                ) {
                    SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VersionNav(item.siblings, onSwitch)
                CopyButton(text)
                SmallAction(Icons.Outlined.Edit, "Edit", enabled = !busy) { editing = true }
            }
        }
    }
}

@Composable
fun AssistantMessage(
    item: ThreadItem,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    onSwitch: (Int) -> Unit,
    onApprove: (Part.ToolCall, Boolean, Boolean) -> Unit,
) {
    val message = item.message
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize()) {
        val visible = item.parts.filter { !(it is Part.Thinking && it.text.isBlank()) }
        if (visible.isEmpty() && item.streaming) {
            TypingIndicator()
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.forEachIndexed { index, part ->
                when (part) {
                    is Part.Text -> SelectionContainer { Markdown(part.text) }
                    is Part.Thinking -> ThinkingBlock(
                        text = part.text,
                        durationMs = part.durationMs,
                        active = item.streaming && index == visible.lastIndex,
                    )
                    is Part.ToolCall -> ToolCard(part, onApprove)
                    is Part.File -> Unit
                }
            }
        }
        if (item.streaming && visible.isNotEmpty() && visible.last() !is Part.Text) {
            Spacer(Modifier.size(8.dp))
            TypingIndicator()
        }
        if (message.error != null && !item.streaming) {
            Spacer(Modifier.size(8.dp))
            ErrorCard(message.error, if (message.status == MessageStatus.ERROR) onRegenerate else null)
        } else if (message.status == MessageStatus.STOPPED && !item.streaming) {
            Text("Stopped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        if (!item.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                CopyButton(item.parts.plainText())
                SmallAction(Icons.Outlined.Refresh, "Regenerate", enabled = !busy, onClick = onRegenerate)
                SmallAction(Icons.AutoMirrored.Outlined.CallSplit, "Branch into new chat", onClick = onBranch)
                VersionNav(item.siblings, onSwitch)
                Spacer(Modifier.weight(1f))
                message.modelId?.let {
                    Text(
                        it + if (message.outputTokens > 0) " · ${message.outputTokens} tok" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionNav(siblings: MessageTree.Siblings, onSwitch: (Int) -> Unit) {
    if (siblings.count <= 1) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSwitch(-1) }, enabled = siblings.index > 0, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.ChevronLeft, "Previous version", Modifier.size(18.dp))
        }
        Text("${siblings.index + 1}/${siblings.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { onSwitch(1) }, enabled = siblings.index < siblings.count - 1, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.ChevronRight, "Next version", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, label, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    SmallAction(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Copy", enabled = text.isNotEmpty()) {
        clipboard.setText(AnnotatedString(text)); copied = true
    }
}

@Composable
fun AttachmentPreview(part: Part.File, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    val file = remember(part.path) { context.container.attachments.file(part) }
    Box {
        if (part.mime.startsWith("image/")) {
            AsyncImage(
                model = file, contentDescription = part.name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        } else {
            Row(
                Modifier.heightIn(min = 52.dp).widthIn(max = 220.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(part.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatSize(part.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onRemove != null) Spacer(Modifier.width(20.dp))
            }
        }
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Text("✕", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.inverseOnSurface) }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@Composable
private fun ThinkingBlock(text: String, durationMs: Long?, active: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val open = expanded || active
    val label = when {
        active -> "Thinking…"
        durationMs != null && durationMs >= 1000L -> "Thought for ${durationMs / 1000L}s"
        else -> "Thought process"
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(2.5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(open) {
                Box(Modifier.padding(top = 8.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(text.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun toolSummary(call: Part.ToolCall): Pair<ImageVector, String> {
    val args = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
    fun arg(key: String) = args?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    return when (call.name) {
        "web_search" -> Icons.Outlined.Search to "Searched the web for “${arg("query").orEmpty()}”"
        "web_fetch" -> Icons.Outlined.Language to "Read ${arg("url").orEmpty().removePrefix("https://").removePrefix("http://").take(60)}"
        "current_time" -> Icons.Outlined.Schedule to "Checked the time"
        "view_skill" -> Icons.Outlined.AutoStories to "Loaded skill ${arg("name").orEmpty()}${arg("file")?.let { " · $it" }.orEmpty()}"
        "spawn_subagent" -> {
            val label = arg("role")?.takeIf { it.isNotBlank() }
                ?: arg("task")?.replace(Regex("\\s+"), " ")?.take(48)?.takeIf { it.isNotBlank() }
            Icons.Outlined.AutoAwesome to (if (label != null) "Subagent · $label" else "Ran subagent")
        }
        else -> Icons.Outlined.Build to "Used ${call.name}"
    }
}

@Composable
private fun ToolCard(call: Part.ToolCall, onApprove: (Part.ToolCall, Boolean, Boolean) -> Unit) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val (icon, summary) = remember(call.name, call.arguments) { toolSummary(call) }
    val awaiting = call.status == ToolStatus.AWAITING_APPROVAL
    val borderColor = if (awaiting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            when (call.status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                ToolStatus.DONE -> Icon(Icons.Outlined.Check, "Done", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolStatus.ERROR -> Icon(Icons.Outlined.ErrorOutline, "Failed", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                ToolStatus.REJECTED -> Text("Declined", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolStatus.AWAITING_APPROVAL -> Unit
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (awaiting) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text("Allow ${call.name} to run?", style = MaterialTheme.typography.bodyMedium)
                Text(call.arguments.take(300), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onApprove(call, false, false) }) { Text("Deny") }
                    Button(onClick = { onApprove(call, true, false) }) { Text("Allow") }
                    TextButton(onClick = { onApprove(call, true, true) }) { Text("Always") }
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Input", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(call.arguments, style = MonoStyle)
                call.result?.let {
                    Text("Result", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer { Text(it.take(6000), style = MonoStyle, color = if (call.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(error: String, onRetry: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i",
            )
            Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}
