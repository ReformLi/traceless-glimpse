package com.hpu.browseguard

import com.hpu.browseguard.data.download.HlsConcurrency
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLS 分段并发（M25）：默认 3、范围 1~8 闭区间、越界一律拒绝。
 *
 * 引擎侧 `services/hls_concurrency.py` 是同一套语义的另一半：字段名 `hlsConcurrency`、
 * 默认 3、范围 1~8、越界拒绝。两端的界限值必须逐字相同——差一个数就是
 * 「手机存得下、引擎拒收」的悬空配置。
 *
 * 拒绝而不是夹取，是用户裁决：把 9 悄悄夹成 8 等于改了用户的意图还没留痕；
 * 这里宁可让保存失败并告诉他范围是多少。
 */
class HlsConcurrencyTest {

    @Test
    fun defaultIsThreeAndTheRangeIsOneToEight() {
        assertEquals(3, HlsConcurrency.DEFAULT)
        assertEquals(1, HlsConcurrency.MIN)
        assertEquals(8, HlsConcurrency.MAX)
    }

    @Test
    fun valuesInsideTheRangeParse() {
        assertEquals(1, HlsConcurrency.parse("1"))
        assertEquals(3, HlsConcurrency.parse("3"))
        assertEquals(8, HlsConcurrency.parse("8"))
        assertEquals("输入框里带空格不该判错", 6, HlsConcurrency.parse(" 6 "))
    }

    @Test
    fun outOfRangeIsRejected() {
        listOf("0", "9", "-1", "100").forEach { raw ->
            assertNull("越界必须拒绝：$raw", HlsConcurrency.parse(raw))
        }
    }

    @Test
    fun nonNumericIsRejected() {
        listOf("abc", "3.5", "", " ", "1e1", "3 4").forEach { raw ->
            assertNull("非数字必须拒绝：$raw", HlsConcurrency.parse(raw))
        }
        assertNull("null 不是合法输入", HlsConcurrency.parse(null))
    }

    @Test
    fun leadingZeroStillResolvesToItsNumericValue() {
        assertEquals(8, HlsConcurrency.parse("08"))
    }

    @Test
    fun missingOrCorruptStoredValueFallsBackToTheDefault() {
        // 旧版本没写过这个键 → 缺失
        assertEquals(3, HlsConcurrency.fromStored(null))
        // 存储被改坏（越界）→ 回落，不崩、不把非法值传下去
        assertEquals(3, HlsConcurrency.fromStored(0))
        assertEquals(3, HlsConcurrency.fromStored(9))
        assertEquals(3, HlsConcurrency.fromStored(-1))
        // 合法值原样返回
        assertEquals(5, HlsConcurrency.fromStored(5))
    }

    @Test
    fun everyAcceptedValueRoundTripsThroughStorage() {
        (HlsConcurrency.MIN..HlsConcurrency.MAX).forEach { value ->
            assertEquals(value, HlsConcurrency.parse(value.toString()))
            assertEquals(value, HlsConcurrency.fromStored(value))
        }
    }

    @Test
    fun theDownloaderTakesItsBudgetFromTheSettingInsteadOfAHardcodedConstant() {
        val downloader = File("$SRC_ROOT/data/backend/hls/HlsDownloader.kt").readText()
        val local = File("$SRC_ROOT/data/backend/LocalDirectDownloader.kt").readText()
        val container = File("$SRC_ROOT/data/AppContainer.kt").readText()

        assertTrue(
            "HlsDownloader 必须由外部注入并发数",
            downloader.contains("concurrency: Int = HlsConcurrency.DEFAULT"),
        )
        assertFalse(
            "并发数不许再是写死的槽位常量",
            downloader.contains("SEGMENT_CONCURRENCY"),
        )
        assertTrue(
            "本机下载器要把设置一路带到 HLS 下载器",
            local.contains("HlsDownloader(") && local.contains("concurrency"),
        )
        assertTrue(
            "AppContainer 是设置注入点",
            container.contains("settings.hlsConcurrency"),
        )
    }

    @Test
    fun theSettingsScreenKeepsOnlyTheDiscreteSlider() {
        // 只锁结构，不锁中文文案：改一个字就让测试变红的是假测试。
        val screen = File("$SRC_ROOT/ui/SettingsScreen.kt").readText()
        assertTrue("滑杆要绑定到状态字段", screen.contains("state.hlsConcurrency"))
        assertTrue("保存回调要一路传到 ViewModel", screen.contains("onHlsConcurrencyChange"))
        assertTrue("松手才落盘，不能只在本地改显示", screen.contains("onValueChangeFinished"))
        assertTrue("必须有离散滑杆", screen.contains("Slider("))
        assertTrue(
            "档位数量由常量区间推导，不许在界面里写死 8 或 6",
            screen.contains("steps = HlsConcurrency.MAX - HlsConcurrency.MIN - 1") &&
                screen.contains("HlsConcurrency.MIN.toFloat()..HlsConcurrency.MAX.toFloat()"),
        )
        assertFalse(
            "滑杆下面那条 1~8 刻度尺已按用户要求去掉，不许再遍历档位画数字",
            screen.contains("HlsConcurrency.MIN..HlsConcurrency.MAX"),
        )
        assertTrue(
            "轨道与拇指自绘：默认滑杆太笨重",
            screen.contains("track = {") && screen.contains("thumb = {"),
        )
        assertTrue(
            "自绘轨道要读当前比例才画得出已填充段",
            screen.contains("coercedValueAsFraction"),
        )
        assertTrue(
            "轨道上要画出档位断点，否则离散滑杆看不出「只能停在整点」",
            screen.contains("for (tick in HlsConcurrency.MIN + 1 until HlsConcurrency.MAX)") &&
                screen.contains("drawCircle"),
        )
        assertFalse(
            "轨道画布已被滑杆按拇指宽度内缩过，再内缩一次就会短一截（真机实测过）",
            screen.contains("val inset"),
        )
        assertTrue(
            "范围必须由常量提供，不许在界面里再写一份 1~8 的判断",
            screen.contains("HlsConcurrency.MIN") && screen.contains("HlsConcurrency.MAX"),
        )
        assertFalse("文本输入框已被滑杆取代，数字键盘不该留在设置页", screen.contains("KeyboardType.Number"))
    }

    @Test
    fun theEngineRequestCarriesTheSameValue() {
        val vm = File("$SRC_ROOT/ui/HomeViewModel.kt").readText()
        assertTrue(
            "引擎模式下要把设置发给引擎，否则桌面端与手机端各按各的跑",
            vm.contains("hlsConcurrency = "),
        )
        assertTrue("值取自设置，不许在 ViewModel 里再写一个 3", vm.contains("settings.hlsConcurrency"))
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
