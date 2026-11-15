package com.nativefinds.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nativefinds.app.AppContainer
import com.nativefinds.app.alerts.AlertNotifier
import com.nativefinds.app.alerts.SightingAlertWorker
import com.nativefinds.app.alerts.SightingAlerts
import com.nativefinds.app.backup.BackupManager
import com.nativefinds.app.backup.Dropbox
import com.nativefinds.app.ui.common.formatDate
import kotlinx.coroutines.launch

/** Dropbox backup: sign in with the user's own Dropbox app, back up now or daily, restore on a new phone. */
@Composable
fun DropboxSection(container: AppContainer) {
    val saved by container.settings.settings.collectAsState(initial = null)
    val s = saved ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var appKey by remember(s.dropboxAppKey) { mutableStateOf(s.dropboxAppKey) }
    var code by remember { mutableStateOf("") }
    var waitingForCode by remember { mutableStateOf(container.state.dropboxVerifier != null && !s.dropboxConnected) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var lastBackup by remember { mutableStateOf(container.state.lastBackupAt) }

    Text("Dropbox backup", style = MaterialTheme.typography.titleSmall)
    if (!s.dropboxConnected) {
        Text(
            "Back up your finds, photos and sounds to your own Dropbox, and restore them on a new phone. " +
                "Paste the App key from your Dropbox app (dropbox.com/developers/apps), then sign in.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = appKey,
            onValueChange = { appKey = it.trim() },
            label = { Text("Dropbox App key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(enabled = appKey.isNotBlank(), onClick = {
            val verifier = Dropbox.newVerifier()
            container.state.dropboxVerifier = verifier
            scope.launch { container.settings.update { it.copy(dropboxAppKey = appKey) } }
            waitingForCode = true
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Dropbox.authorizeUrl(appKey, verifier)))
        }) { Text("Sign in to Dropbox") }
        if (waitingForCode) {
            Text(
                "Dropbox will ask you to allow access, then show a code. Copy it, come back here and paste it.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                label = { Text("Code from Dropbox") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = code.isNotBlank() && busy == null, onClick = {
                scope.launch {
                    busy = "Signing in…"; message = null
                    runCatching {
                        val verifier = container.state.dropboxVerifier ?: error("Tap Sign in to Dropbox again")
                        Dropbox.finishSignIn(appKey, code, verifier)
                    }.onSuccess { token ->
                        container.settings.update { it.copy(dropboxAppKey = appKey, dropboxRefreshToken = token) }
                        container.state.dropboxVerifier = null
                        BackupManager.schedule(ctx, true)
                        waitingForCode = false; code = ""
                        message = "Connected. Tap Back up now for your first backup."
                    }.onFailure { message = "Couldn't sign in: ${it.message}" }
                    busy = null
                }
            }) { Text("Finish signing in") }
        }
    } else {
        Text(
            if (lastBackup > 0) "Connected. Last backup ${formatDate(lastBackup)}." else "Connected. Not backed up yet.",
            style = MaterialTheme.typography.bodySmall,
        )
        container.state.lastBackupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Back up every day", style = MaterialTheme.typography.bodyMedium)
                Text("On Wi-Fi, when the battery isn't low. Each photo is only uploaded once.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.autoBackup, onCheckedChange = { on ->
                scope.launch { container.settings.update { it.copy(autoBackup = on) } }
                BackupManager.schedule(ctx, on)
            })
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = busy == null, onClick = {
                scope.launch {
                    busy = "Backing up…"; message = null; progress = 0f
                    runCatching {
                        container.backup.backup { done, total -> progress = if (total > 0) done.toFloat() / total else 1f }
                    }.onSuccess { r ->
                        lastBackup = container.state.lastBackupAt
                        message = "Backed up. ${r.photosUploaded} new photos and sounds uploaded (${r.photosTotal} in total)."
                    }.onFailure { message = "Backup failed: ${it.message}" }
                    busy = null; progress = null
                }
            }) { Text("Back up now") }
            OutlinedButton(enabled = busy == null, onClick = { confirmRestore = true }) { Text("Restore") }
            TextButton(enabled = busy == null, onClick = {
                scope.launch { container.settings.update { it.copy(dropboxRefreshToken = "") } }
                BackupManager.schedule(ctx, false)
            }) { Text("Disconnect") }
        }
    }
    busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore from Dropbox?") },
            text = {
                Text(
                    "This replaces the finds, places and counts on this phone with your last Dropbox backup, " +
                        "downloads the photos and sounds, then restarts the app. Use it on a new phone. " +
                        "Your settings here stay as they are.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    scope.launch {
                        busy = "Restoring…"; message = null
                        runCatching { container.backup.restore { busy = it } }
                            .onFailure { message = "Restore failed: ${it.message}" }
                        busy = null
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

/** Notifications for unusual species seen nearby that you haven't found yet. */
@Composable
fun AlertsSection(container: AppContainer) {
    val saved by container.settings.settings.collectAsState(initial = null)
    val s = saved ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun enable(on: Boolean) {
        scope.launch { container.settings.update { it.copy(alertsEnabled = on) } }
        SightingAlertWorker.schedule(ctx, on)
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enable(granted)
        if (!granted) result = "Alerts need permission to show notifications."
    }

    Text("Unusual species alerts", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "A notification when something uncommon is seen near your places (or where you last used the app) " +
                "that you haven't found yet. Everyday species, introduced species and pests are left out. " +
                "Checks twice a day; each species is only mentioned once a month.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = s.alertsEnabled, onCheckedChange = { on ->
            if (on && Build.VERSION.SDK_INT >= 33 && !AlertNotifier.canNotify(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            else enable(on)
        })
    }
    if (s.alertsEnabled) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 5, 10, 25).forEach { km ->
                FilterChip(
                    selected = s.alertRadiusKm == km,
                    onClick = { scope.launch { container.settings.update { it.copy(alertRadiusKm = km) } } },
                    label = { Text("Within $km km") },
                )
            }
        }
        OutlinedButton(enabled = !checking && !s.offlineMode, onClick = {
            scope.launch {
                checking = true; result = null
                result = runCatching { SightingAlerts(container).check() }.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) "Nothing unusual seen nearby in the last 7 days. (Needs your region pack, and a place or a recent location.)"
                        else "Seen in the last 7 days:\n" + list.take(12).joinToString("\n") { "• ${it.name}, %.1f km from %s".format(it.distanceKm, it.near) }
                    },
                    onFailure = { "Couldn't check: ${it.message}" },
                )
                checking = false
            }
        }) { Text(if (checking) "Checking…" else "Check now") }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
