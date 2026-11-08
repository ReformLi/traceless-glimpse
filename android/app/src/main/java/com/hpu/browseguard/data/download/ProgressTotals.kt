package com.hpu.browseguard.data.download

/**
 * 引擎报不出总字节时，用它的百分比反推一个「够画进度条」的总量。
 *
 * HLS 分片流经常没有 `Content-Length`：引擎能按分片比算 `percent`，但 `bytesTotal`
 * 只能是 0。下载页的进度条只认「已下载 / 总量」，总量为 0 就退回不确定态——
 * 用户看到的就是「一直 0%，然后突然已完成」。
 *
 * 只在两件事同时成立时反推：**引擎明确给了百分比、且总量确实未知**。反推值不低于
 * 已下载字节，免得百分比四舍五入让进度条越过 100%。
 *
 * 总量已知时原样返回，不拿估算值覆盖引擎的真数字。
 */
fun progressTotalBytes(downloadedBytes: Long, declaredTotal: Long, enginePercent: Double): Long {
    if (declaredTotal > 0L) return declaredTotal
    if (downloadedBytes <= 0L || enginePercent <= 0.0) return declaredTotal
    val estimated = (downloadedBytes * 100.0 / enginePercent).toLong()
    return maxOf(estimated, downloadedBytes)
}
