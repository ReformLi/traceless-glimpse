package com.hpu.browseguard.shared.schema

import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** schema 文件落盘格式化专用；不用于模型序列化（模型一律走 BgvJson）。 */
private val PrettyJson = Json { prettyPrint = true }

/**
 * 需导出的协议模型全集（与 engine 侧对账基准一致，阶段 4 scripts/check_schema.py 逐一对账）。
 *
 * 文件名统一小写蛇形（task.json / sniff_result.json / format_info.json /
 * format_selector.json / post_action.json / error_payload.json），
 * 与 engine/schema/ 一一对应，命名风格见两侧 README。
 */
val EXPORTED_MODELS: List<Pair<String, KSerializer<*>>> = listOf(
    "task" to Task.serializer(),
    "sniff_result" to SniffResult.serializer(),
    "format_info" to FormatInfo.serializer(),
    "format_selector" to FormatSelector.serializer(),
    "post_action" to PostAction.serializer(),
    "error_payload" to ErrorPayload.serializer(),
)

/** 契约产物入口：由 gradle task :shared:exportSchemas 触发，勿手工改 JSON。 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "schema" })
    outDir.mkdirs()
    for ((name, serializer) in EXPORTED_MODELS) {
        val schema = SchemaExporter.schemaOf(serializer)
        File(outDir, "$name.json").writeText(PrettyJson.encodeToString(schema) + "\n")
    }
    println("Exported ${EXPORTED_MODELS.size} schemas to ${outDir.absolutePath}")
}
