package com.hpu.browseguard.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进度总量反推（M22）：引擎给不出 `bytesTotal` 时用 `percent` 补一个够画进度条的总量。
 *
 * 这条规则唯一的目的是让下载页的进度条真的动起来——`bytesTotal=0` 时它只能显示
 * 不确定态，用户看到的就是「0% 一直不动，然后突然已完成」。
 */
class ProgressTotalsTest {

    @Test
    fun declaredTotalWinsEvenWhenPercentDisagrees() {
        assertEquals(
            "引擎给的真总量不能被估算值覆盖",
            8_000_000L,
            progressTotalBytes(downloadedBytes = 2_000_000, declaredTotal = 8_000_000, enginePercent = 30.0),
        )
    }

    @Test
    fun percentFillsInWhenTotalIsMissing() {
        assertEquals(
            "已下 25%、共 4 MB：反推出来的总量应该正好是 4 MB",
            4_000_000L,
            progressTotalBytes(downloadedBytes = 1_000_000, declaredTotal = 0, enginePercent = 25.0),
        )
    }

    @Test
    fun staysUnknownWithoutPercent() {
        assertEquals(
            "没有百分比就没有分母，宁可保持未知也不编一个数",
            0L,
            progressTotalBytes(downloadedBytes = 1_000_000, declaredTotal = 0, enginePercent = 0.0),
        )
    }

    @Test
    fun estimateNeverDropsBelowDownloaded() {
        assertEquals(
            "百分比四舍五入到 100 时，反推值不能小于已下载字节",
            1_000_000L,
            progressTotalBytes(downloadedBytes = 1_000_000, declaredTotal = 0, enginePercent = 100.0),
        )
        assertEquals(
            "百分比越过 100（引擎估算抖动）时也钳住",
            1_000_000L,
            progressTotalBytes(downloadedBytes = 1_000_000, declaredTotal = 0, enginePercent = 120.0),
        )
    }

    @Test
    fun noDownloadedBytesMeansNothingToEstimate() {
        assertEquals(
            "首片还没落地时反推只会得到 0，保持未知即可",
            0L,
            progressTotalBytes(downloadedBytes = 0, declaredTotal = 0, enginePercent = 12.5),
        )
    }
}
