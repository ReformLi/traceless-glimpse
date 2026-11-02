package com.hpu.browseguard.data.backend

import java.io.File
import java.io.IOException

/**
 * 本地写盘沙箱：写 `.part` 之前先判「这个路径在不在允许的根之内」。
 *
 * 为什么不直接复用引擎自检的 `outputDirAllowlist`：那份白名单由**引擎进程**的
 * `BGV_OUTPUT_DIR_ALLOWLIST` 解析，默认是 `%USERPROFILE%\Videos\BGV` 这类电脑侧路径
 * （engine/main.py `_load_output_dir_allowlist`），在手机上既不存在也不该被授权。
 * 手机侧的写盘根由客户端自己声明（应用专属外部存储的 Download 目录，不需要任何存储权限），
 * 本对象只负责一件事：**规范化之后必须仍在白名单根之内**。
 *
 * 判定用 `canonicalFile`：`..` 与软链接都会被解析掉，逃逸的路径拿到的是根外的真实路径，
 * 因此「先通过校验、再被 mkdirs 成根外目录」这条路走不通。
 */
internal object LocalOutputSandbox {

    /** 通过返回 `null`；不通过返回给人看的原因（调用方包成 `Local(OUTPUT_DIR_REJECTED)`）。 */
    fun reject(target: File, allowedRoots: List<String>): String? {
        if (allowedRoots.isEmpty()) return "没有可用的写盘白名单"
        val roots = allowedRoots.mapNotNull { root ->
            runCatching { File(root).canonicalFile }.getOrNull()?.takeIf { it.isDirectory }
        }
        if (roots.isEmpty()) return "白名单里的目录在手机上不存在：$allowedRoots"

        val canonical = try {
            target.canonicalFile
        } catch (e: IOException) {
            return "路径无法规范化：${e.message ?: e.javaClass.simpleName}"
        }
        val inside = roots.any { root ->
            canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
        }
        return if (inside) null else "目标路径逃出沙箱：${canonical.path}"
    }
}
