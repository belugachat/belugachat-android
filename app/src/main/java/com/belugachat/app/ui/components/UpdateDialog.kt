package com.belugachat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.belugachat.app.ui.markdown.Markdown
import com.belugachat.app.update.UpdateRelease
import com.belugachat.app.update.UpdateState
import java.io.File

@Composable
fun UpdateDialog(
    state: UpdateState,
    canInstallPackages: () -> Boolean,
    onDownload: (UpdateRelease) -> Unit,
    onCancelDownload: () -> Unit,
    onRequestInstallPermission: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: (rememberVersion: Boolean) -> Unit,
) {
    when (state) {
        is UpdateState.Available -> {
            val release = state.release
            AlertDialog(
                onDismissRequest = { onDismiss(state.fromAutoCheck) },
                title = { Text("Beluga Chat ${release.version} is available") },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Markdown(release.notes)
                        if (release.apkSizeBytes > 0L) {
                            Text(
                                "APK size: ${formatMb(release.apkSizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onDownload(release) }) {
                        Text("Update now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss(state.fromAutoCheck) }) {
                        Text("Later")
                    }
                },
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Downloading v${state.release.version}") },
                text = {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.totalBytes > 0L) {
                            val progress = (state.bytesRead.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${formatMb(state.bytesRead)} / ${formatMb(state.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                formatMb(state.bytesRead),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onCancelDownload) {
                        Text("Cancel")
                    }
                },
            )
        }

        is UpdateState.ReadyToInstall -> {
            var allowed by remember(state.apkFile) { mutableStateOf(canInstallPackages()) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        allowed = canInstallPackages()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            AlertDialog(
                onDismissRequest = { onDismiss(false) },
                title = { Text("Ready to install v${state.release.version}") },
                text = {
                    Text(
                        if (allowed) {
                            "Beluga Chat ${state.release.version} is downloaded and ready to install."
                        } else {
                            "Android needs permission to install updates from Beluga Chat."
                        },
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (allowed) onInstall(state.apkFile) else onRequestInstallPermission()
                        },
                    ) {
                        Text(if (allowed) "Install" else "Allow installs")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss(false) }) {
                        Text("Later")
                    }
                },
            )
        }

        is UpdateState.Error -> {
            val release = state.release ?: return
            AlertDialog(
                onDismissRequest = { onDismiss(false) },
                title = { Text("Update download failed") },
                text = { Text(state.message) },
                confirmButton = {
                    Button(onClick = { onDownload(release) }) {
                        Text("Retry")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss(false) }) {
                        Text("Close")
                    }
                },
            )
        }

        UpdateState.Idle,
        UpdateState.Checking,
        UpdateState.UpToDate,
        -> Unit
    }
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))
