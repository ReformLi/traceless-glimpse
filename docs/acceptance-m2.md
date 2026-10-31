# BrowseGuard Vid M2 验收报告（PRD §9 标准 2 / 5 / 6）

| 项 | 值 |
|---|---|
| 执行日期 | 2026-10-31（Asia/Shanghai） |
| 验收范围 | PRD §9 验收标准 **2（凭据最小化）**、**5（日志脱敏）**、**6（DRM）** |
| 被测代码 | commit `f21ff56` + M2 未提交改动（日志脱敏 / 输出沙箱 / 惰性 Cookie / 隐私自检） |
| 运行环境 | Windows 10.0.26300 · Python 3.11.9 · Playwright 1.61.0 · yt-dlp 2026.10.26 · ffmpeg 2025-03-10 gyan build |
| 取证方式 | 单元 / 真实链路 pytest + 本机回环媒体服务器 + 真实引擎 HTTP 请求与日志导出 |
| 结论 | **标准 2、5、6 全部通过** |

| 标准 | 要求摘要 | 结论 | 证据位置 |
|---|---|---|---|
| 2 | 普通页面不读 Cookie 文件；被拒时仅一次读取、一次重试；原文件 mtime 不变 | 通过 | §1 |
| 5 | 导出日志中检索不到 Cookie 值、token、完整敏感查询参数 | 通过 | §2 |
| 6 | 加密流返回明确"不支持 DRM"错误码，不产生下载尝试 | 通过 | §3 |

---

## 1. 验收标准 2：凭据最小化（惰性 Cookie）

> PRD §9.2：普通可下载页面全程不读取任何 Cookie 文件；被拒场景下仅发生一次读取、一次重试，且原 Cookie 文件 mtime 不变。

### 1.1 实现对照

| 环节 | 实现 | 文件 |
|---|---|---|
| 凭据来源 | 仅引擎侧 `BGV_COOKIE_FILE`（Netscape cookies.txt），协议不出现文件路径字段 | `engine/services/cookies.py` |
| 惰性触发 | 首次导航/媒体请求不带 Cookie；仅当导航或媒体响应 401/403 时读取一次并重试一次 | `engine/services/sniffer.py` `_sniff` / `_retry_with_cookies` |
| yt-dlp 写回保护 | 原文件只读；需要写回时使用临时副本，结束后删除 | `engine/services/cookies.py` `cookie_file_copy` |
| 直链下载 | 401/403 时读取一次 Cookie 头并重试一次 | `engine/services/downloader.py` `download_direct` |

### 1.2 真实链路证据（本机回环媒体服务器）

取证脚本使用 Playwright 真实打开本地页面，媒体服务器记录每个请求携带的 Cookie，并统计 Cookie 文件的读取次数：

```json
{
  "normalPage": {
    "observed": "http://127.0.0.1:14844/media/hls/master.m3u8",
    "cookieFileReads": 0,
    "requests": 3,
    "requestsCarryingCredential": 0
  },
  "protectedPage": {
    "observed": "http://127.0.0.1:14844/media/hls/master.m3u8",
    "cookieFileReads": 1,
    "pageRequests": 2,
    "firstRequestCookie": "",
    "secondRequestCookie": "sid=good-cookie",
    "mtimeBefore": 1793451636.0936644,
    "mtimeAfter": 1793451636.0936644,
    "mtimeChanged": false
  }
}
```

| 场景 | 硬读数 | 结论 |
|---|---|---|
| 普通页面 `cookiePolicy=lazy` | 读取 **0** 次；3 个请求均未携带凭据；仍嗅探到 HLS | 全程不读 Cookie 文件 |
| 受保护页面 `cookiePolicy=lazy` | 页面请求 **2** 次（首次无 Cookie → 403，第二次带 `sid=good-cookie` → 成功）；读取 **1** 次 | 恰好一次读取、一次重试 |
| Cookie 文件 mtime | 前后均为 `1793451636.0936644`（2026-10-31 21:00:36），`mtimeChanged=false` | 原文件未被修改 |

真实链路用例（`engine/tests_real/test_lazy_cookie_sniff.py`，4 条全过）：

```
test_normal_page_lazy_never_reads_cookie PASSED
test_protected_page_lazy_reads_once_and_retries_once PASSED
test_protected_page_none_gets_no_media PASSED
test_protected_page_explicit_sends_cookie_on_first_request PASSED
```

补充：`engine/test_cookies.py` 14 条通过，覆盖域/路径/secure/过期匹配、临时副本删除、yt-dlp lazy/explicit/none 三条路径。

