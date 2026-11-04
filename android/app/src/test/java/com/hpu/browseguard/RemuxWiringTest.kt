package com.hpu.browseguard

import java.io.File
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
        assertTrue("产物要复开全量扫一遍", remuxer.contains("verifyOutput(target, segmentDurationMs)"))
        assertTrue("复开读不到音轨必须拒绝", remuxer.contains("RemuxAudioGuard.outputGate"))
        assertTrue("音视频末尾时长要对齐", remuxer.contains("RemuxAudioGuard.avDurationGate"))
        assertTrue("中段丢样本要靠音频最大间隔发现", remuxer.contains("RemuxAudioGuard.audioGapGate"))
        assertTrue("容差随分片时长放宽", remuxer.contains("RemuxAudioGuard.avToleranceUs(segmentDurationMs)"))

        val downloader = File("$SRC_ROOT/data/backend/LocalDirectDownloader.kt").readText()
        assertTrue("删 .ts 前还有最后一道兜底", downloader.contains("result.audioSamples <= 0"))
        assertTrue("音轨原因的降级要可识别", downloader.contains("RemuxAudioGuard.AUDIO_FALLBACK_REASONS"))
        assertTrue("分片时长要传进转封装器", downloader.contains("segmentDurationMs = (plan.segmentDurationSeconds * 1000).toLong()"))
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

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
