# 有数 — 技术文档

## 架构

```
手机 sherpa-onnx → ASR 识别 → WebSocket → Hermes mybot 插件 → Agent
    (离线毫秒级)      (确认后发送)      |                     (工具+审批)
                                        |
                        HTTP GET /v1/files/ ← 文件下载
```

## 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| UI | Jetpack Compose + Material3 | BOM 2025.01.00 |
| ASR | sherpa-onnx 1.13.2 + SenseVoice (2025.09) | 离线中英日韩粤5语识别，226MB int8，支持情感识别 |
| 网络 | OkHttp WebSocket | 实时双向通信，自动重连 |
| 数据库 | Room (SQLite) | 会话 + 消息持久化 |
| Markdown | jeziellago/compose-markdown 0.7.2 | 纯 AndroidX，支持表格/代码块 |
| TTS | CosyVoice2 HTTP 代理 | 云端合成，设置页开关，中文女声 |

## 项目结构

```
voice-assistant-android/
├── app/
│   ├── build.gradle.kts
│   ├── libs/                     ← sherpa-onnx AAR
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── jniLibs/              ← sherpa-onnx native libs
│       ├── assets/               ← ASR 模型文件
│       └── java/com/example/voiceassistant/
          ├── MainActivity.kt           ← WebSocket 管理、会话切换、模式切换、备份/恢复
          ├── ui/
          │   ├── MainScreen.kt         ← 主界面、审批卡片、Clarify 卡片、发送状态、Markdown
          │   ├── SettingsScreen.kt     ← 设置界面、AppSettings 持久化、备份/恢复按钮
          │   ├── ClarifyCard.kt        ← Clarify 交互卡片
          │   ├── BackupUtil.kt         ← 全量备份/恢复 (.sagebackup)
│           ├── network/
│           │   ├── HermesWebSocket.kt    ← WebSocket 客户端、协议解析
│           │   └── LlmClient.kt          ← （废弃）原 LLM HTTP 客户端
│           ├── data/                     ← Room 数据库层
│           │   ├── AppDatabase.kt
│           │   ├── SessionEntity.kt / SessionDao.kt / SessionRepository.kt
│           │   └── MessageEntity.kt / MessageDao.kt / MessageRepository.kt
│           └── service/
│               ├── VoiceRecognitionEngine.kt  ← sherpa-onnx ASR 引擎
│               └── VoiceService.kt
├── build.gradle.kts
├── settings.gradle.kts
├── local.properties
├── hermes-plugin/              ← Hermes MyBot 插件
│   ├── README.md
│   └── mybot/
│       ├── plugin.yaml
│       ├── __init__.py
│       └── adapter.py          ← WebSocket 服务器 + 文件注册/推送/下载
├── README.md                   ← 产品愿景与路线图
└── DEVELOPMENT.md              ← 本文档
```

## 通信协议 (WebSocket JSON)

### 发送 (→ Hermes)

```json
// 用户消息（支持能力选择）
{"type":"user_message", "text":"...", "session_id":"...", "selectedSkills":["帮我写作"]}

// 审批响应
{"type":"approval_response", "approved":true, "choice":"once|session|always"}

// Clarify 响应
{"type":"clarify_response", "clarify_id":"...", "text":"选项文字"}
```

### 接收 (← Hermes)

| type | 说明 |
|------|------|
| `assistant_message` | AI 回复 + 附件列表 |
| `tool_progress` | 工具执行进度 |
| `approval_required` | 危险命令请求确认 |
| `approval_resolved` | 审批结果 |
| `session_state` | `thinking` / `awaiting_approval` / `ready` |
| `file` | 文件推送通知 |
| `clarify` | 交互式提问（含可选列表） |
| `clarify_resolved` | Clarify 已解决 |
| `step` | 多步骤进度卡（标题 + 步骤列表） |
| `suggestion` | 可折叠建议卡（标题 + 内容） |
| `error_card` | 可重试错误卡（错误文本 + retryable 标志） |
| `status` | 状态信息 |
| `error` | 错误信息 |

