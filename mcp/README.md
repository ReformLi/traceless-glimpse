# mcp/ — MCP Server

Kotlin/JVM，复用 `:shared` 协议模型，向 AI 客户端暴露 `sniff` / `probe` / `download` / `status` 工具（PRD FR-AG-06）。

M0 仅占位，M2+ 实现。Agent 只能调用白名单工具（嗅探、解析、下载、合并、转录、重命名），不可执行任意 Shell；无法指定 Cookie 文件路径或浏览器类型（PRD FR-AG-04/05）。
