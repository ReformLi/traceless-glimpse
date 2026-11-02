package com.hpu.browseguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.HistoryRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryGroupLabel { TODAY, YESTERDAY, EARLIER }

data class HistoryGroup(
    val label: HistoryGroupLabel,
    val records: List<DownloadRecord>,
)

data class HistoryUiState(
    val groups: List<HistoryGroup> = emptyList(),
    val loading: Boolean = true,
)

/** 历史页只读仓库、写删除；打开/复制/重试都是界面动作。 */
class HistoryViewModel(private val history: HistoryRepository) : ViewModel() {

    val state: StateFlow<HistoryUiState> = history.records
        .map { records -> HistoryUiState(groups = groupHistory(records), loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    fun delete(id: String) {
        viewModelScope.launch { history.delete(id) }
    }

    fun clearAll() {
        if (_clearing.value) return
        viewModelScope.launch {
            _clearing.value = true
            try {
                history.deleteAll()
            } finally {
                _clearing.value = false
            }
        }
    }

    companion object {
        fun factory(history: HistoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
                        "HistoryViewModel.Factory 不认识的类型：$modelClass"
                    }
                    return HistoryViewModel(history) as T
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