### 新增卡片消息格式

**StepCard:**
```json
{"type": "step", "title": "任务标题", "steps": [
  {"label": "步骤描述", "status": "done|running|waiting|error"}
]}
```

**SuggestionCard:**
```json
{"type": "suggestion", "title": "建议标题", "content": "建议详细内容"}
```

**ErrorRetryCard:**
```json
{"type": "error_card", "error": "错误描述", "retryable": true}
```

### 测试卡片
adapter 支持 `test_card` 消息批量广播卡片：
```json
{"type": "test_card", "card": "step|suggestion|error_card|all"}
```
详见 `skills/software-development/card-test/SKILL.md`。

## Hermes 后端部署

有数通过 [Hermes MyBot 插件](hermes-plugin/README.md) 连接 Hermes Agent。

### 安装插件

```bash
cp -r hermes-plugin/mybot ~/.hermes/plugins/
echo "MYBOT_ALLOW_ALL_USERS=true" >> ~/.hermes/.env
echo "GATEWAY_ALLOW_ALL_USERS=true" >> ~/.hermes/.env
hermes plugins enable mybot
hermes gateway restart
```

### 网络模式

手机通过 **frp 公网穿透** 连接 Hermes，配置在 App 设置页：

| Profile | WebSocket 地址 | 说明 |
|---------|---------------|------|
| 工作 | `ws://your-server-ip:8643` | frp 隧道 → 工作环境 Hermes |
| 家里 | `ws://your-server-ip:8644` | frp 隧道 → 家里环境 Hermes |

App 设置页有 Profile 切换，数据和会话完全隔离。frp 配置见 Hermes 服务器端 `frpc.ini`。

### 文件传输

Agent 通过 `write_file` 创建文件后，mybot 适配器自动：
1. 提取文件路径 → 注册到本地文件表
2. WebSocket 推送 `{"type":"file", "name":"...", "url":"/v1/files/<uuid>", ...}`
3. 客户端显示附件卡片 → 点击下载 → HTTP GET 即返回文件

详见 [hermes-plugin/README.md](hermes-plugin/README.md)。

## 技能 Chips（能力标签）

聊天窗口中的技能 chips 来自 **Skill Manager** 独立服务。

### 数据流

```
Skill Manager (8888)  →  mybot 代理 (/v1/capabilities)  →  手机 App HTTP GET
     Flask REST API             aiohttp 薄转发               刷新/切 Profile 时拉取
     + /v1/formats 格式路由      + /v1/capabilities/{name}/skills   (仅加载 capability 列表)
```

### 完整链路（能力 → 技能 → 执行）

```
App 选"帮我写作" Chip
      ↓ 发送 user_message {selectedSkills: ["帮我写作"]}
mybot adapter
      ↓ 查 Skill Manager GET /v1/capabilities/帮我写作/skills
      ↓ → 拿到 ["writing-assistant"]
      ↓ 注入指令: "请用 skill_view 加载: writing-assistant"
Hermes Agent
      ↓ 加载 writing-assistant 技能
writing-assistant
      ↓ curl Skill Manager GET /v1/capabilities/帮我写作/formats
      ↓ → 拿到 [{ppt→powerpoint}, {pdf→nano-pdf}, ...]
      ↓ clarify("什么格式？", ["PPT", "PDF", "Word", "Markdown"])
用户选 📊 PPT
      ↓ skill_view("powerpoint")
powerpoint 技能
      ↓ 生成 .pptx
      ↓ MEDIA:/path/file.pptx → 发给用户
```

### 格式路由管理

格式配置不在技能代码中硬编码，全部在 Skill Manager 管理：

```bash
# 查看格式
curl http://127.0.0.1:8888/v1/capabilities/帮我写作/formats

# 修改格式（增删改）
curl -X PUT http://127.0.0.1:8888/v1/capabilities/帮我写作/formats \
  -H "Content-Type: application/json" \
  -d '{"formats": [
    {"key":"ppt","label":"📊 PPT","skill":"powerpoint"},
    {"key":"pdf","label":"📄 PDF","skill":"nano-pdf"}
  ]}'
```