---

## 2. 验收标准 5：日志脱敏

> PRD §9.5：导出日志中检索不到 Cookie 值、token、完整敏感查询参数。

### 2.1 实现对照

`engine/log_redaction.py` 在应用启动时给根 logger、`uvicorn` / `uvicorn.error` / `uvicorn.access` 的现有 handler 以及 `lastResort` 挂载 `RedactingFilter`。过滤器在格式化前处理消息、字符串参数、异常栈与 `stack_info`，覆盖：

- `Cookie` / `Set-Cookie` / `Authorization` / `Proxy-Authorization` 头整值；
- `Bearer` / `Basic` 凭据、JWT；
- 敏感查询参数（`token` / `signature` / `session` 等）及带前缀键名（`user_password`、`x-api-key`）；
- 脱敏幂等，审计行（`task_id`、字节数）原样保留。

### 2.2 真实引擎日志证据

真实引擎（uvicorn）接收含敏感数据的请求后导出全部日志：

```
$ curl "http://127.0.0.1:8731/v1/health?access_token=SECRET-TOKEN-123&quality=720"
$ curl -H "Cookie: sid=SECRET-COOKIE-456; theme=dark" \
       -H "Authorization: Bearer SECRET-BEARER-789" \
       "http://127.0.0.1:8731/v1/tasks?access_token=SECRET-TOKEN-000&limit=5"
$ curl "http://127.0.0.1:8731/v1/health?signature=SECRET-SIG-111&quality=1080"

$ grep -ac "SECRET" <engine.log>
0
```

导出日志中的对应访问行（令牌被抹、非敏感参数保留）：

```
INFO:     127.0.0.1:14832 - "GET /v1/health?access_token=[REDACTED]&quality=720 HTTP/1.1" 200 OK
INFO:     127.0.0.1:14837 - "GET /v1/tasks?access_token=[REDACTED]&limit=5 HTTP/1.1" 200 OK
INFO:     127.0.0.1:14838 - "GET /v1/health?signature=[REDACTED]&quality=1080 HTTP/1.1" 200 OK
```

### 2.3 文本规则前后对照

| 原始日志行 | 脱敏结果 |
|---|---|
| `Cookie: sid=SECRET-COOKIE-456; theme=dark` | `Cookie: [REDACTED]` |
| `Authorization: Bearer SECRET-BEARER-789` | `Authorization: [REDACTED]` |
| `GET /v1/health?access_token=SECRET-TOKEN-000&quality=720` | `GET /v1/health?access_token=[REDACTED]&quality=720` |
| `proxy_password=SECRET-PASS-222 user=alice` | `proxy_password=[REDACTED] user=alice` |
| `GET /v1/probe?user_password=SECRET-PASS-333&quality=720` | `GET /v1/probe?user_password=[REDACTED]&quality=720` |
| `payload eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJTRUNSRVQifQ.c2lnbmF0dXJl` | `payload [REDACTED]` |
| `sniff_context_open task_id=01a19243-...` | 原样保留 |

### 2.4 验收中发现的缺陷与修复

取证时发现带前缀的敏感键（如 `proxy_password`、`user_password`）未被旧规则命中，值会原样进入日志。已修正 `_PAIR` 的起始边界并让 `_QUERY_PARAM` 支持键名前缀，新增 2 条回归用例：

```
test_prefixed_sensitive_keys_redacted PASSED
test_prefixed_sensitive_query_params_redacted PASSED
```

### 2.5 测试结果

`engine/test_log_redaction.py`：**18 条全过**（含 uvicorn 5 参数访问日志、异常栈、幂等、审计行不变）。另 `engine/tests_real/test_sniffer_real.py::test_audit_lines_do_not_leak_sensitive_data` 验证嗅探审计行不含 URL / Cookie / UA。

---

## 3. 验收标准 6：DRM 不下载

> PRD §9.6：对加密流返回明确的"不支持 DRM"错误码，不产生下载尝试。

### 3.1 实现对照

| 环节 | 行为 | 文件 |
|---|---|---|
| DRM 判定 | 按 manifest `EXT-X-KEY` 的 `KEYFORMAT` 三态判定：明文 / AES-128（非 DRM）/ Widevine（DRM） | `engine/services/formats.py` `detect_drm` |
| 下载入口 | `POST /download` 打印一次 `download_attempt` 审计行后，在调用下载逻辑之前抛 `DRM_PROTECTED`（422），不进入 `_do_download` | `engine/main.py` |
| 自动领取 | 带 `formatSelector` 的 DRM 任务不自动下载，停在 `awaiting_selection` | `engine/main.py` `_advance` |
| 错误载荷 | `code=DRM_PROTECTED` / `category=content` / `retryable=false` / `detail.drmSystem=widevine` | `engine/models.py` |

