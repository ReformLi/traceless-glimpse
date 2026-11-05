package com.hpu.browseguard.data.output

/**
 * 文件名的校验与去重规则。
 *
 * 非法字符按 Windows 那一套一并禁掉：手机上的文件随时可能拷到电脑上，
 * 现在放过一个 `:`，将来在电脑上就是个改不掉的文件。
 */
object FileNaming {

    private const val MAX_BASE_LENGTH = 120
    private const val MAX_ATTEMPTS = 1_000

    /** 返回 null 表示这个名字可用；否则是给用户看的原因。 */
    fun invalidReason(raw: String): String? {
        // 控制字符在 trim 之前判：结尾的换行「看起来」能去掉，去掉它等于替用户改名字。
        if (raw.any { it.code < 0x20 }) return "名称里不能有换行或控制字符"
        val name = raw.trim()
        if (name.isEmpty()) return "名称不能为空"
        ILLEGAL.forEach { if (name.contains(it)) return "名称里不能包含 $it" }
        // 结尾的点会被当成扩展名分隔符、在电脑上还会造出无扩展名文件；空格已经被 trim 掉了。
        if (name.endsWith('.')) return "名称结尾不能是点"
        if (name.length > MAX_BASE_LENGTH) return "名称最多 $MAX_BASE_LENGTH 个字符"
        return null
    }

    /** 去掉扩展名的部分；`.mp4` 这种纯扩展名整体算基础名，改它等于改文件名本身。 */
    fun baseOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 含点的扩展名，没有就返回空串。 */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }

    /** 重名就追加 `(1)`、`(2)`……一直试到没人占；全被占了才退化成时间戳。 */
    fun unique(base: String, extension: String, exists: (String) -> Boolean): String {
        if (!exists("$base$extension")) return "$base$extension"
        for (index in 1 until MAX_ATTEMPTS) {
            val candidate = "$base ($index)$extension"
            if (!exists(candidate)) return candidate
        }
        return "$base-${System.currentTimeMillis()}$extension"
    }

    private val ILLEGAL = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
}

/** 整名版去重：SAF 发布与重命名共用同一条 `(1)` 规则，别在两个地方各写一遍。 */
internal fun uniqueFileName(name: String, exists: (String) -> Boolean): String =
    FileNaming.unique(FileNaming.baseOf(name), FileNaming.extensionOf(name), exists)
