package com.hpu.browseguard.shared.schema

import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.Task
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 导出器单测：断言 required 集合、enum 取值、properties 平铺结构与协议一致。 */
class SchemaExporterTest {

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

    private fun enumOf(schema: kotlinx.serialization.json.JsonObject, prop: String): List<String> =
        schema["properties"]!!.jsonObject[prop]!!.jsonObject["enum"]!!.jsonArray.strings()

    @Test
    fun `Task schema required 集合与协议一致`() {
        val schema = SchemaExporter.schemaOf(Task.serializer())
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("id", "sourceUrl", "mode", "cookiePolicy", "outputDir", "status", "createdAt", "updatedAt"),
            schema["required"]!!.jsonArray.strings().toSet(),
        )
    }

    @Test
    fun `Task schema 枚举取值与协议一致`() {
        val schema = SchemaExporter.schemaOf(Task.serializer())
        assertEquals(setOf("sniff", "download"), enumOf(schema, "mode").toSet())
        assertEquals(setOf("none", "lazy", "explicit"), enumOf(schema, "cookiePolicy").toSet())
        assertEquals(
            setOf(
                "queued", "sniffing", "awaiting_selection", "downloading",
                "merging", "post_processing", "done", "cancelled", "failed",
            ),
            enumOf(schema, "status").toSet(),
        )
        // 嵌套对象占位；数组类型标记
        val props = schema["properties"]!!.jsonObject
        assertEquals("object", props["formatSelector"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("object", props["progress"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("array", props["postActions"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PostAction schema 为扁平结构`() {
        val schema = SchemaExporter.schemaOf(PostAction.serializer())
        // required 仅 type
        assertEquals(listOf("type"), schema["required"]!!.jsonArray.strings())
        // type 枚举 7 值（PROTOCOL §3.7 白名单）
        assertEquals(
            setOf("merge", "remux", "transcode", "extract_subtitles", "transcribe", "thumbnail", "move"),
            enumOf(schema, "type").toSet(),
        )
        // 7 种子类参数全部平铺为可选属性
        val props = schema["properties"]!!.jsonObject
        assertEquals(
            setOf("type", "container", "targetCodec", "crf", "langs", "model", "lang", "destDir"),
            props.keys,
        )
        assertEquals("string", props["container"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", props["targetCodec"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["crf"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("array", props["langs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", props["model"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", props["lang"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", props["destDir"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ErrorPayload schema 错误码枚举完整（30 个）`() {
        val schema = SchemaExporter.schemaOf(ErrorPayload.serializer())
        val props = schema["properties"]!!.jsonObject
        // PROTOCOL §5 全部 28 个 + 裁决 VALIDATION_ERROR + NOT_FOUND
        val codes = props["code"]!!.jsonObject["enum"]!!.jsonArray.strings()
        assertEquals(30, codes.size)
        assertEquals(30, codes.toSet().size) // 无重复
        assertTrue("VALIDATION_ERROR" in codes)
        assertTrue("NOT_FOUND" in codes)
        assertTrue(setOf("SSRF_BLOCKED", "DRM_PROTECTED", "OUTPUT_DIR_REJECTED", "TASK_STATE_CONFLICT").all { it in codes })
        // category 枚举 6 值
        assertEquals(
            setOf("input", "network", "security", "content", "storage", "internal"),
            props["category"]!!.jsonObject["enum"]!!.jsonArray.strings().toSet(),
        )
        // retryable 布尔；detail 为可选嵌套对象（§9.4 错误响应无 detail）
        assertEquals("boolean", props["retryable"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("object", props["detail"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("code", "category", "message", "retryable"),
            schema["required"]!!.jsonArray.strings().toSet(),
        )
    }

    @Test
    fun `FormatSelector schema required 仅 strategy 且枚举正确`() {
        val schema = SchemaExporter.schemaOf(FormatSelector.serializer())
        assertEquals(listOf("strategy"), schema["required"]!!.jsonArray.strings())
        assertEquals(
            setOf("exact", "max_height", "best", "audio_only"),
            enumOf(schema, "strategy").toSet(),
        )
        val props = schema["properties"]!!.jsonObject
        assertEquals("string", props["formatId"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["maxHeight"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["maxSizeBytes"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
