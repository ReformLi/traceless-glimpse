package com.hpu.browseguard.ui

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryGroupingTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 2)

    @Test
    fun groupsByTodayYesterdayAndEarlierInDescendingOrder() {
        val todayMorning = at(today, 9, 0)
        val todayEvening = at(today, 20, 0)
        val yesterday = at(today.minusDays(1), 23, 0)
        val earlier = at(today.minusDays(5), 12, 0)

        val groups = groupHistory(
            listOf(
                record("a", earlier),
                record("b", todayMorning),
                record("c", yesterday),
                record("d", todayEvening),
            ),
            today = today,
            zone = zone,
        )

        assertEquals(
            listOf(HistoryGroupLabel.TODAY, HistoryGroupLabel.YESTERDAY, HistoryGroupLabel.EARLIER),
            groups.map { it.label },
        )
        assertEquals(listOf("d", "b"), groups[0].records.map { it.id })
        assertEquals(listOf("c"), groups[1].records.map { it.id })
        assertEquals(listOf("a"), groups[2].records.map { it.id })
    }

    @Test
    fun groupingUsesUpdatedAtSoARecordMovedToTodayShowsUnderToday() {
        val createdYesterday = at(today.minusDays(1), 23, 50)
        val updatedToday = at(today, 0, 10)
        val groups = groupHistory(
            listOf(record("late", updatedToday, createdAt = createdYesterday)),
            today = today,
            zone = zone,
        )

        assertEquals(HistoryGroupLabel.TODAY, groups.single().label)
    }

    @Test
    fun emptyInputProducesNoGroups() {
        assertEquals(emptyList<HistoryGroup>(), groupHistory(emptyList(), today, zone))
    }

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(day.atTime(hour, minute), zone).toInstant().toEpochMilli()

    private fun record(id: String, updatedAt: Long, createdAt: Long = updatedAt - 1_000) =
        DownloadRecord(
            id = id,
            url = "https://example.com/$id",
            status = RecordStatus.DONE,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