### 3.2 真实 API 取证（桩模式 DRM fixture，真实引擎进程）

| 步骤 | 实测 |
|---|---|
| `GET /v1/_debug/download-counter`（前） | `{"downloadCount":0}` |
| `POST /v1/tasks`（`/drm/` 源） | 201 `queued`，`taskId=01a19243-74e6-7fc4-afb2-aed49cf6cbe2` |
| `GET /v1/tasks/{id}` | `awaiting_selection` |
| `GET /v1/tasks/{id}/sniff-results` | `drmDetected=true`、`drmSystem="widevine"`、`mediaUrl=.../drm/master.m3u8` |
| `POST /v1/tasks/{id}/download`（`{"strategy":"best"}`） | **422**，载荷见下 |
| `GET /v1/_debug/download-counter`（后） | `{"downloadCount":0}` |
| `GET /v1/tasks/{id}`（拒绝后） | 仍为 `awaiting_selection` |
| 该任务 `download_attempt` 审计行 | **1**（入口审计行；下载逻辑零执行，计数器为 0 为硬证据） |

422 响应原文：

```json
{"error":{"code":"DRM_PROTECTED","category":"content","message":"该内容受 DRM 保护，不支持下载","retryable":false,"detail":{"drmSystem":"widevine"}}}
```

引擎日志：

```
2026-10-31 22:00:57,115 bgv.engine INFO download_attempt task_id=01a19243-74e6-7fc4-afb2-aed49cf6cbe2
INFO:     127.0.0.1:9351 - "POST /v1/tasks/01a19243-74e6-7fc4-afb2-aed49cf6cbe2/download HTTP/1.1" 422 Unprocessable Entity
```

### 3.3 测试结果

- `engine/test_drm.py::TestDrmZeroDownload`：`drmDetected=True` → 422 精确载荷；`download_attempt` 恰 1 次；`_do_download` spy 未被调用；计数器为 0。
- `engine/tests_real/test_stage3_units.py::test_drm_tristate`：`plain-hls → (False, None)`、`aes128-hls → (False, None)`、`widevine-hls → (True, "widevine")` 全过。

---

## 4. 偏差与限制

1. **DRM 取证使用合成 manifest 与桩 fixture**：未连接真实 Widevine 流（需要授权内容与密钥体系），验收依据是 manifest `KEYFORMAT` 判定 + 下载入口短路 + 计数器零增长。
2. **AES-128 不视为 DRM**：仅 `KEYFORMAT` 指向 Widevine 等 DRM 系统时拒绝（与 M1 三态裁决一致），AES-128 普通加密不拦截。
3. **日志取证范围**：覆盖引擎进程标准输出/错误（含 uvicorn 访问日志）；外部反向代理或系统日志不在引擎控制范围内。
4. **取证端口**：执行时 `127.0.0.1:8730` 被一个此前会话遗留的旧引擎进程占用，实测使用 `8731`；端点与日志逻辑相同。遗留进程已确认存在但未终止。
5. `download_attempt` 审计行为入口记录，DRM 场景预期为 **1 条**；"零下载"以 `downloadCount=0` 与 `_do_download` 未调用为准。

---

## 5. 复现命令

```powershell
cd engine

# 标准 2 / 5 / 6 的桩模式用例
.\.venv\Scripts\python.exe -m pytest test_cookies.py test_log_redaction.py test_drm.py -v

# 标准 2 / 6 的真实链路用例（需本机 ffmpeg + Playwright）
.\.venv\Scripts\python.exe -m pytest -m real tests_real/test_lazy_cookie_sniff.py tests_real/test_stage3_units.py -v

# 全量
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m pytest -q -m real
```

本次全量结果：桩模式 **143 passed**，真实链路 **79 passed**。

---

## 6. 结论

PRD §9 验收标准 **2、5、6 均通过**：

- **2**：普通页面零读取；被拒场景 1 次读取 / 1 次重试；原 Cookie 文件 mtime 不变。
- **5**：导出日志中敏感值检索为 0；带前缀键名的泄漏缺陷已修复并加回归用例。
- **6**：DRM 流返回 422 `DRM_PROTECTED`，下载计数器保持 0，任务停在 `awaiting_selection`。

M2 出口标准（`GET /v1/privacy/selfcheck` 可用 + 标准 2/5/6 通过）已全部满足。
