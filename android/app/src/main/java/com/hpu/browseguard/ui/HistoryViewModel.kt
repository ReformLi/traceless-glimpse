package com.hpu.browseguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.download.DownloadCoordinator
import com.hpu.browseguard.data.history.DeleteChoice
import com.hpu.browseguard.data.history.DeletionPolicy
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.media.ArtifactSnapshot
import com.hpu.browseguard.data.media.MediaProbe
import com.hpu.browseguard.data.output.ArtifactChange
import com.hpu.browseguard.data.output.ArtifactMover
import com.hpu.browseguard.data.output.FileNaming
import com.hpu.browseguard.data.output.MoveTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryGroupLabel { TODAY, YESTERDAY, EARLIER }

data class HistoryGroup(
    val label: HistoryGroupLabel,
    val records: List<DownloadRecord>,
)

/** 详情面板的三段状态：藏着、正在问文件系统、读完了。 */
sealed interface DetailsState {
    data object Hidden : DetailsState

    /** 探测跑在 IO 线程，中途界面要有一句「正在读」而不是一片空白。 */
    data class Loading(val record: DownloadRecord) : DetailsState

    data class Shown(val snapshot: ArtifactSnapshot) : DetailsState
}

data class HistoryUiState(
    val groups: List<HistoryGroup> = emptyList(),
    /** 仓库第一次发射之前为 true：否则冷启动会先闪一帧「历史是空的」。 */
    val loading: Boolean = true,
    /** 状态过滤栏当前选中的那一档。 */
    val filter: DownloadFilter = DownloadFilter.ALL,
    /** 已完成记录的产物字节数汇总；按 [DownloadFilter] 过滤前后都统计全部，免得切一下数字就跳。 */
    val usedBytes: Long = 0,
    val recordCount: Int = 0,
    /** 长按多选选中的行；非空集就是处于多选状态。 */
    val selection: Set<String> = emptySet(),
    /** 有一批文件操作正在跑：这期间再点批量按钮不该开出第二个批次。 */
    val busy: Boolean = false,
    val details: DetailsState = DetailsState.Hidden,
)

/**
 * 下载管理页：读仓库出清单，取消交给协调器，改名与移动交给搬运工。
 *
 * [sandboxRoot] 只用来判定落点（应用沙箱 / SAF 公开目录 / 电脑侧）——删除策略、
 * 这一行能不能改名移动、批量该问哪一句，全由落点决定。
 */
