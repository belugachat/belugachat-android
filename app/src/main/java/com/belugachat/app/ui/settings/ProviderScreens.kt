package com.belugachat.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.belugachat.app.llm.Presets
import com.belugachat.app.ui.components.Card
import com.belugachat.app.ui.components.ConfirmDialog
import com.belugachat.app.ui.components.EmptyState
import com.belugachat.app.ui.components.ListRow
import com.belugachat.app.ui.components.SectionLabel
import com.belugachat.app.ui.components.SubScreen
import com.belugachat.app.ui.components.TextFieldDialog

@Composable
fun ProvidersScreen(vm: ManageViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val providers by vm.providers.collectAsState()
    val models by vm.models.collectAsState()
    SubScreen(
        "Providers & API keys", onBack,
        floating = { ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add provider") }) },
    ) { padding ->
        if (providers.isEmpty()) {
            EmptyState(
                Icons.Outlined.Key, "No providers yet",
                "Add an API key from any provider. It is encrypted with the Android Keystore and never leaves this phone except to call that provider.",
                Modifier.padding(padding),
            ) { Button(onClick = onAdd) { Text("Add provider") } }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                item { SectionLabel("Connected") }
                items(providers, key = { it.id }) { provider ->
                    val list = models.filter { it.providerId == provider.id }
                    Card(Modifier.padding(bottom = 10.dp)) {
                        ListRow(
                            provider.name,
                            "${Presets.byId(provider.preset).name} · key ${vm.maskedKey(provider)} · ${list.count { it.enabled }}/${list.size} models on",
                            Icons.Outlined.Key,
                            onClick = { onOpen(provider.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddProviderScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    SubScreen("Choose a provider", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "Any service with an OpenAI-compatible or Anthropic-compatible API works, including local servers such as Ollama on your network.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(Presets.all, key = { it.id }) { preset ->
                Card(Modifier.padding(bottom = 8.dp)) { ListRow(preset.name, preset.blurb, onClick = { onPick(preset.id) }) }
            }
        }
    }
}

/** Add (when [providerId] is null) or edit a provider, and manage its models. */
@Composable
fun ProviderScreen(vm: ManageViewModel, providerId: String?, presetId: String, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val providers by vm.providers.collectAsState()
    val allModels by vm.models.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = rememberNotices(vm)
    val context = LocalContext.current
    val provider = providers.firstOrNull { it.id == providerId }
    val preset = Presets.byId(provider?.preset ?: presetId)
    val isNew = providerId == null

    var name by rememberSaveable(provider?.id) { mutableStateOf(provider?.name ?: preset.name) }
    var baseUrl by rememberSaveable(provider?.id) { mutableStateOf(provider?.baseUrl ?: preset.baseUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val customUrl = preset.id.startsWith("custom")

    SubScreen(if (isNew) "Add ${preset.name}" else provider?.name ?: "Provider", onBack, snackbar, actions = {
        if (!isNew) IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "Delete provider") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    if (customUrl || !isNew) {
                        OutlinedTextField(
                            baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true,
                            placeholder = { Text(if (preset.kind == "anthropic") "https://host" else "https://host/v1") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    OutlinedTextField(
                        key, { key = it }, Modifier.fillMaxWidth(),
                        label = { Text(if (isNew) "API key" else "Replace API key") },
                        placeholder = { Text(if (isNew) preset.keyHint else "Current: ${provider?.let(vm::maskedKey)}") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "Hide" else "Show")
                            }
                        },
                    )
                    Text(
                        "Your key stays encrypted on this phone and talks directly to ${if (customUrl) "this endpoint" else preset.name}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val setupGuide = when (preset.id) {
                        "deepseek" -> "2-minute setup guide" to "https://belugachat.app/guides/deepseek"
                        "deploy" -> "Setup guide" to "https://belugachat.app/guides/deploy"
                        "mistral" -> "Setup guide" to "https://belugachat.app/guides/mistral"
                        else -> null
                    }
                    if (preset.keyUrl != null || setupGuide != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            preset.keyUrl?.let { url ->
                                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Get a ${preset.name} key")
                                }
                            }
                            setupGuide?.let { (label, guideUrl) ->
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guideUrl)))
                                        }
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(label)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { vm.saveProvider(providerId, preset.id, name, baseUrl, key.ifBlank { null }, verify = true) { key = ""; onSaved(it) } },
                            enabled = !busy && (key.isNotBlank() || !isNew || customUrl),
                        ) { Text(if (isNew) "Save & connect" else "Save") }
                        if (customUrl) {
                            OutlinedButton(
                                onClick = { vm.saveProvider(providerId, preset.id, name, baseUrl, key.ifBlank { null }, verify = false) { key = ""; onSaved(it) } },
                                enabled = !busy,
                            ) { Text("Save without checking") }
                        }
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (provider != null) {
                val models = allModels.filter { it.providerId == provider.id }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Models · ${models.count { it.enabled }} of ${models.size} on", Modifier.weight(1f))
                        IconButton(onClick = { vm.refreshModels(provider.id) }) { Icon(Icons.Outlined.Refresh, "Refresh models") }
                        IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add model by ID") }
                    }
                    Text(
                        "Switched-on models appear in the model picker.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    if (models.size > 8) {
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("Filter models") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            singleLine = true, shape = RoundedCornerShape(14.dp),
                        )
                    }
                    val visible = models.filter { query.isBlank() || it.modelId.contains(query, true) || it.displayName.contains(query, true) }
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { vm.setAllModels(visible, true) }) { Text("All on") }
                        TextButton(onClick = { vm.setAllModels(visible, false) }) { Text("All off") }
                    }
                }
                val visible = models.filter { query.isBlank() || it.modelId.contains(query, true) || it.displayName.contains(query, true) }
                items(visible, key = { it.modelId }) { model ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                            if (model.displayName != model.modelId) Text(model.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = model.enabled, onCheckedChange = { vm.toggleModel(model, it) })
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (models.isEmpty()) {
                    item {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("No models listed. Tap ↻ to fetch them or + to add one by ID.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (deleting && provider != null) {
        ConfirmDialog("Delete ${provider.name}?", "Its key and model list will be removed. Chats are kept.", "Delete", { vm.deleteProvider(provider.id, onBack) }, { deleting = false })
    }
    if (adding && provider != null) {
        TextFieldDialog("Add model", "", "Model ID", confirm = "Add", onConfirm = { vm.addModel(provider.id, it) }, onDismiss = { adding = false })
    }
}
