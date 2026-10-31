package com.hpu.browseguard.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 后处理动作类型白名单。PROTOCOL §3.7。未知 type → 400 INVALID_POST_ACTION（引擎侧校验）。 */
@Serializable
enum class PostActionType {
    @SerialName("merge") MERGE,
    @SerialName("remux") REMUX,
    @SerialName("transcode") TRANSCODE,
    @SerialName("extract_subtitles") EXTRACT_SUBTITLES,
    @SerialName("transcribe") TRANSCRIBE,
    @SerialName("thumbnail") THUMBNAIL,
    @SerialName("move") MOVE,
}

/**
 * 后处理动作（扁平化表示）。PROTOCOL §3.7。
 *
 * 单一对象：type 枚举 + 7 种子类参数全部平铺为可选字段：
 * - merge / thumbnail：无参数
 * - remux：container
 * - transcode：targetCodec，crf 可选
 * - extract_subtitles：langs
 * - transcribe：model、lang 均可选
 * - move：destDir（引擎侧过沙箱校验）
 */
@Serializable
data class PostAction(
    val type: PostActionType,
    /** remux：目标容器 */
    val container: String? = null,
    /** transcode：目标编码 */
    val targetCodec: String? = null,
    /** transcode 可选：质量因子 */
    val crf: Int? = null,
    /** extract_subtitles：语言列表 */
    val langs: List<String>? = null,
    /** transcribe 可选：Whisper 模型 */
    val model: String? = null,
    /** transcribe 可选：语言 */
    val lang: String? = null,
    /** move：目标目录（需过沙箱校验） */
    val destDir: String? = null,
)
