package com.belugachat.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belugachat.app.data.Reasoning

private val reasoningLabels = mapOf(
    Reasoning.DEFAULT to "Auto", Reasoning.LOW to "Low", Reasoning.MEDIUM to "Medium", Reasoning.HIGH to "High", Reasoning.MAX to "Max",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<ModelChoice>,
    selected: ModelChoice?,
    reasoning: String,
    onSelect: (ModelChoice) -> Unit,
    onReasoning: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text("Reasoning effort", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Reasoning.all.forEach { level ->
                    FilterChip(selected = reasoning == level, onClick = { onReasoning(level) }, label = { Text(reasoningLabels.getValue(level)) })
                }
            }
            Text(
                "Auto lets the model decide. Higher effort is slower and uses more tokens.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search models") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            val filtered = models.filter {
                query.isBlank() || it.model.modelId.contains(query, true) || it.model.displayName.contains(query, true) || it.provider.name.contains(query, true)
            }
            LazyColumn(Modifier.weight(1f)) {
                if (models.isEmpty()) {
                    item {
                        Text(
                            "No models yet. Add a provider and API key, then switch on the models you want to use.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                filtered.groupBy { it.provider }.forEach { (provider, items) ->
                    item(key = "h-" + provider.id) {
                        Text(provider.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(items, key = { it.provider.id + "/" + it.model.modelId }) { choice ->
                        val isSelected = selected?.provider?.id == choice.provider.id && selected.model.modelId == choice.model.modelId
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(choice); onDismiss() }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(choice.model.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (choice.model.displayName != choice.model.modelId) {
                                    Text(choice.model.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (isSelected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { onDismiss(); onManage() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Icon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Providers, keys and models")
            }
        }
    }
}
