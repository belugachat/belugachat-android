package com.belugachat.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.belugachat.app.data.McpServerEntity
import com.belugachat.app.ui.components.Card
import com.belugachat.app.ui.components.ConfirmDialog
import com.belugachat.app.ui.components.EmptyState
import com.belugachat.app.ui.components.SectionLabel
import com.belugachat.app.ui.components.SubScreen
import com.belugachat.app.ui.markdown.Markdown

@Composable
fun ToolsScreen(vm: ManageViewModel, onBack: () -> Unit) {
    val tools by vm.tools.collectAsState()
    SubScreen("Tools", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "Tools the model may call. “Ask first” shows an Allow / Deny card in the chat before the tool runs.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            tools.groupBy { it.group }.forEach { (group, list) ->
                item(key = "g-$group") { SectionLabel(if (group == "Built-in") group else "MCP · $group") }
                items(list, key = { it.key }) { tool ->
                    Card(Modifier.padding(bottom = 8.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.title, style = MaterialTheme.typography.bodyLarge)
                                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(tool.enabled, { vm.setTool(tool, enabled = it) })
                        }
                        Row(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                            FilterChip(selected = !tool.autoApprove, onClick = { vm.setTool(tool, autoApprove = !tool.autoApprove) }, label = { Text("Ask first") }, enabled = tool.enabled)
                        }
                    }
                }
            }
            item {
                Text(
                    "Skills add a “Load skill” tool automatically while any skill is on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@Composable
fun McpScreen(vm: ManageViewModel, onBack: () -> Unit, onMarketplace: () -> Unit) {
    val servers by vm.servers.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = rememberNotices(vm)
    var editing by remember { mutableStateOf<McpServerEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<McpServerEntity?>(null) }

    SubScreen(
        "MCP servers", onBack, snackbar,
        actions = { IconButton(onClick = onMarketplace) { Icon(Icons.Outlined.Storefront, "Browse registry") } },
        floating = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add server") }) },
    ) { padding ->
        if (servers.isEmpty()) {
            EmptyState(
                Icons.Outlined.Hub, "No MCP servers",
                "Connect remote MCP servers over HTTPS (Streamable HTTP) to give the model new tools. Local stdio servers need a computer and aren't supported on Android.",
                Modifier.padding(padding),
            ) { OutlinedButton(onClick = onMarketplace) { Text("Browse the MCP registry") } }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                items(servers, key = { it.id }) { server ->
                    val tools = remember(server.toolsJson) { vm.serverTools(server) }
                    Card(Modifier.padding(bottom = 10.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = MaterialTheme.typography.bodyLarge)
                                Text(server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(server.enabled, { vm.toggleServer(server, it) })
                        }
                        val status = when {
                            !server.enabled -> "Off"
                            server.status == "connected" -> "Connected · ${tools.size} tools"
                            server.status == "error" -> "Error: ${server.lastError}"
                            else -> "Not connected yet"
                        }
                        Text(
                            status, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = if (server.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (tools.isNotEmpty()) {
                            Text(tools.joinToString(" · ") { it.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        Row(Modifier.padding(horizontal = 4.dp)) {
                            TextButton(onClick = { vm.refreshServer(server) }, enabled = !busy) { Icon(Icons.Outlined.Refresh, null); Text(" Reconnect") }
                            TextButton(onClick = { editing = server }) { Text("Edit") }
                            TextButton(onClick = { deleting = server }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val server = editing
        ServerDialog(
            initialName = server?.name.orEmpty(),
            initialUrl = server?.url.orEmpty(),
            initialHeaders = server?.let(vm::serverHeaders).orEmpty(),
            onSave = { name, url, headers -> vm.saveServer(server?.id, name, url, headers) },
            onDismiss = { creating = false; editing = null },
        )
    }
    deleting?.let { server ->
        ConfirmDialog("Remove ${server.name}?", "Its tools will no longer be available.", "Remove", { vm.deleteServer(server) }, { deleting = null })
    }
}

@Composable
private fun ServerDialog(initialName: String, initialUrl: String, initialHeaders: String, onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var headers by remember { mutableStateOf(initialHeaders) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialUrl.isEmpty()) "Add MCP server" else "Edit MCP server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, placeholder = { Text("https://example.com/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    headers, { headers = it }, label = { Text("Headers (optional)") },
                    placeholder = { Text("Authorization: Bearer …") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                Text("One header per line. Stored encrypted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, url, headers); onDismiss() }, enabled = name.isNotBlank() && url.isNotBlank()) { Text("Save & connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SkillsScreen(vm: ManageViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onMarketplace: () -> Unit) {
    val skills by vm.skills.collectAsState()
    val snackbar = rememberNotices(vm)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSkill) }
    SubScreen(
        "Skills", onBack, snackbar,
        actions = { IconButton(onClick = onMarketplace) { Icon(Icons.Outlined.Storefront, "Marketplace") } },
        floating = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/zip", "text/markdown", "text/plain", "application/octet-stream", "*/*")) },
                icon = { Icon(Icons.Outlined.UploadFile, null) }, text = { Text("Import") },
            )
        },
    ) { padding ->
        if (skills.isEmpty()) {
            EmptyState(
                Icons.Outlined.AutoStories, "No skills yet",
                "Skills are folders of instructions (SKILL.md) the model loads when relevant. Install them from the marketplace or import a .zip / SKILL.md. Type @name in a message to use one directly.",
                Modifier.padding(padding),
            ) { Button(onClick = onMarketplace) { Text("Open marketplace") } }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                items(skills, key = { it.id }) { skill ->
                    Card(Modifier.padding(bottom = 8.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                TextButton(onClick = { onOpen(skill.id) }, contentPadding = PaddingValues(0.dp)) {
                                    Text("@${skill.name}", style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(skill.description.ifBlank { "No description" }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(skill.enabled, { vm.toggleSkill(skill, it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkillDetailScreen(vm: ManageViewModel, skillId: String, onBack: () -> Unit) {
    val skills by vm.skills.collectAsState()
    val skill = skills.firstOrNull { it.id == skillId }
    var deleting by remember { mutableStateOf(false) }
    SubScreen(skill?.let { "@${it.name}" } ?: "Skill", onBack, actions = {
        if (skill != null) IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "Delete skill") }
    }) { padding ->
        if (skill == null) return@SubScreen
        val files = remember(skill.filesJson) { vm.skillFiles(skill) }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Source: ${skill.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Markdown(skill.body)
                if (files.isNotEmpty()) {
                    SectionLabel("Supporting files", Modifier.padding(start = 0.dp))
                    files.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
        }
    }
    if (deleting && skill != null) {
        ConfirmDialog("Delete @${skill.name}?", "The skill and its files will be removed.", "Delete", { vm.deleteSkill(skill); onBack() }, { deleting = false })
    }
}
