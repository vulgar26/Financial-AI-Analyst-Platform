# 60 秒演示流程（Docker Compose 版）

假设你的本机已安装并启动 Docker Desktop。

## 0. 准备环境变量

1. 复制模板：`copy .env.example .env`
2. 编辑 `.env`：至少填 `SPRING_AI_DASHSCOPE_API_KEY`，并保证 `APP_JWT_SECRET`、`POSTGRES_PASSWORD`（如需要）与示例一致。

## 1. 启动全套服务

在仓库根目录执行：

```powershell
docker compose up -d --build
```

## 2. 验收健康检查（匿名可访问）

```powershell
curl.exe http://localhost:8081/actuator/health
```

返回应包含 `{"status":"UP"}`。

## 3. 登录拿 Token（demo 账号）

```powershell
$resp = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/auth/login" `
  -ContentType "application/json" `
  -Body '{"username":"demo","password":"demo123"}'

$token = $resp.token
```

## 4. 上传一段知识（用于向量检索）

准备一个 `test.txt` 文件（任意几行文本）。

```powershell
curl.exe -X POST "http://localhost:8081/knowledge/upload" `
  -H "Authorization: Bearer $token" `
  -F "file=@test.txt"
```

成功时响应为 **JSON**（`ok`、`fileName`、`chunkCount`、`message`）；校验失败时为 `error` + `message`。

## 5. 发起 SSE 对话（流式输出）

**推荐（POST + JSON）**：`query` 放在请求体，避免 GET 查询串长度与访问日志暴露问题。

```powershell
curl.exe -N -X POST "http://localhost:8081/analysis/chat/demo-conv" `
  -H "Authorization: Bearer $token" `
  -H "Accept: text/event-stream" `
  -H "Content-Type: application/json" `
  --data-raw "{\"query\":\"请基于我上传的资料，总结这家公司的近期财报亮点、风险因素和需要继续核验的数据。\"}"
```

**兼容（GET，已弃用）**：服务端仍支持，但会返回 `Deprecation: true`；新脚本请改用上面的 POST。

```powershell
curl.exe -N -X GET "http://localhost:8081/analysis/chat/demo-conv?query=请基于我上传的资料，总结这家公司的近期财报亮点、风险因素和需要继续核验的数据。" `
  -H "Authorization: Bearer $token" `
  -H "Accept: text/event-stream"
```

终端会持续输出 SSE 行：流首可能含 **`event: plan_parse`** 与一行 **`data:`**（JSON 元数据），随后为引用与正文的 **`data:`** 行。`query` 长度受 `app.conversation.max-query-chars` 限制（默认 8192），超出返回 **400** JSON。
