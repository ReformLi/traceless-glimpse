package com.hpu.browseguard.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hpu.browseguard.R
import com.hpu.browseguard.data.history.ArtifactPresence
import com.hpu.browseguard.data.history.DeleteChoice
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordLocation
import com.hpu.browseguard.data.history.defaultHistoryTitle
import com.hpu.browseguard.data.history.locationOf
import com.hpu.browseguard.data.media.ArtifactSnapshot
import com.hpu.browseguard.data.output.FileNaming
import com.hpu.browseguard.ui.components.BgvInputField

/**
 * 下载管理页的文件操作意图。
 *
 * 界面只发意图，不碰文件系统：改名/移动要串行跑、要在成了之后才回填记录，
 * 这些编排全在 `HistoryViewModel` 里，界面这里多一个参数就少一处「点了没反应」的分歧。
 */
data class DownloadFileActions(
    val onToggleSelect: (String) -> Unit = { _ -> },
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onBatchDelete: (DeleteChoice) -> Unit = { _ -> },
    val onRename: (String, String) -> Unit = { _, _ -> },
    val onMoveToFolder: (String) -> Unit = {},
    val onMoveToAppFolder: () -> Unit = {},
    val onOpenDetails: (String) -> Unit = {},
    val onCloseDetails: () -> Unit = {},
    val onDropMissing: (String) -> Unit = {},
    val onReportShown: () -> Unit = {},
)

/** 多选状态顶栏：先说清「选了几个、能不能反选」，再让人决定要不要退出多选。 */
@Composable
fun DownloadSelectionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.download_select_count, selectedCount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) {
            Icon(imageVector = Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.download_select_all))
        }
        TextButton(onClick = onExit) {
            Text(stringResource(R.string.download_select_exit))
        }
    }
}

/** 多选状态底栏：删除、移动、重命名。重命名只在恰好选中一行时才摆出来。 */
@Composable
fun DownloadBatchBar(
    busy: Boolean,
    showRename: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (busy) {
                Text(
                    text = stringResource(R.string.download_batch_busy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onMove,
                    enabled = enabled && !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.download_batch_move))
                }
                if (showRename) {
                    Button(
                        onClick = onRename,
                        enabled = enabled && !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.download_batch_rename))
                    }
                }
                Button(
                    onClick = onDelete,
                    enabled = enabled && !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.download_batch_delete))
                }
            }
        }
    }
}

/**
 * 改名对话框：实时校验，不合法就不让提交。
 *
 * 校验规则来自 [FileNaming]——手机上放过一个 `:`，将来拷到电脑上就是个改不掉的文件。
 * 扩展名不在这里改：它跟着现在的文件走，改基本名由搬运工重新拼。
 */
@Composable
fun RenameDialog(
    record: DownloadRecord,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(baseNameOf(record)) }
    val invalid = FileNaming.invalidReason(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_rename_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                BgvInputField(
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.download_rename_label),
                    isError = invalid != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = invalid ?: stringResource(R.string.download_move_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (invalid != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = invalid == null && text.isNotBlank(),
            ) {
                Text(stringResource(R.string.download_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_cancel))
            }
        },
    )
}

