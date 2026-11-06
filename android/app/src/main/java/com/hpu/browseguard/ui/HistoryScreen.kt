package com.hpu.browseguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.hpu.browseguard.data.history.DeleteChoice
import com.hpu.browseguard.data.history.DeletionPolicy
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordNotes
import com.hpu.browseguard.data.history.RecordLocation
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.defaultHistoryTitle
import com.hpu.browseguard.data.history.locationOf
import com.hpu.browseguard.data.history.sourceHost
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.ui.components.ConfirmDialog
import com.hpu.browseguard.ui.components.StateView
import com.hpu.browseguard.ui.components.StateViewKind
import com.hpu.browseguard.ui.theme.BgvTheme
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryRoute(container: AppContainer, onRetry: (String) -> Unit, onPlay: (String) -> Unit) {
    val viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(
            container.history,
            container.downloads,
            container.fileMover,
            container.mediaProbe,
            container.localSandboxRoot,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        report = report,
        sandboxRoot = container.localSandboxRoot,
        onSetFilter = viewModel::setFilter,
        onDelete = viewModel::delete,
        onRetry = onRetry,
        onCancel = viewModel::cancel,
        actions = DownloadFileActions(
            onToggleSelect = viewModel::toggleSelect,
            onSelectAll = viewModel::selectAllShown,
            onClearSelection = viewModel::clearSelection,
            onBatchDelete = viewModel::deleteSelected,
            onRename = viewModel::rename,
            onMoveToFolder = viewModel::moveToFolder,
            onMoveToAppFolder = viewModel::moveToAppFolder,
            onOpenDetails = viewModel::openDetails,
            onCloseDetails = viewModel::closeDetails,
            onDropMissing = viewModel::dropMissingRecord,
            onReportShown = viewModel::consumeReport,
        ),
        onPlay = onPlay,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    sandboxRoot: String?,
    onSetFilter: (DownloadFilter) -> Unit,
    onDelete: (String, DeleteChoice) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    actions: DownloadFileActions = DownloadFileActions(),
    report: OperationReport? = null,
    onPlay: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingCancel by remember { mutableStateOf<DownloadRecord?>(null) }
    var openProblem by remember { mutableStateOf<OpenFileProblem?>(null) }

    // 外部删除对账：「打开」撞到文件没了，光说「打不开」没有出口，所以这里要能直接重下或删行。
    var missingFile by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingRename by remember { mutableStateOf<DownloadRecord?>(null) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var pendingBatchDelete by remember { mutableStateOf<List<DownloadRecord>?>(null) }

    val shown = state.groups.flatMap { it.records }
    val selection = state.selection
    val selecting = selection.isNotEmpty()
    val selectedRecords = shown.filter { it.id in selection }

    // 系统文件夹选择器：URI 的持久授权由 ViewModel 在搬运前抓牢，界面不碰文件系统。
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { actions.onMoveToFolder(it.toString()) } }

    /**
     * 从单行发起移动：先把这一行选中，再弹目标表。
     *
     * 移动只有一个入口（批量），单行只是「选中一项的批量」——两条代码路径迟早会
     * 在「同名追加 (1)」「失败保留原文件」这些细节上走偏。
     */
    fun moveSingle(record: DownloadRecord) {
        actions.onClearSelection()
        actions.onToggleSelect(record.id)
        actions.onCloseDetails()
        selected = null
        showMoveSheet = true
    }

    /**
     * 打开产物：手机侧的成品交给内置播放器（全屏 + 断点续播），其余情形交给系统应用。
     *
     * 撞到「文件已不在」时不给一句死信，而是换成对账对话框：
     * 要么按原链接重下，要么把这条行删掉——用户此刻要的正是这两件事之一。
     */
    fun openArtifact(record: DownloadRecord) {
        if (playableInApp(record, sandboxRoot)) {
            onPlay(record.id)
            return
        }
        val path = record.outputPath ?: return
        val problem = openLocalFile(context, path)
        if (problem == OpenFileProblem.NotFound) {
            missingFile = record
        } else {
            openProblem = problem
        }
    }

    // 一次批量操作说一次：读一次就消费，切 Tab 回来不重播同一句「已移动 2 个」。
    LaunchedEffect(report) {
        val outcome = report ?: return@LaunchedEffect
        actions.onReportShown()
        Toast.makeText(context, operationReportLabel(context, outcome), Toast.LENGTH_LONG).show()
    }

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
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.download_summary, state.recordCount, formatBytes(state.usedBytes)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            DownloadFilterBar(state.filter, onSetFilter)
            if (selecting) {
                Spacer(Modifier.height(6.dp))
                DownloadSelectionBar(
                    selectedCount = selection.size,
                    onSelectAll = actions.onSelectAll,
                    onExit = actions.onClearSelection,
                )
            }
        }

        when {
            state.groups.isEmpty() && state.loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            state.groups.isEmpty() && state.recordCount == 0 -> Box(
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

            state.groups.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.download_empty_filtered),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            onClick = {
                                // 多选态下点击就是勾选/取消；单选态才展开操作面板。
                                if (selecting) actions.onToggleSelect(record.id) else selected = record
                            },
                            onLongClick = { actions.onToggleSelect(record.id) },
                            onRequestDelete = { pendingDelete = record },
                        ) {
                            HistoryCard(
                                record = record,
                                selected = record.id in selection,
                                onCancel = { pendingCancel = record },
                                onRetry = { onRetry(record.url) },
                            )
                        }
                    }
                }
            }
        }

        if (selecting) {
            DownloadBatchBar(
                busy = state.busy,
                showRename = selection.size == 1,
                enabled = selectedRecords.isNotEmpty(),
                onDelete = { pendingBatchDelete = selectedRecords },
                onMove = { showMoveSheet = true },
                onRename = { pendingRename = selectedRecords.firstOrNull() },
            )
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
                    openArtifact(record)
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
                onRename = {
                    pendingRename = record
                    selected = null
                },
                onMove = {
                    moveSingle(record)
                },
                onDetails = {
                    actions.onOpenDetails(record.id)
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
        val location = locationOf(record.outputPath, sandboxRoot)
        if (DeletionPolicy.requiresChoice(location)) {
            DeleteChoiceDialog(
                count = 1,
                onDismiss = { pendingDelete = null },
                onChoose = { choice ->
                    onDelete(record.id, choice)
                    pendingDelete = null
                },
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.history_delete_title),
                message = stringResource(
                    if (DeletionPolicy.deletesFile(DeletionPolicy.defaultChoice(location), location)) {
                        R.string.history_delete_message
                    } else {
                        R.string.history_delete_no_file_message
                    },
                ),
                confirmLabel = stringResource(R.string.history_delete_confirm),
                dismissLabel = stringResource(R.string.history_cancel),
                destructive = true,
                onConfirm = {
                    onDelete(record.id, DeletionPolicy.defaultChoice(location))
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }

    // 取消也要点头：分片会留在 .part 里，但这一行从此停在「已取消」，不会自己续跑。
    pendingCancel?.let { record ->
        ConfirmDialog(
            title = stringResource(R.string.download_cancel_title),
            message = stringResource(R.string.download_cancel_message),
            confirmLabel = stringResource(R.string.download_cancel),
            dismissLabel = stringResource(R.string.download_cancel_keep),
            destructive = true,
            onConfirm = {
                onCancel(record.id)
                pendingCancel = null
            },
            onDismiss = { pendingCancel = null },
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

    // 外部删除对账：重下走首页那条链，删行只动记录——文件本来就已经没了。
    missingFile?.let { record ->
        AlertDialog(
            onDismissRequest = { missingFile = null },
            title = { Text(stringResource(R.string.download_missing_title)) },
            text = { Text(stringResource(R.string.download_missing_body)) },
            confirmButton = {
                TextButton(onClick = {
                    missingFile = null
                    onRetry(record.url)
                }) {
                    Text(stringResource(R.string.download_missing_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    missingFile = null
                    actions.onDropMissing(record.id)
                }) {
                    Text(stringResource(R.string.download_missing_drop))
                }
            },
        )
    }

    pendingRename?.let { record ->
        RenameDialog(
            record = record,
            onDismiss = { pendingRename = null },
            onConfirm = { newName ->
                pendingRename = null
                actions.onRename(record.id, newName)
            },
        )
    }

    if (showMoveSheet) {
        MoveTargetSheet(
            onDismiss = { showMoveSheet = false },
            onPickFolder = { folderPicker.launch(null) },
            onMoveToApp = { actions.onMoveToAppFolder() },
        )
    }

    // 批量删除的问法看落点：挑进来的行里只要有一颗在公开目录，就必须摆出二选一。
    pendingBatchDelete?.let { records ->
        val locations = records.map { locationOf(it.outputPath, sandboxRoot) }
        if (DeletionPolicy.batchRequiresChoice(locations)) {
            DeleteChoiceDialog(
                count = records.size,
                onDismiss = { pendingBatchDelete = null },
                onChoose = { choice ->
                    pendingBatchDelete = null
                    actions.onBatchDelete(choice)
                },
            )
        } else {
            val deletesFile = DeletionPolicy.deletesFile(
                DeletionPolicy.defaultChoice(locations.firstOrNull() ?: RecordLocation.MISSING),
                locations.firstOrNull() ?: RecordLocation.MISSING,
            )
            val many = records.size > 1
            val messageRes = when {
                many && deletesFile -> R.string.history_delete_message_count
                many -> R.string.history_delete_no_file_message
                deletesFile -> R.string.history_delete_message
                else -> R.string.history_delete_no_file_message
            }
            ConfirmDialog(
                title = if (many) {
                    stringResource(R.string.history_delete_title_count, records.size)
                } else {
                    stringResource(R.string.history_delete_title)
                },
                // 带条数的那句必须把条数喂进去：漏了参数，用户看到的就是原样的「%1$d」。
                message = if (messageRes == R.string.history_delete_message_count) {
                    stringResource(messageRes, records.size)
                } else {
                    stringResource(messageRes)
                },
                confirmLabel = stringResource(R.string.history_delete_confirm),
                dismissLabel = stringResource(R.string.history_cancel),
                destructive = true,
                onConfirm = {
                    pendingBatchDelete = null
                    actions.onBatchDelete(DeleteChoice.RECORD_AND_FILE)
                },
                onDismiss = { pendingBatchDelete = null },
            )
        }
    }

    if (state.details != DetailsState.Hidden) {
        DetailsSheet(
            state = state.details,
            sandboxRoot = sandboxRoot,
            onDismiss = actions.onCloseDetails,
            onOpen = { record ->
                actions.onCloseDetails()
                openArtifact(record)
            },
            onRename = { record ->
                pendingRename = record
            },
            onMove = { record ->
                moveSingle(record)
            },
            onDropMissing = { id ->
                actions.onCloseDetails()
                actions.onDropMissing(id)
            },
        )
    }
}

/**
 * 公开目录的删除二选一：文件在用户自己的相册/文件管理器里，
 * 不能由 App 替他决定删不删，所以两个选择都摆出来、都要他亲手点。
 */
@Composable
private fun DeleteChoiceDialog(
    count: Int,
    onChoose: (DeleteChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (count > 1) {
                    stringResource(R.string.history_delete_title_count, count)
                } else {
                    stringResource(R.string.history_delete_title)
                },
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = if (count > 1) {
                        stringResource(R.string.history_delete_public_message_count, count)
                    } else {
                        stringResource(R.string.history_delete_public_message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onChoose(DeleteChoice.RECORD_ONLY) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text(stringResource(R.string.history_delete_record_only))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onChoose(DeleteChoice.RECORD_AND_FILE) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.history_delete_record_and_file))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteRow(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRequestDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnRequestDelete by rememberUpdatedState(onRequestDelete)
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.35f },
    )
    // 只「请求」删除：真正的删除要过二次确认，所以收到滑走回调后立刻把行弹回原位。
    // 回调保持稳定：弹框引起重组时不能让 LaunchedEffect 重启、把请求删两次。
    val onDismiss: (SwipeToDismissBoxValue) -> Unit = remember(dismissState) {
        { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) currentOnRequestDelete()
            scope.launch { dismissState.reset() }
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = onDismiss,
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

/** 状态过滤栏：四档互斥，切换只改这一页看哪些记录，不动库里的任何状态。 */
@Composable
private fun DownloadFilterBar(filter: DownloadFilter, onSelect: (DownloadFilter) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        DownloadFilter.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = filter == item,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = DownloadFilter.entries.size),
                label = { Text(stringResource(item.labelRes())) },
            )
        }
    }
}

@Composable
private fun HistoryCard(
    record: DownloadRecord,
    selected: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val running = record.status == RecordStatus.DOWNLOADING
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
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
                text = listOfNotNull(
                    sourceHost(record.url).takeIf { it.isNotBlank() },
                    timeLabel(record.updatedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (running) {
                Spacer(Modifier.height(10.dp))
                val fraction = progressFraction(record.downloadedBytes, record.bytesTotal)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fraction == null) {
                        // 总量未知（chunked 流、引擎侧任务还没报全长）：不确定进度条比一个假的 0% 诚实。
                        LinearProgressIndicator(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.download_row_cancel))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = progressLabel(record.downloadedBytes, record.bytesTotal, record.speedBps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                noteLabel(record)?.let { note ->
                    // 转封装那一段没有新心跳，进度条会一直停在最后一次的位置；这句是「还在干活」的证据。
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val detail = detailLine(record)
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 看过的行给出断点：这一条小进度条说的是「上次看到哪儿」，不是下载进度。
                watchedFraction(record)?.let { fraction ->
                    val percent = watchedPercent(record)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        if (percent != null) {
                            Text(
                                text = stringResource(R.string.history_watched, percent),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                noteLabel(record)?.let { note ->
                    // 产物完好但容器不标准（`.ts` 降级）：这一行小字是用户打不开文件时唯一的线索。
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failureReason(record)?.let { reason ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (record.status in retryableStatuses) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.download_row_retry))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 这一行的补充提示。库里存的是 key，措辞留在这里，
 * 所以只认已登记的 key——将来写入方加了没配套的文案，宁可省略也不把标识符甩给用户。
 *
 * 只在「下载中」和「已完成」两种状态下说话：提示描述的是产物或当下的阶段，
 * 一条已经失败/取消的旧记录留着上一轮那句话，只会让人对不上账。
 */
@Composable
private fun noteLabel(record: DownloadRecord): String? = when {
    record.status != RecordStatus.DONE && record.status != RecordStatus.DOWNLOADING -> null
    record.note == RecordNotes.REMUX_FALLBACK -> stringResource(R.string.download_remux_fallback)
    record.note == RecordNotes.REMUX_AUDIO_FALLBACK -> stringResource(R.string.download_remux_fallback_audio)
    record.note == RecordNotes.REMUXING -> stringResource(R.string.download_remuxing)
    else -> null
}

/** 引擎有码就报码，本机失败没有码就报原因；两头都没有就省略这一行，不编话。 */
@Composable
private fun failureReason(record: DownloadRecord): String? = when (record.status) {
    RecordStatus.FAILED, RecordStatus.INTERRUPTED, RecordStatus.CANCELLED ->
        record.errorMessage?.takeIf { it.isNotBlank() }
            ?: record.errorCode?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.history_error_code, it) }
            ?: stringResource(R.string.history_status_failed)

    else -> null
}

private val retryableStatuses = setOf(
    RecordStatus.READY,
    RecordStatus.FAILED,
    RecordStatus.CANCELLED,
    RecordStatus.INTERRUPTED,
)

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
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    val localFile = canOpenOnPhone(record.outputPath, sandboxRoot)
    val fileOperable = fileOperable(record, sandboxRoot)
    // 落点在手机、文件却已经不在了：产物被文件管理器删掉了，不是躺在电脑上。
    val deletedOnPhone = fileOperable && !localFile
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
            failureReason(record)?.let { reason ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (record.status) {
            RecordStatus.DONE -> when {
                localFile -> ActionRow(
                    stringResource(R.string.history_open_file),
                    Icons.Filled.PlayArrow,
                    onClick = onOpen,
                )

                // 这一支不能说「请到电脑上看」：文件就在手机上被删掉了，把人支到电脑前是白跑。
                deletedOnPhone -> {
                    Text(
                        text = stringResource(R.string.download_missing_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                    ActionRow(
                        stringResource(R.string.download_missing_retry),
                        Icons.Filled.Refresh,
                        onClick = onRetry,
                    )
                }

                else -> Text(
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
        // 改名/移动只对「手机上确有产物」的行有意义；引擎侧产物在电脑上，手机改不了它。
        if (fileOperable && !deletedOnPhone) {
            ActionRow(stringResource(R.string.download_rename_title), Icons.Filled.Edit, onClick = onRename)
            ActionRow(stringResource(R.string.download_batch_move), Icons.Filled.LocationOn, onClick = onMove)
        }
        ActionRow(stringResource(R.string.download_details_title), Icons.Filled.Info, onClick = onDetails)
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
private fun detailLine(record: DownloadRecord): String = listOfNotNull(
    record.format?.takeIf { it.isNotBlank() },
    record.sizeBytes?.takeIf { it > 0L }?.let(::formatBytes),
    durationLabel(record.mediaDurationMs),
).joinToString(" · ")

private fun DownloadFilter.labelRes(): Int = when (this) {
    DownloadFilter.ALL -> R.string.download_filter_all
    DownloadFilter.ACTIVE -> R.string.download_filter_active
    DownloadFilter.DONE -> R.string.download_filter_done
    DownloadFilter.FAILED -> R.string.download_filter_failed
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
/** `content://` 是手机侧 SAF 产物；本地路径还要确认仍在沙箱内且文件真的在。 */
private fun canOpenOnPhone(path: String?, sandboxRoot: String?): Boolean {
    if (path == null) return false
    if (path.startsWith("content://")) return true
    if (sandboxRoot == null) return false
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
            onSetFilter = {},
            onDelete = { _, _ -> },
            onRetry = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 900)
@Composable
private fun HistoryListPreview() {
    val now = System.currentTimeMillis()
    val records = listOf(
        previewRecord("a", "https://www.youtube.com/watch?v=abc123", RecordStatus.DONE, now, "720p · mp4"),
        previewRecord(
            id = "b",
            url = "https://cdn.example.com/clip.mp4",
            status = RecordStatus.FAILED,
            updatedAt = now - 60_000,
            format = "1080p · mp4",
            errorCode = ErrorCode.NETWORK_TIMEOUT.wireCode(),
            errorMessage = "下载超时 · 客户端本地计时 1800s 到点",
        ),
        previewRecord("c", "https://example.com/stream.m3u8", RecordStatus.INTERRUPTED, now - 3_600_000, null),
        previewRecord("d", "https://example.com/ready.mp4", RecordStatus.READY, now - 86_400_000, null),
        DownloadRecord(
            id = "e",
            taskId = "task-e",
            url = "https://cdn.example.com/live.mp4",
            title = "cdn.example.com · live",
            format = "1080p · mp4",
            status = RecordStatus.DOWNLOADING,
            createdAt = now - 120_000,
            updatedAt = now - 120_000,
            downloadedBytes = 104_857_600,
            bytesTotal = 524_288_000,
            speedBps = 5_242_880,
        ),
    )
    BgvTheme {
        HistoryScreen(
            state = HistoryUiState(
                loading = false,
                groups = groupHistory(filterRecords(records, DownloadFilter.ALL), today = LocalDate.now(), zone = ZoneId.systemDefault()),
                usedBytes = usedBytes(records),
                recordCount = records.size,
            ),
            sandboxRoot = "/data/user/0/com.hpu.browseguard/files/Download",
            onSetFilter = {},
            onDelete = { _, _ -> },
            onRetry = {},
            onCancel = {},
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
    errorMessage: String? = null,
): DownloadRecord = DownloadRecord(
    id = id,
    taskId = "task-$id",
    url = url,
    title = defaultHistoryTitle(url),
    format = format,
    status = status,
    outputPath = null,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = updatedAt - 30_000,
    updatedAt = updatedAt,
)
