package com.hpu.browseguard.shared.schema

import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 导出器单测：断言 required 集合、enum 取值、properties 平铺结构与协议一致。 */
class SchemaExporterTest {

    /** 线上契约的可空性键名，与 engine/export_schema.py 的 X_NULLABLE 同字面值；测试按协议字面量钉，不跟随实现常量。 */
    private val xNullableKey = "x-nullable"

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

    @Test
    fun `可空字段带 x-nullable 标记`() {
        assertEquals(
            setOf("error", "formatSelector", "namingTemplate", "progress", "proxyId"),
            nullableOf(SchemaExporter.schemaOf(Task.serializer())),
        )
        assertEquals(setOf("drmSystem"), nullableOf(SchemaExporter.schemaOf(SniffResult.serializer())))
        assertEquals(emptySet(), nullableOf(SchemaExporter.schemaOf(FormatInfo.serializer())))
        assertEquals(
            setOf("formatId", "maxHeight", "maxSizeBytes", "preferCodec"),
            nullableOf(SchemaExporter.schemaOf(FormatSelector.serializer())),
        )
        assertEquals(
            setOf("container", "crf", "destDir", "lang", "langs", "model", "targetCodec"),
            nullableOf(SchemaExporter.schemaOf(PostAction.serializer())),
        )
        assertEquals(setOf("detail"), nullableOf(SchemaExporter.schemaOf(ErrorPayload.serializer())))
    }

    /**
     * 可空性标记只在「字段类型可为 null」时出现，且不与 required 并存：
     * required 表达「字段必须出现在 JSON 里」，x-nullable 表达「该字段的值允许是 null」，
     * 两条轴独立（Kotlin 侧 `?` 与 `= null` 默认值也独立）。这条断言把两侧别混用钉住。
     */
    @Test
    fun `x-nullable 与 required 互斥且标记与可空集合一致`() {
        listOf(
            Task.serializer(), SniffResult.serializer(), FormatInfo.serializer(),
            FormatSelector.serializer(), PostAction.serializer(), ErrorPayload.serializer(),
        ).forEach { serializer ->
            val schema = SchemaExporter.schemaOf(serializer)
            val required = schema["required"]!!.jsonArray.strings().toSet()
            val nullable = nullableOf(schema)
            val overlap = nullable.intersect(required)
            assertTrue(actual = overlap.isEmpty(), message = "可空字段不该同时是必填：$overlap")
            schema["properties"]!!.jsonObject.forEach { (field, prop) ->
                assertEquals(
                    expected = field in nullable,
                    actual = prop.jsonObject.containsKey(xNullableKey),
                    message = "字段 $field 的 x-nullable 键与可空集合不一致",
                )
            }
        }
    }

    private fun nullableOf(schema: kotlinx.serialization.json.JsonObject): Set<String> =
        schema["properties"]!!.jsonObject
            .filter { (_, prop) -> prop.jsonObject[xNullableKey]?.jsonPrimitive?.booleanOrNull == true }
            .keys
}
