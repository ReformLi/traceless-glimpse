#!/usr/bin/env bash
# Traceless Glimpse M0 smoke（联调合同 §9.1–§9.4 + 偏差 2 端到端 + §10 DRM 零下载）
#
# 用法（Git Bash）：
#   BASE=http://127.0.0.1:8730 \
#   ENGINE_LOG=<引擎控制台日志路径，正斜杠> \
#   bash scripts/smoke.sh
#
# 注意：脚本读取 /v1/_debug/download-counter，启动引擎时必须设置
#   BGV_ENABLE_DEBUG_ENDPOINTS=1
# 否则该端点返回 404（M1 起默认关闭）。
#
# 依赖：bash + curl.exe + engine/.venv（仅用于从 JSON 取值，不依赖 jq）。
# 退出码：0 全绿；非 0 为失败用例数。

set -u

BASE="${BASE:-http://127.0.0.1:8730}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PYTHON:-$ROOT/engine/.venv/Scripts/python.exe}"
ENGINE_LOG="${ENGINE_LOG:-}"
TMP_BODY="$(mktemp)"

PASS=0
FAIL=0
TOTAL=0

# ---------------------------------------------------------------- 基础工具

http() { # http METHOD PATH [JSON]
  local method="$1" path="$2" data="${3-}"
  if [ -n "${data:-}" ]; then
    STATUS=$(curl.exe -sS -o "$TMP_BODY" -w '%{http_code}' -X "$method" "$BASE$path" \
      -H 'Content-Type: application/json' -d "$data")
  else
    STATUS=$(curl.exe -sS -o "$TMP_BODY" -w '%{http_code}' -X "$method" "$BASE$path")
  fi
  BODY=$(cat "$TMP_BODY")
}

jget() { # jget dotted.path —— 缺失键返回 <MISSING>，JSON null 返回 None
  "$PYTHON" -c '
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
try:
    for key in sys.argv[2].split("."):
        data = data[int(key)] if isinstance(data, list) else data[key]
    print(data)
except Exception:
    print("<MISSING>")
' "$TMP_BODY" "$1"
}

body_count() { # 当前响应体里某子串出现次数
  printf '%s' "$BODY" | grep -c "$1"
}

log_count() { # 引擎日志中某子串出现的行数
  if [ -z "$ENGINE_LOG" ]; then echo "0"; return; fi
  grep -c "$1" "$ENGINE_LOG" 2>/dev/null
}

check() { # check 描述 期望 实际
  TOTAL=$((TOTAL + 1))
  if [ "$3" = "$2" ]; then
    PASS=$((PASS + 1))
    printf 'PASS  %-52s %s\n' "$1" "$2"
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL  %-52s expect=%s actual=%s\n' "$1" "$2" "$3"
  fi
}

section() { printf '\n== %s ==\n' "$1"; }

# ---------------------------------------------------------------- 0. 健康检查

section "0. GET /v1/health（§6.6）"
http GET /v1/health
check "health http 状态" 200 "$STATUS"
check "health protocolVersion" 0.1 "$(jget protocolVersion)"
if [ -z "$ENGINE_LOG" ]; then
  echo "WARN  ENGINE_LOG 未设置：日志类断言将全部按 0 计（仅供本地快速跑）"
fi

# ---------------------------------------------------------------- 1. §9.2 SSRF

section "1. §9.2 安全拒绝：SSRF"
http POST /v1/tasks '{"sourceUrl":"http://127.0.0.1/admin","mode":"sniff","cookiePolicy":"none","outputDir":"/home/user/Videos/BGV"}'
check "SSRF http 状态" 400 "$STATUS"
check "SSRF code" SSRF_BLOCKED "$(jget error.code)"
check "SSRF category" security "$(jget error.category)"
check "SSRF retryable" False "$(jget error.retryable)"
check "SSRF message" "目标地址指向受限网络，已拒绝" "$(jget error.message)"
check "SSRF detail.host" 127.0.0.1 "$(jget error.detail.host)"

# ---------------------------------------------------------------- 2. §9.4 输出目录

section "2. §9.4 输出目录拒绝"
http POST /v1/tasks '{"sourceUrl":"https://example.com/v","mode":"sniff","cookiePolicy":"none","outputDir":"/"}'
check "OUTPUT_DIR http 状态" 400 "$STATUS"
check "OUTPUT_DIR code" OUTPUT_DIR_REJECTED "$(jget error.code)"
check "OUTPUT_DIR category" security "$(jget error.category)"

# ---------------------------------------------------------------- 3. §9.3 DRM 零下载

section "3. §9.3 内容拒绝：DRM（零下载硬证据）"
http POST /v1/tasks '{"sourceUrl":"https://example.com/drm/watch?v=xyz","mode":"download","cookiePolicy":"lazy","outputDir":"/home/user/Videos/BGV"}'
check "DRM 任务创建 201" 201 "$STATUS"
DRM_ID="$(jget id)"
check "DRM 任务初始状态" queued "$(jget status)"

http GET "/v1/tasks/$DRM_ID"
check "DRM 任务惰性推进" awaiting_selection "$(jget status)"

http GET /v1/_debug/download-counter
COUNTER_BEFORE="$(jget downloadCount)"
ATTEMPT_BEFORE="$(log_count 'download_attempt')"
check "DRM 前下载计数器" 0 "$COUNTER_BEFORE"

http POST "/v1/tasks/$DRM_ID/download" '{"formatSelector":{"strategy":"best"}}'
check "DRM http 状态" 422 "$STATUS"
check "DRM code" DRM_PROTECTED "$(jget error.code)"
check "DRM category" content "$(jget error.category)"
check "DRM retryable" False "$(jget error.retryable)"
check "DRM message" "该内容受 DRM 保护，不支持下载" "$(jget error.message)"
check "DRM detail.drmSystem" widevine "$(jget error.detail.drmSystem)"

