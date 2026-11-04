package com.hpu.browseguard

import com.hpu.browseguard.data.backend.remux.RemuxAudioGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转封装的接线钉子：平台 muxer 的调用点、降级出口、以及用户能看到的两个文案。
 *
 * 转封装本身跑不了 JVM 单测，这层只保证「代码真的接上了」——哪一半掉了，
 * 用户看到的要么是没有提示的卡顿，要么是假装成功的 `.ts`。
 */
class RemuxWiringTest {

    @Test
    fun theTsMergeGoesThroughThePlatformRemuxerAndFallsBackHonestly() {
        val downloader = File("$SRC_ROOT/data/backend/LocalDirectDownloader.kt").readText()

        assertTrue("TS 合并后要真的调转封装器", downloader.contains("remuxer.remux("))
        assertTrue("产物名要换成 .mp4", downloader.contains("withContainer(productName, \"mp4\")"))
        assertTrue("转封装期间要有独立的进度阶段", downloader.contains("phase = REMUX_PHASE"))
        assertTrue("成功要留一行", downloader.contains("\"hls_remux_done\""))
        assertTrue("降级要留一行", downloader.contains("\"hls_remux_skipped\""))
        assertTrue("降级事实要传回界面", downloader.contains("remuxFallback = remuxFallback"))
        assertTrue("音轨降级要单独标记", downloader.contains("remuxAudioFallback = remuxAudioFallback"))
        assertTrue("源 .ts 只能在 mp4 落地后删", downloader.contains("if (mp4Part.renameTo(mp4Final))"))
    }

    @Test
    fun theRemuxerGatesEverySilentOutputPathBeforeDone() {
        val remuxer = File("$SRC_ROOT/data/backend/remux/TsRemuxer.kt").readText()

        assertTrue("找不到音轨必须拒绝", remuxer.contains("RemuxAudioGuard.trackGate"))
        assertTrue("AAC 缺解码配置必须拒绝", remuxer.contains("RemuxAudioGuard.csdGate"))
        assertTrue("音频样本为 0 必须拒绝", remuxer.contains("RemuxAudioGuard.sampleGate"))
        assertTrue("产物要复开全量扫一遍", remuxer.contains("verifyOutput(target, segmentDurationMs, outputAudio)"))
        assertTrue("复开读不到音轨必须拒绝", remuxer.contains("RemuxAudioGuard.outputGate"))
        assertTrue("音视频末尾时长要对齐", remuxer.contains("RemuxAudioGuard.avDurationGate"))
        assertTrue(
            "相邻音频样本间隔审计要真的接在复检里",
            remuxer.contains("RemuxAudioGuard.audioSampleGapGate(out.maxGapUs)"),
        )
        assertTrue(
            "间隔审计排在切片审计之前：先量最直白的证据",
            remuxer.indexOf("RemuxAudioGuard.audioSampleGapGate") <
                remuxer.indexOf("RemuxAudioGuard.timeSliceGate"),
        )
        assertTrue("写源样本时就要数 ADTS 帧", remuxer.contains("sourceAudio.add(ptsUs, read, buffer)"))
        assertTrue("复检产物也要留下一份同样的扫描", remuxer.contains("out.add(ptsUs, read, buffer)"))
        assertTrue(
            "内容时长比例审计要真的接在复检里",
            remuxer.contains("RemuxAudioGuard.contentRatioGate(videoLastPtsUs - videoStartUs, out.contentUs)"),
        )
        assertTrue(
            "内容审计排在切片审计之前：绝对量比分布统计更硬",
            remuxer.indexOf("RemuxAudioGuard.contentRatioGate") <
                remuxer.indexOf("RemuxAudioGuard.timeSliceGate(videoStartUs"),
        )
        assertTrue("容差随分片时长放宽", remuxer.contains("RemuxAudioGuard.avToleranceUs(segmentDurationMs)"))
        assertTrue("产物要做时间窗口切片审计", remuxer.contains("RemuxAudioGuard.timeSliceGate"))
        assertTrue("音频样本要按 10 秒归格", remuxer.contains("RemuxAudioGuard.sliceWindowIndex(0L, ptsUs)"))
        assertTrue("视频起点要参与切片平移", remuxer.contains("videoFirstPtsUs"))
        assertTrue(
            "每个窗口要累加样本数，密度门禁才有依据",
            remuxer.contains("audioWindowCounts[window] = (audioWindowCounts[window] ?: 0) + 1"),
        )
        assertTrue(
            "切片审计要把逐窗计数而不是窗口集合交给门禁",
            remuxer.contains("RemuxAudioGuard.timeSliceGate(videoStartUs, videoLastPtsUs, audioWindowCounts)"),
        )
        assertTrue(
            "切片审计排在首尾对齐之前：moov 头部时长会被伪装，分布不会",
            remuxer.indexOf("RemuxAudioGuard.timeSliceGate") <
                remuxer.indexOf("RemuxAudioGuard.avDurationGate("),
        )
        assertTrue("muxer 拒收音频轨要走登记过的理由", remuxer.contains("RemuxAudioGuard.AUDIO_TRACK_REJECTED"))
        assertTrue("音频时间戳为负要走登记过的理由", remuxer.contains("RemuxAudioGuard.AUDIO_PTS_NEGATIVE"))

        val downloader = File("$SRC_ROOT/data/backend/LocalDirectDownloader.kt").readText()
        assertTrue("删 .ts 前还有最后一道兜底", downloader.contains("result.audioSamples <= 0"))
        assertTrue("音轨原因的降级要可识别", downloader.contains("RemuxAudioGuard.AUDIO_FALLBACK_REASONS"))
        assertTrue("分片时长要传进转封装器", downloader.contains("segmentDurationMs = (plan.segmentDurationSeconds * 1000).toLong()"))
        // 成功行、兜底行、降级行都要带上源/产物对照：少一处，现场就只能靠猜。
        assertEquals(
            "三行转封装审计都要留下源与产物的音频对照",
            3,
            Regex("audioAuditFields\\(result\\.sourceAudio, result\\.outputAudio\\)").findAll(downloader).count(),
        )
    }

