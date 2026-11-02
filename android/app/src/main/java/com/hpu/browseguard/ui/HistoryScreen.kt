package com.hpu.browseguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.defaultHistoryTitle
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.ui.components.ConfirmDialog
import com.hpu.browseguard.ui.components.StateView
import com.hpu.browseguard.ui.components.StateViewKind
import com.hpu.browseguard.ui.theme.BgvTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryRoute(
    container: AppContainer,
    onRetry: (String) -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container.history)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        sandboxRoot = container.localSandboxRoot,
        onDelete = viewModel::delete,
        onRetry = onRetry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    sandboxRoot: String?,
    onDelete: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var openProblem by remember { mutableStateOf<OpenFileProblem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.history_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            state.groups.isEmpty() && state.loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            state.groups.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StateView(
                    kind = StateViewKind.Empty,
                    title = stringResource(R.string.history_empty_title),
                    description = stringResource(R.string.history_empty_body),
                    icon = Icons.AutoMirrored.Filled.List,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "swipe-hint") {
                    Text(
                        text = stringResource(R.string.history_swipe_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                state.groups.forEach { group ->
                    item(key = "group-${group.label}") {
                        Text(
                            text = stringResource(group.label.titleRes()),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(group.records, key = { it.id }) { record ->
                        SwipeDeleteRow(
                            onClick = { selected = record },
                            onLongClick = { selected = record },
                            onRequestDelete = { pendingDelete = record },
                        ) {
                            HistoryCard(record)
                        }
                    }
                }
            }
        }
    }

    selected?.let { record ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            RecordActions(
                record = record,
                sandboxRoot = sandboxRoot,
                onOpen = {
                    record.outputPath?.let { path -> openLocalFile(context, path)?.let { openProblem = it } }
                    selected = null
                },
                onCopy = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("link", record.url))
                    Toast.makeText(context, R.string.history_copy_done, Toast.LENGTH_SHORT).show()
                    selected = null
                },
                onRetry = {
                    onRetry(record.url)
                    selected = null
                },
                onDelete = {
                    pendingDelete = record
                    selected = null
                },
            )
        }
    }

    pendingDelete?.let { record ->
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            message = stringResource(R.string.history_delete_message),
            confirmLabel = stringResource(R.string.history_delete_confirm),
            dismissLabel = stringResource(R.string.history_cancel),
            destructive = true,
            onConfirm = {
                onDelete(record.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    openProblem?.let { problem ->
        AlertDialog(
            onDismissRequest = { openProblem = null },
            title = { Text(stringResource(R.string.history_open_file)) },
            text = { Text(openProblemText(problem)) },
            confirmButton = {
                TextButton(onClick = { openProblem = null }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteRow(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRequestDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        // 只「请求」删除：真正的删除要过二次确认，所以这里永远不让行真的滑走。
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onRequestDelete()
            false
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.history_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            content()
        }
    }
}

@Composable
private fun HistoryCard(record: DownloadRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.title ?: defaultHistoryTitle(record.url),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(record.status)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = record.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = detailLine(record),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeLabel(record.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: RecordStatus) {
    val color = when (status) {
        RecordStatus.DONE -> Color(0xFF16A34A)
        RecordStatus.FAILED -> MaterialTheme.colorScheme.error
        RecordStatus.INTERRUPTED, RecordStatus.SNIFFING -> MaterialTheme.colorScheme.tertiary
        RecordStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
        RecordStatus.READY -> MaterialTheme.colorScheme.secondary
        RecordStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun RecordActions(
    record: DownloadRecord,
    sandboxRoot: String?,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val localFile = isLocalFile(record.outputPath, sandboxRoot)
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.history_actions_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = record.title ?: defaultHistoryTitle(record.url),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = record.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            record.errorCode?.let { code ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.history_error_code, code),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (record.status) {
            RecordStatus.DONE -> if (localFile) {
                ActionRow(stringResource(R.string.history_open_file), Icons.Filled.PlayArrow, onClick = onOpen)
            } else {
                Text(
                    text = stringResource(R.string.history_pc_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }

            RecordStatus.READY,
            RecordStatus.FAILED,
            RecordStatus.INTERRUPTED,
            RecordStatus.CANCELLED,
            -> ActionRow(stringResource(R.string.history_retry), Icons.Filled.Refresh, onClick = onRetry)

            RecordStatus.SNIFFING,
            RecordStatus.DOWNLOADING,
            -> Unit
        }

        ActionRow(stringResource(R.string.history_copy_link), Icons.Filled.Share, onClick = onCopy)
        ActionRow(
            label = stringResource(R.string.history_delete),
            icon = Icons.Filled.Delete,
            destructive = true,
            onClick = onDelete,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(14.dp))
            Text(text = label, color = color, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun detailLine(record: DownloadRecord): String {
    val error = record.errorCode
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.history_error_code, it) }
    return listOfNotNull(record.format?.takeIf { it.isNotBlank() }, error).joinToString(" · ")
}

private fun HistoryGroupLabel.titleRes(): Int = when (this) {
    HistoryGroupLabel.TODAY -> R.string.history_group_today
    HistoryGroupLabel.YESTERDAY -> R.string.history_group_yesterday
    HistoryGroupLabel.EARLIER -> R.string.history_group_earlier
}

private fun RecordStatus.labelRes(): Int = when (this) {
    RecordStatus.SNIFFING -> R.string.history_status_sniffing
    RecordStatus.READY -> R.string.history_status_ready
    RecordStatus.DOWNLOADING -> R.string.history_status_downloading
    RecordStatus.DONE -> R.string.history_status_done
    RecordStatus.FAILED -> R.string.history_status_failed
    RecordStatus.CANCELLED -> R.string.history_status_cancelled
    RecordStatus.INTERRUPTED -> R.string.history_status_interrupted
}

/** 只有落在应用沙箱内的路径才允许「打开文件」；引擎侧路径一律只给提示。 */
private fun isLocalFile(path: String?, sandboxRoot: String?): Boolean {
    if (path == null || sandboxRoot == null) return false
    return try {
        val root = File(sandboxRoot).canonicalPath
        val file = File(path).canonicalPath
        file.startsWith(root + File.separator) && File(file).isFile
    } catch (e: Exception) {
        false
    }
}

private val TIME_TODAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val TIME_EARLIER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

private fun timeLabel(updatedAt: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(updatedAt).atZone(zone)
    return time.format(if (time.toLocalDate() == LocalDate.now(zone)) TIME_TODAY else TIME_EARLIER)
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F8FA)
@Composable
private fun HistoryEmptyPreview() {
    BgvTheme {
        HistoryScreen(
            state = HistoryUiState(loading = false),
            sandboxRoot = null,
            onDelete = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 900)
@Composable
private fun HistoryListPreview() {
    val now = System.currentTimeMillis()
    val records = listOf(
        previewRecord("a", "https://www.youtube.com/watch?v=abc123", RecordStatus.DONE, now, "720p · mp4"),
        previewRecord("b", "https://cdn.example.com/clip.mp4", RecordStatus.FAILED, now - 60_000, "1080p · mp4", ErrorCode.NETWORK_TIMEOUT.wireCode()),
        previewRecord("c", "https://example.com/stream.m3u8", RecordStatus.INTERRUPTED, now - 3_600_000, null),
        previewRecord("d", "https://example.com/ready.mp4", RecordStatus.READY, now - 86_400_000, null),
    )
    BgvTheme {
        HistoryScreen(
            state = HistoryUiState(
                loading = false,
                groups = groupHistory(records, today = LocalDate.now(), zone = ZoneId.systemDefault()),
            ),
            sandboxRoot = "/data/user/0/com.hpu.browseguard/files/Download",
            onDelete = {},
            onRetry = {},
        )
    }
}

private fun previewRecord(
    id: String,
    url: String,
    status: RecordStatus,
    updatedAt: Long,
    format: String?,
    errorCode: String? = null,
): DownloadRecord = DownloadRecord(
    id = id,
    taskId = "task-$id",
    url = url,
    title = defaultHistoryTitle(url),
    format = format,
    status = status,
    outputPath = null,
    errorCode = errorCode,
    createdAt = updatedAt - 30_000,
    updatedAt = updatedAt,
)