class HistoryViewModel(
    private val history: HistoryRepository,
    private val downloads: DownloadCoordinator,
    private val mover: ArtifactMover,
    private val probe: MediaProbe,
    private val sandboxRoot: String?,
) : ViewModel() {

    private val filter = MutableStateFlow(DownloadFilter.ALL)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val details = MutableStateFlow<DetailsState>(DetailsState.Hidden)

    /** null 表示还没收到第一次发射。批量操作要按 id 找回整行，过滤后的清单里没有它就没法下手。 */
    private val allRecords = MutableStateFlow<List<DownloadRecord>?>(null)

    val state: StateFlow<HistoryUiState> =
        combine(allRecords, filter, selection, busy, details) { records, current, picked, running, panel ->
            val visible = records.orEmpty()
            HistoryUiState(
                groups = groupHistory(filterRecords(visible, current)),
                loading = records == null,
                filter = current,
                usedBytes = usedBytes(visible),
                recordCount = visible.size,
                selection = picked,
                busy = running,
                details = panel,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private val _report = MutableStateFlow<OperationReport?>(null)

    /** 一次文件操作的汇总；界面用 Snackbar 显示一次，随即由 [consumeReport] 清空。 */
    val report: StateFlow<OperationReport?> = _report.asStateFlow()

    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    init {
        viewModelScope.launch {
            history.records.collect { records ->
                allRecords.value = records
                val alive = records.mapTo(mutableSetOf()) { it.id }
                // 行已经不在了就不该留在选中集里：批量删完或清空历史后，顶栏不该还显示「已选 3 项」。
                if (selection.value.isNotEmpty()) {
                    selection.value = selection.value.filterTo(mutableSetOf()) { it in alive }
                }
                val openId = (details.value as? DetailsState.Shown)?.snapshot?.record?.id
                if (openId != null && openId !in alive) {
                    details.value = DetailsState.Hidden
                }
            }
        }
    }

    fun setFilter(next: DownloadFilter) {
        filter.value = next
    }

    fun consumeReport() {
        _report.value = null
    }

    /**
     * 取消一条还在跑的记录。
     *
     * 这里只发指令：终态仍由协调器收集的那条 `start` 流写。两处都改状态就会打架，
     * 屏上会出现「已经取消、进度却还在涨」。
     */
    fun cancel(recordId: String) {
        viewModelScope.launch { downloads.cancel(recordId) }
    }

    /** 长按进多选，再点一下切换选中。 */
    fun toggleSelect(recordId: String) {
        selection.update { if (recordId in it) it - recordId else it + recordId }
    }

    /** 全选跟着当前过滤档走：在「已完成」里全选，不该把失败那一档的行也圈进来。 */
    fun selectAllShown() {
        val shown = filterRecords(allRecords.value.orEmpty(), filter.value).mapTo(mutableSetOf()) { it.id }
        selection.value = if (shown.isNotEmpty() && selection.value.containsAll(shown)) emptySet() else shown
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /**
     * 删一批记录。
     *
     * [choice] 由界面按 [DeletionPolicy] 问出来：应用专属目录一次确认就删干净，
     * 公开目录必须让用户在「留视频」和「连视频一起删」之间亲手选。
     *
     * 在跑的行先取消再删：不然协调器那条流还在往 `.part` 里写字节，而行已经没了——
     * 留下的半成品没人认领，也永远等不到下一次清理。
     */
    fun delete(ids: List<String>, choice: DeleteChoice) {
        if (ids.isEmpty()) return
        runOperation {
            for (id in ids) {
                val status = recordOf(id)?.status
                if (status in inFlightStatuses) {
                    downloads.cancel(id)
                }
                history.delete(id, choice)
            }
            selection.value = emptySet()
            details.value = DetailsState.Hidden
            _report.value = OperationReport(OperationVerb.DELETE, succeeded = ids.size)
        }
    }

    fun deleteSelected(choice: DeleteChoice) {
        delete(selection.value.toList(), choice)
    }

    /** 单条删除走同一个入口：界面问出来的 [choice] 决定要不要动文件。 */
    fun delete(id: String, choice: DeleteChoice) {
        delete(listOf(id), choice)
    }

    /**
     * 外部删除对账：「打开」撞到文件已经不在了，界面给的重兜底就是删掉这一行。
     *
     * 只删记录，不再问「要不要连文件一起删」——真相是文件已经没了，再问一遍是消遣用户。
     */
    fun dropMissingRecord(id: String) {
        delete(listOf(id), DeleteChoice.RECORD_ONLY)
    }

    /**
     * 改名：扩展名沿用现在那个，重名由搬运工追加 `(1)`。
     *
     * 先搬文件、成了才回填这一行——反过来会出现「清单说新名字，盘上还是旧文件」，
     * 而用户的下一个动作就是照着清单里那个名字去找它。
     */
    fun rename(recordId: String, newBaseName: String) {
        val record = recordOf(recordId)
        if (record == null) {
            _report.value = OperationReport(OperationVerb.RENAME, 0, failures = listOf("这一条已经不在了"))
            return
        }
        runOperation {
            val change = mover.rename(record, newBaseName)
            if (change is ArtifactChange.Changed) {
                history.updateArtifact(
                    id = recordId,
                    outputPath = change.outputPath,
                    title = FileNaming.baseOf(change.name),
                    sizeBytes = change.sizeBytes,
                )
                refreshDetails(recordId)
            }
            _report.value = reportOf(OperationVerb.RENAME, listOf(change))
        }
    }

    /**
     * 把选中的行移到某个 SAF 文件夹。
     *
     * 权限先抓牢再动第一个文件：系统选择器交回的 URI 只带临时授权，而搬运跑在 IO 协程里，
     * 可能跨过界面重建。抓不住就一个都不搬——搬一半才发现没权限，剩下那半的落点没法交代。
     */
    fun moveToFolder(treeUri: String) {
        moveSelected(MoveTarget.PublicTree(treeUri))
    }

    /** 把选中的行移回应用专属目录（其他 App 看不见的沙箱）。 */
    fun moveToAppFolder() {
        moveSelected(MoveTarget.PrivateSandbox)
    }

    fun clearAll() {
        if (_clearing.value) return
        viewModelScope.launch {
            _clearing.value = true
            try {
                history.deleteAll()
                selection.value = emptySet()
                details.value = DetailsState.Hidden
            } finally {
                _clearing.value = false
            }
        }
    }

    private fun moveSelected(target: MoveTarget) {
        val picked = selection.value.toList().mapNotNull(::recordOf)
        if (picked.isEmpty()) return
        runOperation {
            if (target is MoveTarget.PublicTree) {
                val denied = mover.permitTarget(target.treeUri)
                if (denied != null) {
                    _report.value = OperationReport(OperationVerb.MOVE, 0, failures = listOf(denied))
                    return@runOperation
                }
            }
            val movable = picked.filter { fileOperable(it, sandboxRoot) }
            val skipped = picked.size - movable.size
            // record 和它的结果成对拿着，回填时才不会出现「A 的新路径写到 B 那一行」。
            val changes = movable.map { record -> record to mover.move(record, target) }
            for ((record, change) in changes) {
                if (change is ArtifactChange.Changed) {
                    history.updateArtifact(
                        id = record.id,
                        outputPath = change.outputPath,
                        // 移动不改名字：标题保持原样，只有产物路径与大小跟着落点走。
                        title = null,
                        sizeBytes = change.sizeBytes,
                    )
                }
            }
            selection.value = emptySet()
            details.value = DetailsState.Hidden
            _report.value = reportOf(OperationVerb.MOVE, changes.map { it.second }, skipped = skipped)
        }
    }

    /**
     * 打开详情：报**此刻**的文件属性，不报记录里那个可能已经滞后的数字。
     *
     * 改名、移动、或用户在文件管理器里自己动手之后，记录里的名字与大小都会滞后；
     * 探测跑在 IO 线程，界面先显示「正在读」。
     */
    fun openDetails(recordId: String) {
        val record = recordOf(recordId) ?: return
        details.value = DetailsState.Loading(record)
        viewModelScope.launch {
            details.value = DetailsState.Shown(probe.probe(record))
        }
    }

    fun closeDetails() {
        details.value = DetailsState.Hidden
    }

    /** 改完名之后面板还开着：重新探一次，否则用户看到的还是旧文件名。 */
    private fun refreshDetails(recordId: String) {
        val current = details.value
        if (current is DetailsState.Shown && current.snapshot.record.id == recordId) {
            openDetails(recordId)
        }
    }

    private fun recordOf(id: String): DownloadRecord? = allRecords.value?.firstOrNull { it.id == id }

    /**
     * 一段批量操作的壳：串行跑、期间置 `busy`。
     *
     * 串行而不是并发：一次移动要占满磁盘 IO，两个批次同时跑只会互相拖慢；
     * 而且 SAF 同名去重要看「目标里现在有没有这个文件」，并发会撞出两份同名。
     */
    private fun runOperation(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        /** 删这些状态的行之前要先取消：协程还活着，行没了它就会把半成品写到没人认领的地方。 */
        private val inFlightStatuses = setOf(RecordStatus.DOWNLOADING, RecordStatus.SNIFFING)

        fun factory(
            history: HistoryRepository,
            downloads: DownloadCoordinator,
            mover: ArtifactMover,
            probe: MediaProbe,
            sandboxRoot: String?,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
                        "HistoryViewModel.Factory 不认识的类型：$modelClass"
                    }
                    return HistoryViewModel(history, downloads, mover, probe, sandboxRoot) as T
                }
            }
    }
}

/**
 * 按「今天 / 昨天 / 更早」分组，组内与组间都按最近活动时间倒序。
 *
 * 用 `updatedAt` 而不是 `createdAt`：一条昨晚开始、今早完成的下载，
 * 用户会认为它是「今天」的。
 */
internal fun groupHistory(
    records: List<DownloadRecord>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<HistoryGroup> {
    val yesterday = today.minusDays(1)
    val buckets = linkedMapOf<HistoryGroupLabel, MutableList<DownloadRecord>>()
    records.sortedByDescending { it.updatedAt }.forEach { record ->
        val date = Instant.ofEpochMilli(record.updatedAt).atZone(zone).toLocalDate()
        val label = when (date) {
            today -> HistoryGroupLabel.TODAY
            yesterday -> HistoryGroupLabel.YESTERDAY
            else -> HistoryGroupLabel.EARLIER
        }
        buckets.getOrPut(label) { mutableListOf() } += record
    }
    return HistoryGroupLabel.entries.mapNotNull { label ->
        buckets[label]?.let { HistoryGroup(label, it) }
    }
}
