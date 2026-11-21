package com.belugachat.app.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belugachat.app.data.InstalledItemEntity
import com.belugachat.app.ext.MarketItem
import com.belugachat.app.ext.MarketSource
import com.belugachat.app.ext.Marketplace
import com.belugachat.app.ui.components.Card
import com.belugachat.app.ui.components.ConfirmDialog
import com.belugachat.app.ui.components.EmptyState
import com.belugachat.app.ui.components.SectionLabel
import com.belugachat.app.ui.components.SubScreen
import com.belugachat.app.ui.markdown.Markdown

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(vm: ManageViewModel, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var marketplaceFilter by rememberSaveable { mutableStateOf("") }
    var sources by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<InstalledItemEntity?>(null) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var headerValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val plugins by vm.plugins.collectAsState()
    val pluginErrors by vm.pluginErrors.collectAsState()
    val mcpItems by vm.mcpItems.collectAsState()
    val loading by vm.marketLoading.collectAsState()
    val installing by vm.installing.collectAsState()
    val installed by vm.installed.collectAsState()
    val settings by vm.settings.collectAsState()
    val detail by vm.detailItem.collectAsState()
    val detailLoading by vm.detailLoading.collectAsState()
    val snackbar = rememberNotices(vm)

    LaunchedEffect(tab) {
        if (tab == 0) vm.loadPlugins()
    }
    LaunchedEffect(query, tab) {
        if (tab == 1) {
            kotlinx.coroutines.delay(400)
            vm.searchMcp(query)
        }
    }
    LaunchedEffect(detail?.lockId) {
        val loaded = detail ?: return@LaunchedEffect
        val defaults = loaded.headers.mapNotNull { spec ->
            spec.value?.takeIf { !it.contains('{') && !it.contains('$') }?.let { spec.name to it }
        }.toMap()
        headerValues = defaults + headerValues.filterValues { it.isNotBlank() }
    }

    fun switchTab(index: Int) {
        tab = index
        query = ""
        marketplaceFilter = ""
        expandedKey = null
        headerValues = emptyMap()
        vm.closeDetail()
    }

    fun toggle(item: MarketItem) {
        if (expandedKey == item.lockId) {
            expandedKey = null
            headerValues = emptyMap()
            vm.closeDetail()
        } else {
            expandedKey = item.lockId
            headerValues = emptyMap()
            vm.openDetail(item)
        }
    }

    val installedIds = installed.map { it.id }.toSet()
    val shown = (if (tab == 0) plugins else mcpItems).orEmpty()
    val marketplaces = shown.mapNotNull { it.marketplace.takeIf { value -> value.isNotBlank() } }.distinct().sorted()
    val filtered = shown.filter { item ->
        (query.isBlank() || listOfNotNull(item.name, item.description, item.author, item.category, item.marketplace)
            .any { it.contains(query, true) }) && (marketplaceFilter.isBlank() || item.marketplace == marketplaceFilter)
    }

    SubScreen("Marketplace", onBack, snackbar, actions = {
        if (tab == 0) IconButton(onClick = { sources = true }) { Icon(Icons.Outlined.Settings, "Marketplace sources") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { switchTab(0) }, text = { Text("Plugins") })
                Tab(tab == 1, { switchTab(1) }, text = { Text("MCP servers") })
                Tab(tab == 2, { switchTab(2) }, text = { Text("Installed (${installed.size})") })
            }
            if (tab != 2) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    placeholder = { Text(if (tab == 0) "Search plugins and authors" else "Search the MCP registry") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") } },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                )
                if (marketplaces.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        item { FilterChip(marketplaceFilter.isBlank(), { marketplaceFilter = "" }, label = { Text("All marketplaces") }) }
                        items(marketplaces) { value ->
                            FilterChip(
                                marketplaceFilter == value,
                                { marketplaceFilter = if (marketplaceFilter == value) "" else value },
                                label = { Text(value) },
                            )
                        }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (pluginErrors.isNotEmpty()) {
                        item {
                            Text(
                                "Some marketplace sources couldn't be loaded. ${pluginErrors.first()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (plugins != null && filtered.isEmpty()) item { Text("Nothing found.", Modifier.padding(20.dp)) }
                    items(filtered, key = { it.lockId }) { item ->
                        val expanded = expandedKey == item.lockId
                        ItemCard(
                            item = item,
                            expanded = expanded,
                            detail = if (expanded) detail else null,
                            detailLoading = expanded && detailLoading,
                            installed = item.lockId in installedIds,
                            installing = installing == item.marketplace + item.id,
                            headerValues = headerValues,
                            onHeaderChange = { name, value -> headerValues = headerValues + (name to value) },
                            onToggle = { toggle(item) },
                            onInstall = {
                                vm.quickInstallPlugin(item) {
                                    expandedKey = item.lockId
                                    headerValues = emptyMap()
                                }
                            },
                            onInstallWithHeaders = { values ->
                                vm.install(item, values) {
                                    expandedKey = null
                                    headerValues = emptyMap()
                                    vm.closeDetail()
                                }
                            },
                        )
                    }
                }
                1 -> {
                    val featuredFiltered = Marketplace.featuredMcpServers.filter { item ->
                        query.isBlank() || listOfNotNull(item.name, item.description, item.author, item.category, item.id)
                            .any { it.contains(query, true) }
                    }
                    val featuredIds = featuredFiltered.map { it.lockId }.toSet()
                    val registryItems = filtered.filter { it.lockId !in featuredIds }

                    fun androidx.compose.foundation.lazy.LazyListScope.mcpCards(list: List<MarketItem>) {
                        items(list, key = { it.lockId }) { item ->
                            val expanded = expandedKey == item.lockId
                            ItemCard(
                                item = item,
                                expanded = expanded,
                                detail = if (expanded) detail else null,
                                detailLoading = expanded && detailLoading,
                                installed = item.lockId in installedIds,
                                installing = installing == item.marketplace + item.id,
                                headerValues = headerValues,
                                onHeaderChange = { name, value -> headerValues = headerValues + (name to value) },
                                onToggle = { toggle(item) },
                                onInstall = { if (item.needsSignIn) toggle(item) else vm.install(item) },
                                onInstallWithHeaders = { values ->
                                    vm.install(item, values) {
                                        expandedKey = null
                                        headerValues = emptyMap()
                                        vm.closeDetail()
                                    }
                                },
                            )
                        }
                    }

                    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        item {
                            Text(
                                "From the official MCP Registry. Only servers with a remote HTTPS endpoint can run on a phone. " +
                                    "Servers that ask for tokens show a sign-in form before install.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                        if (featuredFiltered.isNotEmpty()) {
                            item { SectionLabel("Recommended · Free, no key needed") }
                            mcpCards(featuredFiltered)
                        }
                        if (registryItems.isNotEmpty()) {
                            item { SectionLabel("MCP Registry") }
                            mcpCards(registryItems)
                        }
                        if (featuredFiltered.isEmpty() && mcpItems != null && registryItems.isEmpty()) {
                            item { Text("Nothing found.", Modifier.padding(20.dp)) }
                        }
                    }
                }
                else -> {
                    if (installed.isEmpty()) {
                        EmptyState(Icons.Outlined.Storefront, "Nothing installed", "Skills, plugins and MCP servers you install appear here.")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(installed, key = { it.id }) { item ->
                                Card(Modifier.padding(bottom = 8.dp)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            (if (item.source == MarketSource.MCP) "MCP Registry" else item.marketplace) + (item.version?.let { " · v$it" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                            if (item.source == MarketSource.PLUGINS) {
                                                OutlinedButton(onClick = {
                                                    val match = vm.plugins.value?.firstOrNull { it.lockId == item.id }
                                                    if (match != null) vm.install(match) else { vm.loadPlugins(); vm.notify("Loading catalog… try again in a moment.") }
                                                }) { Text("Update") }
                                            }
                                            TextButton(onClick = { removing = item }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (sources) {
        var repo by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { sources = false },
            title = { Text("Marketplace sources") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GitHub repos with a .claude-plugin/marketplace.json file.", style = MaterialTheme.typography.bodySmall)
                    settings.marketplaces.sorted().forEach { source ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { vm.removeMarketplace(source) }) { Icon(Icons.Outlined.Close, "Remove") }
                        }
                    }
                    OutlinedTextField(repo, { repo = it }, label = { Text("owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.addMarketplace(repo); repo = "" }, enabled = repo.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { sources = false }) { Text("Done") } },
        )
    }

    removing?.let { item ->
        ConfirmDialog("Remove ${item.name}?", "Its skills and MCP servers will be removed.", "Remove", { vm.uninstall(item.id) }, { removing = null })
    }
}

@Composable
private fun ItemCard(
    item: MarketItem,
    expanded: Boolean,
    detail: MarketItem?,
    detailLoading: Boolean,
    installed: Boolean,
    installing: Boolean,
    headerValues: Map<String, String>,
    onHeaderChange: (String, String) -> Unit,
    onToggle: () -> Unit,
    onInstall: () -> Unit,
    onInstallWithHeaders: (Map<String, String>) -> Unit,
) {
    Card(Modifier.padding(bottom = 12.dp)) {
        Column {
            Column(
                Modifier
                    .clickable(onClick = onToggle)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(item.trust, item.author, item.version?.let { "v$it" }, item.category).joinToString(" · ")
                Text(
                    meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (item.description.isNotBlank()) {
                    Text(
                        item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val summary = componentSummary(item.components)
                if (summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                item.note?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        if (expanded) "Hide details" else "Show details",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Outlined.ExpandMore, null,
                        Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!expanded) {
                        when {
                            installing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            installed -> Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    "Installed",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                            item.installable -> InstallButton(
                                label = if (item.needsSignIn) "Sign in" else "Install",
                                installing = false,
                                enabled = true,
                                onClick = onInstall,
                            )
                        }
                    }
                }
            }
            if (expanded) {
                HorizontalDivider()
                InlineDetails(
                    item = detail ?: item,
                    loading = detailLoading,
                    installed = installed,
                    installing = installing,
                    values = headerValues,
                    onHeaderChange = onHeaderChange,
                    onInstall = { onInstallWithHeaders(headerValues) },
                )
            }
        }
    }
}

@Composable
private fun InstallButton(
    label: String,
    installing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !installing,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        if (installing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun InlineDetails(
    item: MarketItem,
    loading: Boolean,
    installed: Boolean,
    installing: Boolean,
    values: Map<String, String>,
    onHeaderChange: (String, String) -> Unit,
    onInstall: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var error by remember(item.lockId) { mutableStateOf<String?>(null) }
    val missing = item.headers.filter { it.required && values[it.name].isNullOrBlank() }
    fun open(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { uriHandler.openUri(url) }.onFailure { error = "Could not open that link." }
    }

    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            item.description.ifBlank { "No description provided." },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!item.installable || item.note != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    item.note ?: "Unavailable on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What gets installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    componentSummary(item.components).ifEmpty { "No components listed yet." },
                    style = MaterialTheme.typography.bodyMedium,
                )
                item.remoteUrl?.let {
                    Text("Remote endpoint", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (item.homepage != null || item.repository != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Links", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                item.homepage?.let { LinkText(it, ::open) }
                item.repository?.takeIf { it != item.homepage }?.let { LinkText(it, ::open) }
            }
        }
        if (item.headers.isNotEmpty()) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (item.needsSignIn) "Sign in to install" else "Connection headers", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "These values are stored encrypted on this phone and are only sent to this server.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.headers.forEach { spec ->
                OutlinedTextField(
                    values[spec.name].orEmpty(),
                    { value -> onHeaderChange(spec.name, value); error = null },
                    label = { Text(spec.name + if (spec.required) " *" else "") },
                    supportingText = { if (spec.description.isNotBlank()) Text(spec.description) },
                    visualTransformation = if (spec.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (spec.secret) KeyboardType.Password else KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (missing.isNotEmpty()) {
            Text(
                "Fill in: ${missing.joinToString { it.name }}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (installed) {
                Text("Installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
            }
            InstallButton(
                label = if (installed) "Reinstall" else "Install",
                installing = installing,
                enabled = item.installable && !loading && missing.isEmpty(),
                onClick = onInstall,
            )
        }
        if (loading) {
            Text("Loading README…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!item.readme.isNullOrBlank()) {
            HorizontalDivider()
            Text("README", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Markdown(item.readme)
        }
    }
}

@Composable
private fun LinkText(url: String, onOpen: (String) -> Unit) {
    Text(
        url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { onOpen(url) },
    )
}

private fun componentSummary(components: Map<String, Int>): String {
    val labels = mapOf(
        "skills" to "skill",
        "mcp_servers" to "MCP server",
        "remotes" to "remote",
        "packages" to "package",
        "commands" to "command",
        "agents" to "agent",
    )
    return components.entries
        .filter { it.value > 0 }
        .joinToString(" · ") { (key, value) -> "$value ${labels[key] ?: key}${if (value == 1) "" else "s"}" }
}
