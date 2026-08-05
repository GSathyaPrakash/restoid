package io.github.hddq.restoid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hddq.restoid.R
import io.github.hddq.restoid.data.PerAppItemKind
import io.github.hddq.restoid.data.ResticState
import io.github.hddq.restoid.ui.home.PerAppHomeUiState
import io.github.hddq.restoid.ui.home.PerAppItemSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppHomeScreen(
    uiState: PerAppHomeUiState,
    onRefresh: () -> Unit,
    onDeleteHistory: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onRestore: (String) -> Unit,
    onSelectSnapshot: (String) -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.resticState !is ResticState.Installed -> {
                        Text(
                            stringResource(R.string.restic_not_available_check_settings),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    !uiState.isRepoReady -> {
                        Text(
                            stringResource(R.string.no_repository_selected),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    uiState.error != null -> {
                        Text(
                            stringResource(R.string.error_with_message, uiState.error ?: ""),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    uiState.isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    uiState.items.isEmpty() -> {
                        Text(
                            stringResource(R.string.per_app_home_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            uiState.items.forEach { item ->
                                PerAppItemRow(
                                    item = item,
                                    onDelete = { onDeleteHistory(item.descriptor.slug) },
                                    onRestore = { onRestore(item.descriptor.slug) }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                            }
                            Spacer(Modifier.height(88.dp))
                        }
                    }
                }
            }
        }
    }

    // Delete-history confirmation
    val pendingDelete = uiState.pendingDeleteSlug
    if (pendingDelete != null) {
        val name = uiState.items.find { it.descriptor.slug == pendingDelete }?.descriptor?.displayName
            ?: pendingDelete
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.per_app_delete_confirm_title)) },
            text = { Text(stringResource(R.string.per_app_delete_confirm_message, name)) },
            confirmButton = {
                Button(onClick = onConfirmDelete, enabled = !uiState.isDeleting) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Restore snapshot picker
    if (uiState.pendingRestoreSlug != null) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onCancelRestore,
            title = { Text(stringResource(R.string.per_app_restore_title)) },
            text = {
                Column {
                    when {
                        uiState.isRestoreLoading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(12.dp))
                                Text(stringResource(R.string.per_app_restore_loading))
                            }
                        }
                        uiState.restoreError != null -> {
                            Text(
                                stringResource(R.string.error_with_message, uiState.restoreError ?: ""),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        uiState.restoreSnapshots.isEmpty() -> {
                            Text(stringResource(R.string.per_app_restore_no_snapshots))
                        }
                        else -> {
                            Text(
                                stringResource(R.string.per_app_restore_pick_version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            uiState.restoreSnapshots.forEach { snapshot ->
                                val label = remember(snapshot.time, context) {
                                    relativeTime(snapshot.time, context) + "  (${snapshot.id.take(8)})"
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = snapshot.id == uiState.selectedSnapshotId,
                                            onClick = { onSelectSnapshot(snapshot.id) },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = snapshot.id == uiState.selectedSnapshotId,
                                        onClick = null
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmRestore,
                    enabled = !uiState.isRestoreLoading &&
                        uiState.restoreError == null &&
                        uiState.selectedSnapshotId != null
                ) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRestore) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun PerAppItemRow(
    item: PerAppItemSummary,
    onDelete: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val descriptor = item.descriptor
    val isApp = descriptor.kind == PerAppItemKind.APP
    val timeText = remember(item.lastBackupTime, context) {
        relativeTime(item.lastBackupTime, context)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isApp) Icons.Default.Apps else Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp).padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = descriptor.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = stringResource(if (isApp) R.string.per_app_item_app else R.string.per_app_item_directory),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val sub = if (item.snapshotCount > 0) {
                "${pluralStringResource(R.plurals.snapshots_count, item.snapshotCount, item.snapshotCount)} · $timeText"
            } else {
                stringResource(R.string.per_app_no_backup_history)
            }
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isApp && item.snapshotCount > 0) {
            OutlinedButton(onClick = onRestore, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.action_restore))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.per_app_action_delete_history)
            )
        }
    }
}

private fun relativeTime(isoTime: String?, context: android.content.Context): String {
    if (isoTime.isNullOrBlank()) return context.getString(R.string.per_app_no_backup_history)
    return try {
        val instant = java.time.Instant.parse(isoTime)
        val duration = java.time.Duration.between(instant, java.time.Instant.now())
        when {
            duration.toMinutes() < 1 -> context.getString(R.string.time_just_now)
            duration.toHours() < 1 -> {
                val mins = duration.toMinutes().toInt()
                context.resources.getQuantityString(R.plurals.time_mins_ago, mins, mins)
            }
            duration.toDays() < 1 -> {
                val hours = duration.toHours().toInt()
                context.resources.getQuantityString(R.plurals.time_hours_ago, hours, hours)
            }
            duration.toDays() < 30 -> {
                val days = duration.toDays().toInt()
                context.resources.getQuantityString(R.plurals.time_days_ago, days, days)
            }
            duration.toDays() < 365 -> {
                val months = (duration.toDays() / 30).toInt()
                context.resources.getQuantityString(R.plurals.time_months_ago, months, months)
            }
            else -> {
                val years = (duration.toDays() / 365).toInt()
                context.resources.getQuantityString(R.plurals.time_years_ago, years, years)
            }
        }
    } catch (_: Exception) {
        isoTime.take(10)
    }
}