ATTEMPT_AFTER="$(log_count 'download_attempt')"
check "DRM 新增 download_attempt 审计行数" 1 "$((ATTEMPT_AFTER - ATTEMPT_BEFORE))"
check "DRM 审计行携带 task_id 出现次数" 1 "$(log_count "download_attempt task_id=$DRM_ID")"

http GET /v1/_debug/download-counter
COUNTER_AFTER="$(jget downloadCount)"
check "DRM 后下载计数器（零下载）" 0 "$COUNTER_AFTER"

http GET "/v1/tasks/$DRM_ID"
check "DRM 任务拒绝后仍为 awaiting_selection" awaiting_selection "$(jget status)"

# ---------------------------------------------------------------- 4. §9.1 成功链路

section "4. §9.1 成功链路"
http POST /v1/tasks '{"sourceUrl":"https://example.com/watch?v=abc","mode":"download","cookiePolicy":"lazy","outputDir":"/home/user/Videos/BGV"}'
check "成功链路创建 201" 201 "$STATUS"
TASK_ID="$(jget id)"
check "成功链路初始状态" queued "$(jget status)"
check "Task 响应体无 cookieFile 字段" 0 "$(body_count '"cookieFile"')"
check "Task 响应体无 browser 字段" 0 "$(body_count '"browser"')"

http GET "/v1/tasks/$TASK_ID"
check "轮询状态" awaiting_selection "$(jget status)"

http GET "/v1/tasks/$TASK_ID/formats"
check "formats http 状态" 200 "$STATUS"
check "formats 含 137+140" 1 "$(body_count '137+140')"

http POST "/v1/tasks/$TASK_ID/download" '{"formatSelector":{"strategy":"exact","formatId":"137+140"}}'
check "download 202" 202 "$STATUS"
check "download 后状态" downloading "$(jget status)"

http POST "/v1/tasks/$TASK_ID/download" '{"formatSelector":{"strategy":"exact","formatId":"137+140"}}'
check "同 formatId 重复调用幂等 202（§2.4）" 202 "$STATUS"

http POST "/v1/tasks/$TASK_ID/download" '{"formatSelector":{"strategy":"exact","formatId":"140"}}'
check "不同 formatId 冲突 409（§2.4/§4）" 409 "$STATUS"
check "冲突 code" TASK_STATE_CONFLICT "$(jget error.code)"

http GET "/v1/tasks/$TASK_ID"
check "终态状态" done "$(jget status)"
check "终态 percent" 100.0 "$(jget progress.percent)"
check "终态 error" None "$(jget error)"

http POST "/v1/tasks/$TASK_ID/download" '{"formatSelector":{"strategy":"exact","formatId":"137+140"}}'
check "终态再 POST /download 409（§4）" 409 "$STATUS"

# ---------------------------------------------------- 5. 偏差 2 修复端到端（无 POST /download）

section "5. 带 formatSelector 创建 → 惰性到 done，全程无 POST /download"
echo "NOTE  本段只发 POST /v1/tasks + GET，不存在任何 POST /download 请求"
http POST /v1/tasks '{"sourceUrl":"https://example.com/watch?v=auto","mode":"download","cookiePolicy":"lazy","outputDir":"/home/user/Videos/BGV","formatSelector":{"strategy":"max_height","maxHeight":1080}}'
check "带 selector 创建 201" 201 "$STATUS"
AUTO_ID="$(jget id)"
echo "      AUTO_TASK_ID=$AUTO_ID"
check "带 selector 初始状态" queued "$(jget status)"

http GET "/v1/tasks/$AUTO_ID"
check "首次 GET 直接 downloading（引擎自动领取）" downloading "$(jget status)"

http GET "/v1/tasks/$AUTO_ID"
check "再次 GET 到 done" done "$(jget status)"
check "done percent" 100.0 "$(jget progress.percent)"

check "该任务被 POST /download 的次数（日志）" 0 "$(log_count "POST /v1/tasks/$AUTO_ID/download")"
check "该任务 download_attempt 审计行数（引擎自动领取 1 次）" 1 "$(log_count "download_attempt task_id=$AUTO_ID")"

# ---------------------------------------------------------------- 6. 新增错误码冒烟

section "6. 新增错误码冒烟（阶段 4 裁决：NOT_FOUND / VALIDATION_ERROR）"
http GET /v1/tasks/0192f3a4-0000-7000-8000-000000000000
check "未知任务 404" 404 "$STATUS"
check "未知任务 code" NOT_FOUND "$(jget error.code)"
check "NOT_FOUND retryable" False "$(jget error.retryable)"

http POST /v1/tasks '{"sourceUrl":"https://example.com/watch?v=abc","cookiePolicy":"lazy","outputDir":"/home/user/Videos/BGV"}'
check "缺 mode → 422" 422 "$STATUS"
check "缺 mode code" VALIDATION_ERROR "$(jget error.code)"
check "缺 mode category" input "$(jget error.category)"

# ---------------------------------------------------------------- 汇总

section "SUMMARY"
http GET /v1/_debug/download-counter
echo "checks: PASS=$PASS FAIL=$FAIL TOTAL=$TOTAL"
echo "downloadCounter(final)=$(jget downloadCount)（仅成功链路/自动领取递增，DRM 段恒为 0）"
if [ "$FAIL" -ne 0 ]; then
  echo "RESULT: FAIL"
  exit "$FAIL"
fi
echo "RESULT: ALL GREEN"
exit 0
