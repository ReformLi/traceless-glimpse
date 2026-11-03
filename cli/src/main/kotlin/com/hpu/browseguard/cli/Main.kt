package com.hpu.browseguard.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ContextCliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.hpu.browseguard.engineclient.EngineApi
import kotlin.system.exitProcess

const val DEFAULT_ENGINE_URL = "http://127.0.0.1:8730"

/** 进程级依赖：输出通道、轮询参数与 API 工厂（测试可注入）。 */
class AppContext(
    val env: CliEnv,
    val runtime: CliRuntime,
    val apiFactory: (String) -> EngineApi,
) {
    var json: Boolean = false
    var engine: String = DEFAULT_ENGINE_URL
    val style: Style get() = env.style(json)
}

fun main(args: Array<String>) {
    val color = System.getenv("NO_COLOR").isNullOrEmpty() && System.console() != null
    val env = CliEnv(out = System.out, err = System.err, tty = color)
    exitProcess(runCli(args.toList(), env))
}

/**
 * 执行 CLI 并返回进程退出码。
 *
 * 帮助/用法错误在这里统一落盘：帮助走 stdout，用法错误走 stderr，
 * 任何可预期的失败都不会以异常形式逃到进程外。
 */
fun runCli(
    args: List<String>,
    env: CliEnv,
    runtime: CliRuntime = CliRuntime(),
    apiFactory: (String) -> EngineApi = { EngineApi(it) },
): Int {
    val app = AppContext(env, runtime, apiFactory)
    val root = BgvRoot(app)
    return try {
        root.parse(args)
        ExitCodes.OK
    } catch (e: CliExit) {
        e.code
    } catch (e: CliUsageException) {
        env.err.println(e.message)
        ExitCodes.INPUT
    } catch (e: PrintHelpMessage) {
        helpCommand(root, e).getFormattedHelp(e).let(env.out::println)
        ExitCodes.OK
    } catch (e: UsageError) {
        helpCommand(root, e).getFormattedHelp(e).let(env.err::println)
        ExitCodes.INPUT
    } catch (e: PrintMessage) {
        val stream = if (e.printError) env.err else env.out
        e.message?.let(stream::println)
        e.statusCode
    } catch (e: ProgramResult) {
        e.statusCode
    } catch (e: CliktError) {
        if (e.printError) e.message?.let(env.err::println)
        ExitCodes.INTERNAL
    } catch (e: Exception) {
        env.err.println("内部错误：${e.message ?: e::class.simpleName}")
        ExitCodes.INTERNAL
    }
}

private fun helpCommand(
    root: CliktCommand,
    error: CliktError,
): com.github.ajalt.clikt.core.BaseCliktCommand<*> =
    (error as? ContextCliktError)?.context?.command ?: root

/** 根命令只承载全局开关与子命令注册；具体动作在各子命令内完成。 */
class BgvRoot(private val app: AppContext) : CliktCommand(name = "bgv") {

    private val json by option(
        "--json",
        help = "输出协议 JSON；stdout 只有 JSON，提示与进度全部走 stderr",
    ).flag()

    private val engine by option(
        "--engine",
        envvar = "BGV_ENGINE_URL",
        help = "引擎地址（默认 $DEFAULT_ENGINE_URL，可用 BGV_ENGINE_URL 覆盖）",
    ).default(DEFAULT_ENGINE_URL)

    init {
        configureContext {
            terminal = Terminal(ansiLevel = if (app.env.tty) AnsiLevel.TRUECOLOR else AnsiLevel.NONE)
            echoMessage = { _, message, newline, err ->
                val stream = if (err) app.env.err else app.env.out
                if (newline) stream.println(message) else stream.print(message)
            }
        }
        subcommands(
            SniffCommand(app),
            FormatsCommand(app),
            DownloadCommand(app),
            DownloadUrlCommand(app),
            CancelCommand(app),
            StatusCommand(app),
            TasksCommand(app),
        )
    }

    override fun help(context: Context): String = "Traceless Glimpse 引擎命令行客户端"

    override fun helpEpilog(context: Context): String =
        "退出码：0 成功 | 2 输入错误 | 3 安全拒绝 | 4 内容不可用 | 5 引擎不可达 | 6 存储错误 | 7 内部错误 | 8 用户主动取消"

    override fun run() {
        app.json = json
        app.engine = engine
    }
}
