package com.hpu.browseguard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 带 `%n$` 占位的文案，每一个调用点都得把参数喂进去。
 *
 * `stringResource(R.string.x)` 少写一个参数不会编译报错，也不会在任何单测里响一声——
 * 用户看到的就是原样的「确定删除这 %1$d 条记录？」（MuMu 实测批量删除弹框踩过这条）。
 * 这里静态扫一遍全部调用点：占位与参数是否配对，只有源码本身说了算。
 */
class ParameterizedCopyGuardTest {

    private val stringsFile = File("src/main/res/values/strings.xml")
    private val sources = File("src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun everyParameterizedCopyIsFormattedAtEveryCallSite() {
        val parameterized = Regex("""<string name="([a-zA-Z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(stringsFile.readText())
            .filter { PLACEHOLDER.containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue("占位文案一个都没扫到，说明扫描本身失效了", parameterized.isNotEmpty())

        val offenders = mutableListOf<String>()
        for ((name, body) in parameterized) {
            val bare = Regex("""(stringResource|getString)\(\s*R\.string\.$name\s*\)""")
            for (file in sources) {
                bare.findAll(file.readText()).forEach {
                    offenders += "${file.name}:${name} → \"${body.take(40)}\""
                }
            }
        }
        assertTrue("这些文案在用占位，却有调用点没喂参数：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    private companion object {
        /** 形如 `%1$`、`%2$` 的位置参数；正常中文文案里不会出现「百分号紧跟数字」。 */
        val PLACEHOLDER = Regex("""%\d+.""")
    }
}
