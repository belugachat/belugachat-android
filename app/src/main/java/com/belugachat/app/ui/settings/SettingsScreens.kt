package com.belugachat.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.belugachat.app.BuildConfig
import com.belugachat.app.data.DEFAULT_SYSTEM_PROMPT
import com.belugachat.app.ui.components.Card
import com.belugachat.app.ui.components.ListRow
import com.belugachat.app.ui.components.SectionLabel
import com.belugachat.app.ui.components.SubScreen
import com.belugachat.app.update.UpdateState

@Composable
fun rememberNotices(vm: ManageViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.notices.collect { host.showSnackbar(it) } }
    return host
}

@Composable
fun SettingsHome(vm: ManageViewModel, onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val providers by vm.providers.collectAsState()
    val models by vm.models.collectAsState()
    val servers by vm.servers.collectAsState()
    val skills by vm.skills.collectAsState()
    val settings by vm.settings.collectAsState()
    val updateState by vm.updateState.collectAsState()
    var theme by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SubScreen("Settings", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionLabel("Models")
            Card {
                val enabledIds = providers.filter { it.enabled }.map { it.id }.toSet()
                ListRow(
                    "Providers & API keys",
                    "${enabledIds.size} active · ${models.count { it.enabled && it.providerId in enabledIds }} models on",
                    Icons.Outlined.Key,
                    onClick = { navigate("providers") },
                )
            }
            SectionLabel("Assistant")
            Card {
                ListRow("Default system prompt", settings.systemPrompt.lineSequence().first().take(60), Icons.Outlined.Tune, onClick = { navigate("prompt") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("Tools", "Web search, web fetch, time, MCP tools", Icons.Outlined.Build, onClick = { navigate("tools") })
            }
            SectionLabel("Extensions")
            Card {
                ListRow("MCP servers", "${servers.size} configured · ${servers.count { it.enabled }} on", Icons.Outlined.Hub, onClick = { navigate("mcp") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("Skills", "${skills.size} installed · ${skills.count { it.enabled }} on", Icons.Outlined.AutoStories, onClick = { navigate("skills") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("Marketplace", "Browse skills, plugins and MCP servers", Icons.Outlined.Storefront, onClick = { navigate("marketplace") })
            }
            SectionLabel("Chats")
            Card {
                ListRow("Manage chats", "Select and delete multiple conversations", Icons.Outlined.DeleteSweep, onClick = { navigate("chats") })
            }
            SectionLabel("App")
            Card {
                ListRow("Appearance", when (settings.theme) { "dark" -> "Dark"; "light" -> "Light"; else -> "System default" }, Icons.Outlined.Palette, onClick = { theme = true })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("Analytics", "Tokens, activity and model breakdown", Icons.Outlined.Analytics, onClick = { navigate("analytics") })
            }
            SectionLabel("About")
            Card {
                ListRow(
                    "Beluga Chat ${BuildConfig.VERSION_NAME}",
                    "Keys stay encrypted on this device. No central server or tracking.",
                    Icons.Outlined.Info,
                )
                if (BuildConfig.ENABLE_SELF_UPDATE) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val updateSubtitle = when (val s = updateState) {
                        UpdateState.Checking -> "Checking…"
                        UpdateState.UpToDate -> "You're up to date (v${BuildConfig.VERSION_NAME})"
                        is UpdateState.Available -> "Update available: v${s.release.version}"
                        is UpdateState.Downloading -> "Downloading v${s.release.version}…"
                        is UpdateState.ReadyToInstall -> "Ready to install v${s.release.version}"
                        is UpdateState.Error -> s.message
                        UpdateState.Idle -> "Current version ${BuildConfig.VERSION_NAME}"
                    }
                    ListRow(
                        "Check for updates",
                        updateSubtitle,
                        Icons.Outlined.SystemUpdate,
                        onClick = { vm.checkForUpdates() },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    "Website & setup guides",
                    "https://belugachat.app",
                    Icons.Outlined.Language,
                    onClick = { openUrl("https://belugachat.app") },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    "GitHub source code",
                    "https://github.com/${BuildConfig.GITHUB_REPO}",
                    Icons.Outlined.Code,
                    onClick = { openUrl("https://github.com/${BuildConfig.GITHUB_REPO}") },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    "Manrope font license",
                    "SIL Open Font License 1.1",
                    Icons.Outlined.Description,
                    onClick = { showLicense = true },
                )
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    if (theme) {
        AlertDialog(
            onDismissRequest = { theme = false },
            title = { Text("Appearance") },
            text = {
                Column {
                    listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setTheme(value); theme = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.theme == value, onClick = { vm.setTheme(value); theme = false })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { theme = false }) { Text("Done") } },
        )
    }

    if (showLicense) {
        val licenseText = remember {
            runCatching {
                context.assets.open("licenses/Manrope-OFL.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("Manrope font is licensed under the SIL Open Font License, Version 1.1.")
        }
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("Manrope License (OFL 1.1)") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(licenseText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } },
        )
    }
}

@Composable
fun PromptScreen(vm: ManageViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val snackbar = rememberNotices(vm)
    var text by rememberSaveable(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    SubScreen("Default system prompt", onBack, snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Sent at the start of every chat. A chat can override it from the + menu → Chat instructions.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                minLines = 8,
                maxLines = 16,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.savePrompt(text) }, enabled = text != settings.systemPrompt) { Text("Save") }
                OutlinedButton(onClick = { text = DEFAULT_SYSTEM_PROMPT; vm.resetPrompt() }) { Text("Reset to default") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
