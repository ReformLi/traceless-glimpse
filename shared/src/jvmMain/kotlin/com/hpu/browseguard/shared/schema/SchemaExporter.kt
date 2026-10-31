package com.hpu.browseguard.shared.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 自写 SerialDescriptor → JSON Schema 导出器。
 *
 * 顶层结构与 Pydantic model_json_schema() 同构：{"type":"object","required":[...],"properties":{...}}。
 * - 枚举 → {"type":"string","enum":[...]}（取值为 @SerialName 修饰后的协议值）
 * - 嵌套对象（CLASS / MAP / CONTEXTUAL）→ {"type":"object"} 占位，阶段 4 对嵌套层降级比对
 * - 数组 → {"type":"array"}（元素类型不展开）
 * - 不追求完整 JSON Schema 语义，仅覆盖 required / enum / properties / 基础类型。
 */
object SchemaExporter {

    fun schemaOf(serializer: KSerializer<*>): JsonObject {
        val d = serializer.descriptor
        val required = buildJsonArray {
            for (i in 0 until d.elementsCount) {
                if (!d.isElementOptional(i)) add(d.getElementName(i))
            }
        }
        val properties = buildJsonObject {
            for (i in 0 until d.elementsCount) {
                put(d.getElementName(i), typeOf(d.getElementDescriptor(i)))
            }
        }
        return buildJsonObject {
            put("type", "object")
            put("required", required)
            put("properties", properties)
        }
    }

    private fun typeOf(desc: SerialDescriptor): JsonObject = buildJsonObject {
        when (desc.kind) {
            SerialKind.ENUM -> {
                put("type", "string")
                put(
                    "enum",
                    buildJsonArray {
                        for (i in 0 until desc.elementsCount) add(desc.getElementName(i))
                    },
                )
            }
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                put("type", "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> put("type", "string")
            StructureKind.LIST -> put("type", "array")
            else -> put("type", "object") // CLASS / MAP / OBJECT / CONTEXTUAL：嵌套占位
        }
    }
}
