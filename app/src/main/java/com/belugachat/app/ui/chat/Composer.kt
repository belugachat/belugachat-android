package com.belugachat.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.belugachat.app.chat.AgentMode
import com.belugachat.app.chat.AttachmentStore
import com.belugachat.app.data.Part
import com.belugachat.app.data.Reasoning
import com.belugachat.app.data.SkillEntity
import com.belugachat.app.ui.theme.LocalDarkTheme
import java.io.File

private val reasoningChipLabels = mapOf(
    Reasoning.DEFAULT to "Auto",
    Reasoning.LOW to "Low",
    Reasoning.MEDIUM to "Medium",
    Reasoning.HIGH to "High",
    Reasoning.MAX to "Max",
)

@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<Part.File>,
    attaching: Int,
    skills: List<SkillEntity>,
    running: Boolean,
    reasoning: String,
    selectedPreset: String?,
    selectedModelId: String?,
    onSelectReasoning: (String) -> Unit,
    onToggleDeepSeekReasoning: () -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit,
    onRemove: (Part.File) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    var menu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { if (it.isNotEmpty()) onAttach(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onAttach(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> cameraFile?.let { if (ok) onAttach(listOf(it)) } }

    // Keep the cursor position while the text is also driven from outside (drafts, skill picks).
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))

    val mention = Regex("(^|\\s)@([a-z0-9._-]*)$").find(field.text.substring(0, field.selection.end.coerceAtMost(field.text.length)))
    val suggestions = if (mention != null) skills.filter { it.name.startsWith(mention.groupValues[2]) }.take(5) else emptyList()
    val commandToken = Regex("^\\s*/([A-Za-z_-]*)$").find(field.text)
    val commandSuggestions = if (commandToken != null) {
        AgentMode.suggestions.filter { it.first.removePrefix("/").startsWith(commandToken.groupValues[1].lowercase()) }
    } else {
        emptyList()
    }
    val mode = AgentMode.detect(field.text)
    val modeLabel = AgentMode.label(mode)

    val deepSeekMediaWarning = selectedPreset == "deepseek" && attachments.any {
        val kind = AttachmentStore.kindOf(it.mime, it.name)
        kind == AttachmentStore.Kind.IMAGE || kind == AttachmentStore.Kind.PDF
    }

    fun applyCommand(command: String) {
        val next = "$command "
        onTextChange(next)
        field = TextFieldValue(next, TextRange(command.length + 1))
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (commandSuggestions.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Column {
                    commandSuggestions.forEach { (command, description) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { applyCommand(command) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(command, style = MaterialTheme.typography.labelLarge)
                                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else if (suggestions.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Column {
                    suggestions.forEach { skill ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val start = mention!!.range.first + mention.groupValues[1].length
                                val updated = field.text.substring(0, start) + "@${skill.name} " + field.text.substring(field.selection.end)
                                onTextChange(updated)
                            }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.AutoStories, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("@${skill.name}", style = MaterialTheme.typography.labelLarge)
                                Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (deepSeekMediaWarning) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "DeepSeek API models currently accept text, code, and DOCX files rather than images or PDFs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        val pillBg = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = pillBg,
            shadowElevation = if (dark) 0.dp else 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
        ) {
            Column {
                if (attachments.isNotEmpty() || attaching > 0) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        attachments.forEach { AttachmentPreview(it) { onRemove(it) } }
                        if (attaching > 0) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
                        .heightIn(min = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (field.text.isEmpty()) {
                        Text(
                            if (skills.isEmpty()) "Message Beluga Chat…" else "Message · @ skills, / modes",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it; onTextChange(it.text) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .clickable { menu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Add, "Attach", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Photos") },
                                leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                onClick = { menu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                leadingIcon = { Icon(Icons.Outlined.CameraAlt, null) },
                                onClick = {
                                    menu = false
                                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "photo-${System.currentTimeMillis()}.jpg"))
                                    cameraFile = uri
                                    camera.launch(uri)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Files and documents") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                                onClick = { menu = false; pickFiles.launch(arrayOf("*/*")) },
                            )
                            HorizontalDivider()
                            AgentMode.suggestions.forEach { (command, description) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(command)
                                            Text(
                                                description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                                    onClick = {
                                        menu = false
                                        applyCommand(command)
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))

                    val isDeepSeek = selectedPreset == "deepseek"
                    val isDeepSeekReasoner = isDeepSeek && (selectedModelId?.contains("reasoner", ignoreCase = true) == true)
                    val reasoningActive = if (isDeepSeek) isDeepSeekReasoner else reasoning != Reasoning.DEFAULT
                    val reasoningBg = when {
                        isDeepSeek && isDeepSeekReasoner -> MaterialTheme.colorScheme.primary
                        reasoningActive -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                    val reasoningFg = when {
                        isDeepSeek && isDeepSeekReasoner -> MaterialTheme.colorScheme.onPrimary
                        reasoningActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val reasoningText = when {
                        isDeepSeek -> "Reasoning"
                        reasoning == Reasoning.DEFAULT -> "Reasoning"
                        else -> "Reasoning: ${reasoningChipLabels[reasoning] ?: reasoning}"
                    }

                    Box {
                        Surface(
                            onClick = {
                                if (isDeepSeek) onToggleDeepSeekReasoning() else reasoningMenu = true
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = reasoningBg,
                            modifier = Modifier.height(30.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(14.dp), tint = reasoningFg)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    reasoningText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = reasoningFg,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (!isDeepSeek) {
                            DropdownMenu(expanded = reasoningMenu, onDismissRequest = { reasoningMenu = false }) {
                                Reasoning.all.forEach { level ->
                                    val picked = reasoning == level
                                    DropdownMenuItem(
                                        text = { Text(reasoningChipLabels.getValue(level)) },
                                        trailingIcon = {
                                            if (picked) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        },
                                        onClick = {
                                            reasoningMenu = false
                                            onSelectReasoning(level)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    if (modeLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.height(30.dp),
                        ) {
                            Row(
                                Modifier.padding(start = 10.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    modeLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Outlined.Close,
                                    "Clear mode",
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .clickable { onTextChange(AgentMode.removeCommand(field.text)) },
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    } else {
                        Surface(
                            onClick = { applyCommand("/goal") },
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.height(30.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "/goal",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    val canSend = (field.text.isNotBlank() || attachments.isNotEmpty()) && attaching == 0
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    running -> MaterialTheme.colorScheme.onSurface
                                    canSend -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            )
                            .clickable(enabled = running || canSend) { if (running) onStop() else onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (running) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                            if (running) "Stop" else "Send",
                            Modifier.size(18.dp),
                            tint = when {
                                running -> MaterialTheme.colorScheme.surface
                                canSend -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