`skill: null` 的格式表示不需要加载额外技能（如纯文本写作）。

### App 加载时机

- App 启动时自动加载
- 切换 Profile 时重新加载
- 通过 `NetworkUtils.loadCapabilities()` 调用 `/v1/capabilities?profile=<当前profile>`

### 显示位置

- **聊天页**：输入框上方 `LazyRow` 横滚条，点选高亮（蓝色背景），发消息时携带已选技能名
- **设置页**：能力开关列表，可单独开启/关闭

### 数据格式

```json
// GET /v1/capabilities → Skill Manager 返回
{"capabilities": [
  {"name": "代码助手", "icon": "💻", "description": "写代码、Debug..."},
  {"name": "帮我写作", "icon": "✍️", "description": "写文章、报告..."},
  {"name": "知识查询", "icon": "📚", "description": "查询知识库..."}
]}
```

### 故障排查

技能 chips 不显示时，按以下顺序排查：

1. **Skill Manager 是否运行**：`curl --noproxy '*' http://127.0.0.1:8888/health`
2. **mybot 代理是否正常**：`curl http://your-server-ip:8643/v1/capabilities`（通过 frp 公网地址访问）
3. **依赖是否安装**：`python3 -c "import flask"`，若报错则 `uv pip install flask`
4. **启动服务**：`cd ~/skill-manager && python3 server.py`（后台运行）
5. **手机端**：重启 App 或切换 Profile 触发重新加载

## 构建

### 1. 下载 sherpa-onnx

```bash
# JNI 库（放到 app/src/main/jniLibs/）
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-v1.13.2-android.tar.bz2
tar xvf sherpa-onnx-v1.13.2-android.tar.bz2
cp -r jniLibs/* app/src/main/jniLibs/

# AAR 包（放到 app/libs/）
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar
mkdir -p app/libs && mv sherpa-onnx-1.13.2.aar app/libs/
```

### 2. 下载 ASR 模型

```bash
# SenseVoiceSmall（当前使用，中英日韩粤5语识别，226MB）
# 从 HuggingFace 下载:
# https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09
mkdir -p app/src/main/assets/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09
# 下载 model.int8.onnx + tokens.txt 到上述目录
```

#### 已跑通的模型

| 模型 | 日期 | 大小 | 架构 | 状态 |
|:---|:---|:---:|:---|:---:|
| Paraformer zh-small int8 | 2024-03 | 79 MB | Paraformer | ✅ 初始版本 |
| Zipformer CTC small zh int8 | 2025-07 | 60 MB | Zipformer CTC | ✅ 已验证 |
| Zipformer CTC zh int8（全量） | 2025-07 | 350 MB | Zipformer CTC | ⚠️ 纯中文，中英混说<unk> |
| **SenseVoiceSmall int8（当前）** | **2025-09** | **226 MB** | **SenseVoice** | **✅ 中英混说正常 + 情感** |

#### SDK 支持但未验证的模型（sherpa-onnx 1.13.2）

| 模型 | 大小估算 | 特性 |
|:---|:---:|:---|
| SenseVoiceSmall int8 | ~226 MB | ASR + 情感识别 + 音频事件 + 5语言 |
| Paraformer zh int8 | ~227 MB | 全量 Paraformer |
| Qwen3-ASR 0.6B int8 | ~936 MB | Qwen LLM 架构，精度最高但体积大 |

> 切换模型只需：替换 `app/src/main/assets/` 下的模型文件 + 修改 `VoiceRecognitionEngine.kt` 中 `MODEL_DIR` 和对应的 config 类。同架构（如 Zipformer CTC）之间切换甚至只需要换 `model.int8.onnx` 文件。

```bash
# Silero VAD 模型（629KB）
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
mv silero_vad.onnx app/src/main/assets/
```