/** 移动目标：现选一个 SAF 文件夹，或移回应用目录。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveTargetSheet(
    onDismiss: () -> Unit,
    onPickFolder: () -> Unit,
    onMoveToApp: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = stringResource(R.string.download_move_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.download_move_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OperationRow(
                label = stringResource(R.string.download_move_to_folder),
                icon = Icons.Filled.LocationOn,
                onClick = {
                    onDismiss()
                    onPickFolder()
                },
            )
            OperationRow(
                label = stringResource(R.string.download_move_to_app),
                icon = Icons.Filled.Home,
                onClick = {
                    onDismiss()
                    onMoveToApp()
                },
            )
        }
    }
}

/**
 * 详情面板：报此刻的文件属性，而不是记录里那个可能已经滞后的数字。
 *
 * 读不到就明说读不到。`GONE` / `REVOKED` 分开说话：前者给「删除记录」这条出口，
 * 后者要说的是重新授权——把授权失效说成文件没了，会让人白跑一趟文件管理器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(
    state: DetailsState,
    sandboxRoot: String?,
    onDismiss: () -> Unit,
    onOpen: (DownloadRecord) -> Unit,
    onRename: (DownloadRecord) -> Unit,
    onMove: (DownloadRecord) -> Unit,
    onDropMissing: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            when (state) {
                DetailsState.Hidden -> Unit

                is DetailsState.Loading -> Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Text(
                        text = state.record.title ?: defaultHistoryTitle(state.record.url),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.download_details_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is DetailsState.Shown -> DetailsContent(
                    snapshot = state.snapshot,
                    sandboxRoot = sandboxRoot,
                    onOpen = onOpen,
                    onRename = onRename,
                    onMove = onMove,
                    onDropMissing = onDropMissing,
                )
            }
        }
    }
}

@Composable
private fun DetailsContent(
    snapshot: ArtifactSnapshot,
    sandboxRoot: String?,
    onOpen: (DownloadRecord) -> Unit,
    onRename: (DownloadRecord) -> Unit,
    onMove: (DownloadRecord) -> Unit,
    onDropMissing: (String) -> Unit,
) {
    val record = snapshot.record
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            text = snapshot.fileName ?: record.title ?: defaultHistoryTitle(record.url),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        presenceLine(snapshot.presence)?.let { line ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        val size = snapshot.sizeBytes ?: record.sizeBytes
        FactRow(
            stringResource(R.string.download_details_location),
            stringResource(locationLabel(locationOf(record.outputPath, sandboxRoot))),
        )
        if (snapshot.presence == ArtifactPresence.PRESENT) {
            snapshot.fileName?.let { FactRow(stringResource(R.string.download_details_name), it) }
            size?.takeIf { it > 0L }?.let { FactRow(stringResource(R.string.download_details_size), formatBytes(it)) }
            durationLabel(snapshot.facts?.durationMs)?.let {
                FactRow(stringResource(R.string.download_details_duration), it)
            }
            resolutionLabel(snapshot.facts?.width, snapshot.facts?.height)?.let {
                FactRow(stringResource(R.string.download_details_resolution), it)
            }
            snapshot.unreadableReason?.let { reason ->
                Text(
                    text = stringResource(R.string.download_details_unreadable, reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            record.outputPath?.let { path ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    when (snapshot.presence) {
        ArtifactPresence.PRESENT -> DetailsActions(record, onOpen, onRename, onMove)

        // 记录还在、文件没了：这一层给的不是「打开」，而是把这条行清理掉的出口。
        ArtifactPresence.GONE -> OperationRow(
            label = stringResource(R.string.download_missing_drop),
            icon = Icons.Filled.Delete,
            destructive = true,
            onClick = { onDropMissing(record.id) },
        )

        ArtifactPresence.REVOKED,
        ArtifactPresence.NOT_ON_PHONE,
        -> Unit
    }
}

@Composable
private fun DetailsActions(
    record: DownloadRecord,
    onOpen: (DownloadRecord) -> Unit,
    onRename: (DownloadRecord) -> Unit,
    onMove: (DownloadRecord) -> Unit,
) {
    OperationRow(stringResource(R.string.history_open_file), Icons.Filled.PlayArrow) { onOpen(record) }
    OperationRow(stringResource(R.string.download_rename_title), Icons.Filled.Edit) { onRename(record) }
    OperationRow(stringResource(R.string.download_batch_move), Icons.Filled.LocationOn) { onMove(record) }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 存在性那一句话；`PRESENT` 不需要额外说明，返回 null 让界面整块省略。 */
@Composable
private fun presenceLine(presence: ArtifactPresence): String? = when (presence) {
    ArtifactPresence.PRESENT -> null
    ArtifactPresence.GONE -> stringResource(R.string.download_details_missing)
    ArtifactPresence.REVOKED -> stringResource(R.string.download_details_revoked)
    ArtifactPresence.NOT_ON_PHONE -> stringResource(R.string.download_details_not_on_phone)
}

private fun locationLabel(location: RecordLocation): Int = when (location) {
    RecordLocation.PRIVATE -> R.string.download_details_location_private
    RecordLocation.PUBLIC -> R.string.download_details_location_public
    RecordLocation.ENGINE -> R.string.download_details_location_engine
    RecordLocation.MISSING -> R.string.download_details_not_on_phone
}

/**
 * 一次批量操作的汇总。
 *
 * 失败原因照抄搬运工写的那句话：它已经足够具体（「目标文件夹已不可用」
 * 「没能删掉公开目录里的原文件」），在这儿换个说法只会把它磨平。
 */
fun operationReportLabel(context: Context, report: OperationReport): String {
    val head = when (report.verb) {
        OperationVerb.DELETE -> context.getString(R.string.download_report_deleted, report.succeeded)
        OperationVerb.MOVE -> context.getString(R.string.download_report_moved, report.succeeded)
        OperationVerb.RENAME -> context.getString(R.string.download_report_renamed)
    }
    val skipped = if (report.skipped > 0) {
        context.getString(R.string.download_report_skipped, report.skipped)
    } else {
        ""
    }
    val failures = if (report.failures.isEmpty()) "" else {
        context.getString(R.string.download_report_failed, report.failures.joinToString("；"))
    }
    return head + skipped + failures
}

/** 详情/操作共用的行：与记录操作面板同一套排版，但这里是文件操作。 */
@Composable
private fun OperationRow(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(14.dp))
            Text(text = label, color = color, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 改名框的初始值：优先用文件名，本地路径取最后一段，SAF 与没有产物的行退回记录标题。 */
internal fun baseNameOf(record: DownloadRecord): String {
    val path = record.outputPath
    if (path != null && !path.startsWith("content://")) {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        if (name.isNotBlank()) return FileNaming.baseOf(name)
    }
    return record.title.orEmpty().ifBlank { FileNaming.baseOf(path.orEmpty()) }
}
