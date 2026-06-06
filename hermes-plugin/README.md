# Hermes MyBot Plugin

将 Hermes Agent 能力通过 WebSocket 暴露给自定义客户端（Android 语音助手等）。

## 快速安装

```bash
# 1. 复制插件到 Hermes 插件目录
cp -r hermes-plugin/mybot ~/.hermes/plugins/

# 2. 设置环境变量（允许所有用户访问）
echo "MYBOT_ALLOW_ALL_USERS=true" >> ~/.hermes/.env

# 3. 启用插件
hermes plugins enable mybot

# 4. 重启 Gateway
hermes gateway restart
```

## 配置

插件通过环境变量自动配置，无需修改 `config.yaml`：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYBOT_HOST` | `0.0.0.0` | WebSocket 监听地址 |
| `MYBOT_PORT` | `8643` | WebSocket 端口 |
| `MYBOT_API_KEY` | (无) | 可选 API Key |
| `MYBOT_ALLOW_ALL_USERS` | `false` | 必须设为 `true` |

## 协议

客户端通过 WebSocket 连接 `ws://host:8643/v1/ws?session_id=<uuid>`。

### 客户端 → 服务端

```json
{"type": "user_message", "text": "你好"}
{"type": "ping"}
{"type": "approval_response", "approved": true, "choice": "once"}
```

### 服务端 → 客户端

```json
{"type": "status", "text": "已连接 Hermes"}
{"type": "session_state", "state": "thinking|awaiting_approval|ready"}
{"type": "assistant_message", "text": "回复内容"}
{"type": "file", "name": "hello.txt", "url": "/v1/files/<uuid>", "size": 12, "mime": "text/plain"}
{"type": "tool_progress", "tool": "terminal", "status": "running", "label": "..."}
{"type": "approval_required", "command": "rm -rf /tmp", "description": "delete in root path"}
{"type": "approval_resolved", "approved": true}
{"type": "error", "text": "错误信息"}

// P2 新增卡片 (v2.0)
{"type": "step", "title": "任务进度", "steps": [{"label": "步骤1", "status": "done"}, ...]}
{"type": "suggestion", "title": "建议标题", "content": "建议内容"}
{"type": "error_card", "error": "错误描述", "retryable": true}
```

### approval_response 的 choice 值

| choice | 含义 |
|--------|------|
| `once` | 仅本次允许 |
| `session` | 本次会话都允许 |
| `always` | 永久允许 |

## 文件传输

### 文件发送（Hermes → 客户端）

Agent 通过 `write_file` 创建文件后，适配器自动检测响应中的文件路径（通过 `extract_local_files`）或 `MEDIA:` 标签（通过 `extract_media`），注册到本地文件注册表，然后通过 WebSocket 推送独立的 `file` 消息：

```json
{
  "type": "file",
  "name": "report.pdf",
  "url": "/v1/files/a1b2c3d4-...",
  "size": 125000,
  "mime": "application/pdf"
}
```

客户端收到后应：
1. 在 UI 中显示附件卡片（文件名 + 大小）
2. 用户点击下载 → HTTP GET `http://host:8643/v1/files/<uuid>`
3. 响应为直接文件流（`Content-Disposition: attachment`）

### 文件端点

| 端点 | 说明 |
|------|------|
| `GET /v1/files/{id}` | 下载文件 |
| `GET /v1/files/{id}/info` | 获取文件元信息（JSON） |

文件由 mybot 适配器本地注册和直供，不依赖 API Server (8642)。

### 文件接收（客户端 → Hermes）

客户端通过 HTTP POST 上传文件（未来支持）。

## 配合 Android App

Android 项目已内置 WebSocket 客户端（`HermesWebSocket.kt`），支持 `file` 消息类型的解析和附件卡片渲染。

设置界面可配置 WebSocket 和 HTTP 地址，支持 USB / Tailscale / 局域网三种网络模式。

USB 连接时需设置端口转发：

```bash
adb reverse tcp:8643 tcp:8643
```

Tailscale 组网方式：

```bash
# Windows 管理员 PowerShell:
New-NetFirewallRule -DisplayName "Hermes MyBot" -Direction Inbound -Protocol TCP -LocalPort 8643 -Action Allow
netsh interface portproxy add v4tov4 listenport=8643 listenaddress=<Tailscale IP> connectport=8643 connectaddress=localhost
```

## 与 Hermes 的升级兼容性

此插件为标准 Hermes 插件，存放在 `~/.hermes/plugins/` 下，不修改 Hermes 核心代码。

- `hermes update` → 无冲突
- `git pull` Hermes 源码 → 无冲突
- 插件发现 → 自动（`hermes plugins enable mybot`）

## 架构

```
Android App (WebSocket + HTTP)
    │
    ├── ws://host:8643/v1/ws         ← 对话、审批、文件推送
    └── http://host:8643/v1/files/   ← 文件下载
            │
    MyBotAdapter (aiohttp WebSocket server)
            │
    _message_handler → Hermes Gateway → Agent
            │
    extract_media / extract_local_files
            │
    _send_file_via_ws → file registry → web.FileResponse
```