### 3. 配置 SDK 路径

```bash
echo "sdk.dir=/mnt/c/Users/$(whoami)/AppData/Local/Android/Sdk" > local.properties
```

### 4. 构建

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 5. 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 更新日志

### 2026-06-06 — 能力路由 + 格式引擎
- **能力→技能自动加载**：App 选中 Chip → WebSocket 携带 `selectedSkills` → mybot adapter 查 Skill Manager 映射 → 自动注入 `skill_view` 加载指令
- **格式路由表**：Skill Manager 新增 `capability_format_skills` 表 + `GET/PUT /v1/capabilities/{name}/formats` API
- **writing-assistant 技能**：编排者技能，查 Skill Manager 格式 API → clarify 选格式 → 按需加载子技能（powerpoint/nano-pdf/humanizer）
- **网络文档更新**：USB/Tailscale/局域网 → frp 公网穿透（实际部署方式）
- **协议更新**：`user_message` 新增 `selectedSkills` 字段
- **发送修复**：录音中校正后点发送，使用编辑后的 `inputText` 而非原始 `liveText`

### 2026-06-06 — ASR 训练流水线
- **asr-trainer 项目**：独立流水线项目（校对/微调/导出），加入 P3 路线图
- **训练数据自动上传**：发消息时上传 WAV+文字到 Hermes `~/.hermes/training/`
- **模型演进**：Paraformer zh-small (79MB) → Zipformer CTC small (60MB, 更快更准) → 全量 Zipformer CTC (350MB, 精度最高) 测试中
- **SDK 能力**：sherpa-onnx 1.13.2 支持 SenseVoiceSmall / Paraformer zh / Qwen3-ASR 等，按需切换
- **文档更新**：DEVELOPMENT.md 加入模型列表、切换说明

### 2026-06-06 — 交互细节打磨
- **CosyVoice2 TTS 集成**：HTTP 代理播放，设置页开关控制
- **模式下拉**：顶栏模式标签改为可点击下拉，三种模式一键切换，从 ⋮ 菜单中移除
- **录音即发**：录音中点发送按钮自动停录+发送，识别有误可先停录再修改
- **连接状态简化**：已连接时只显示绿点不显示文字，异常状态才显标签
- **状态文字精简**：去掉所有"Hermes"字样和服务端状态过滤
- **仪表盘导航修复**：设置→仪表盘不再错误跳转到会话列表
- **仪表盘返回按钮**：改为箭头图标，与聊天页风格统一
- **启动崩溃修复**：`ttsEnabled` 初始化从构造期移到 `onCreate`
- **服务端修复**：CosyVoice API 兼容（generator 处理、音色名、WAV 编码、HTTP 头），mybot TTS 代理 URL 修正

### 2026-06-05 — 顶栏 & 设置页优化
- **顶栏精简**：去掉设置⚙️图标、Profile标签、搜索🔍图标、会话下拉菜单
- **模式标签**：`教新技能`→`技能`，`记忆管理`→`记忆`，显示在顶栏右侧 `[💬普通]`
- **搜索+模式切换**：收进 `[...]` 菜单
- **会话标题**：`fontSize=15sp` + `maxLines=1` + `TextOverflow.Ellipsis`，长标题自适应
- **输入框键盘适配**：移到 `Scaffold.bottomBar` + `imePadding()` + `adjustResize`，键盘弹起不遮挡
- **设置页 Profile**：竖排全宽大卡片（36sp emoji + 描述文字），选中卡片蓝色边框+[当前]标签+✓
- **Profile 匹配修复**：`MainActivity` 传给 `SettingsScreen` 用 raw key `"work"/"home"` 而非显示名
- **返回按钮统一**：设置页改为 `←` 图标按钮，与聊天页一致

### 2026-06-05 — Skill Manager 修复
- 手机端技能 chips 不显示，排查后发现 Flask 未安装，Skill Manager 启动失败
- `uv pip install flask` 安装依赖，后台重启服务后恢复