    @Test
    fun thePlaylistKeepsItsTargetDurationForTheRemuxTolerance() {
        val parser = File("$SRC_ROOT/data/backend/hls/HlsPlaylistParser.kt").readText()
        val downloader = File("$SRC_ROOT/data/backend/hls/HlsDownloader.kt").readText()

        assertTrue("清单要读 TARGETDURATION", parser.contains("\"#EXT-X-TARGETDURATION:\""))
        assertTrue("缺失时退化为最长 EXTINF", parser.contains("if (targetDuration > 0.0) targetDuration else maxSegmentDuration"))
        assertTrue("计划要带分片时长", downloader.contains("segmentDurationSeconds = media.targetDurationSeconds"))
    }

    @Test
    fun theUiShowsTheRemuxPhaseAndTheFallbackLine() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val browser = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()
        val home = File("$SRC_ROOT/ui/HomeScreen.kt").readText()
        val viewModel = File("$SRC_ROOT/ui/HomeViewModel.kt").readText()

        assertTrue("转封装阶段要有提示", strings.contains("正在转封装为 MP4…"))
        assertTrue("降级文案按约定", strings.contains("转封装失败，已保留 .ts（系统播放器可能不认这个格式）"))
        assertTrue("音轨降级文案按约定", strings.contains("转封装失败，已保留原格式（含音频）"))
        assertTrue("浏览器抽屉要认 remux 阶段", browser.contains("R.string.download_remuxing"))
        assertTrue("下载卡片也要认 remux 阶段", home.contains("R.string.download_remuxing"))
        assertTrue("完成卡要显示降级提示", browser.contains("R.string.download_remux_fallback"))
        assertTrue("浏览器要认音轨降级文案", browser.contains("R.string.download_remux_fallback_audio"))
        assertTrue("下载卡也要认音轨降级文案", home.contains("R.string.download_remux_fallback_audio"))
        assertTrue("终态要把降级事实带进成功卡", viewModel.contains("remuxFallback = true"))
        assertTrue("终态要把音轨降级事实带进成功卡", viewModel.contains("remuxAudioFallback = event.remuxAudioFallback"))
    }

    /**
     * 全枚举钉子：转封装里每一个带 `audio` 的理由字面量都必须登记进 `AUDIO_FALLBACK_REASONS`。
     *
     * 少登记一条，用户看到的就是「系统播放器可能不认这个格式」——实际上格式没问题、
     * 音频也没丢，只是 MP4 会没声音。这种文案会直接把人引向错误的结论，所以写成自动检查：
     * 以后新增音频类拒绝理由，忘了登记就红。
     */
    @Test
    fun everyAudioReasonInTheRemuxerIsRegisteredForTheAudioFallbackCopy() {
        val sources = File("$SRC_ROOT/data/backend/remux")
            .listFiles { file -> file.extension == "kt" }!!
            .joinToString("\n") { it.readText() }
        val reasons = Regex("\"[a-z_]*audio[a-z_]*\"")
            .findAll(sources)
            .map { it.value.removeSurrounding("\"") }
            .toSet()

        assertTrue("扫描不到任何音频类理由，说明这条钉子指错了目录", reasons.isNotEmpty())
        val unregistered = reasons - RemuxAudioGuard.AUDIO_FALLBACK_REASONS
        assertTrue("未登记到音频降级文案的理由：$unregistered", unregistered.isEmpty())
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
