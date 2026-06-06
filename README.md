# Hermes Pocket · 有数 — 你的数据，你的智者

> **Hermes Pocket** — Hermes Agent 的 Android 口袋客户端。**有数 / Tomo — 心里有数，数在手中。**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9%2B-purple.svg)](https://kotlinlang.org)
[![Hermes](https://img.shields.io/badge/Hermes-Agent-orange.svg)](https://hermes-agent.nousresearch.com)

**Hermes Pocket** 是 [Hermes Agent](https://hermes-agent.nousresearch.com) 的本地优先 Android 客户端——口袋里的赫尔墨斯。App 名称「有数」，后续可接入任意兼容的 Agent 后端。

与 QQ/微信/Telegram 等平台网关不同，有数将一切数据留存在用户手机本地 —— 对话记录、语音输入、AI 生成的文件、图片，全部归你所有，可随时查看、搜索、导出、删除。

## 截图

|<img src="docs/screenshots/01-main-chat.png" width="280">|<img src="docs/screenshots/02-main-chat.jpg" width="280">|
|---|---|
|主聊天界面|聊天界面（深色模式）|

## 愿景

当前 AI 助手的悖论：你用它们越多，它们采集你的数据越多——但这些数据被用来训练的是**别人的模型**，你无法从中受益。

**有数改变了这一点：你的数据，训练你的模型。**

```
别人的 AI                           有数
───────────                        ──────────
你的数据 ─→ 他们的服务器            你的数据 ─→ 留在你的手机
          ─→ 训练他们的模型                    ─→ 校对 ─→ 微调
          ─→ 你得不到更好体验                  ─→ 你的模型越来越懂你
```

| 原则 | 含义 |
|------|------|
| **隐私优先** | 录音本地处理，对话存手机，数据不经过第三方 |
| **数据即资产** | 每一次使用都在积累训练数据，日积月累形成你专属的数据集 |
| **自我进化** | ASR 模型随使用次数增多持续微调，越用越准；Agent 行为随记忆积累越用越贴切 |
| **用户可控** | 随时查看、搜索、导出、删除自己的数据。全量备份一键完成 |
| **端到端直达** | 手机通过 frp 公网穿透直连 Hermes，不经过任何平台服务器 |
| **离线能力** | 核心功能（ASR）不依赖网络 |

## 产品特点

| 特点 | 说明 |
|------|------|
| 🏠 **数据主权** | 对话、语音、文件全存手机。随时查看、搜索、导出、删除——你拥有你的数据。 |
| 🧭 **意图路由** | 你说「帮我写作」，不选「powerpoint」。用户意图 → Skill Manager 映射 → Hermes 技能，两层翻译让技术细节对用户不可见。同一个能力支持多种输出格式，选格式后自动路由到对应技能。 |
| 🧬 **自我进化** | 每一条录音都在积累训练数据。ASR 模型随使用微调，越用越准；Agent 记忆越积越丰富，越用越贴切。 |
| 📱 **手机原生** | 不是网页套壳。GPS、日历、闹钟、通讯录、短信等 16 个手机工具深度集成，Agent 可以读你的位置、查你的日程、帮你发短信。 |
| 🔗 **Agent 无关** | 第一个适配的是 Hermes Agent，但架构设计和后端解耦——接入其他 Agent 框架只需换适配器。 |
| 🎯 **零平台依赖** | frp 公网穿透直连你自己的服务器，不经过任何第三方平台。没有厂商锁定，没有数据收割。 |

## 架构

```
手机 App (有数) ╌╌ frp ╌╌ Hermes Gateway (8643/8644) ╌╌ Skill Manager (8888)
─────────────              ───────────────────────        ──────────────────
Jetpack Compose     ←WS→   mybot 适配器 (能力→技能) ←HTTP→ Flask REST API
Room SQLite                 WebSocket Server               SQLite DB
sherpa-onnx ASR             + /v1/capabilities 代理        capabilities.db
                            + /v1/formats 查询             + format_routing
```

- **手机 ↔ Hermes**: 通过 frp 公网穿透，配置文件在设置页修改（默认 `ws://your-server-ip:8643`）
- **Hermes ↔ Skill Manager**: 内网 localhost:8888，不对外暴露
- **能力路由**: 能力 Chip → adapter 查 Skill Manager 映射 → 自动加载技能 → 按需 clarify 选格式 → 路由子技能
- **详见**: 设置页可配置所有连接参数

## 安装

无论哪种方式，你都需要在手机上安装有数 APK。可以下载预编译的 APK，也可以自行构建。

### 方式一：下载 APK（推荐）

1. **下载 APK**  
   从百度网盘（链接待更新）下载最新版 APK

2. **安装到手机**  
   将 APK 传到手机，点击安装（需允许「未知来源」安装）

3. **配置服务器地址**  
   打开 App → 设置 → 填入你的 Hermes 服务器地址：
   - WebSocket 地址：`ws://你的服务器IP:8643`
   - HTTP 地址：`http://你的服务器IP:8643`

4. **开始使用**  
   返回聊天页，看到绿色连接点即表示已连接。试说一句话或打字发消息。

> 🧪 **体验服务器**：我们计划提供公共体验服务器，无需自建即可试用。敬请期待。
>
> 💡 如果你还没有自己的 Hermes 服务器，见下方[方式三](#方式三自建-hermes-服务器推荐开发者)自建。完成后回到这里配置地址即可。

### 方式二：自行构建 APK

```bash
git clone https://github.com/netment/hermes-pocket.git
cd hermes-pocket
# 用 Android Studio 打开项目，或命令行构建：
./gradlew assembleDebug
# APK 路径：app/build/outputs/apk/debug/app-debug.apk
```

构建完成后回到[方式一](#方式一下载-apk推荐)继续安装和配置。

### 方式三：自建 Hermes 服务器（推荐开发者）

#### 1. 安装 Hermes Agent

```bash
# 一键安装
curl -fsSL https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh | bash

# 配置模型和 API Key
hermes setup
```

#### 2. 安装 mybot 插件（WebSocket 服务，手机连接必需）

```bash
# 复制插件到 Hermes 插件目录
cp -r hermes-plugin/mybot ~/.hermes/plugins/

# 设置环境变量
echo "MYBOT_ALLOW_ALL_USERS=true" >> ~/.hermes/.env

# 启用插件
hermes plugins enable mybot

# 重启 Gateway
hermes gateway restart
```

#### 3. （可选）安装 Skill Manager

```bash
git clone https://github.com/netment/skill-manager.git ~/skill-manager
cd ~/skill-manager
pip install -r requirements.txt
python server.py &   # 后台运行，默认 8888 端口
```

#### 4. 配置 frp 公网穿透

让你的手机随时随地连接 Hermes：

```bash
# 服务器端 frps.ini
[common]
bind_port = 7000

# 客户端 frpc.ini
[hermes-pocket]
type = tcp
local_ip = 127.0.0.1
local_port = 8643
remote_port = 8643
```

> 📖 详细 frp 配置见 [frp 文档](https://github.com/fatedier/frp)

## 能力路由：用户视角 → 技术技能

有数区别于普通聊天客户端的核心设计：**你的意图不直接对应一个技能，而是经过两层映射**。

```
用户说 "帮我写作"           Hermes 加载对应技能
      │                            ▲
      ▼                            │
┌──────────────┐          ┌─────────────────┐
│  用户视角      │  映射    │  技术视角         │
│  能力 Chip    │ ──────→ │  SKILL.md       │
│  (Capability) │          │  (Hermes Skill) │
└──────────────┘          └─────────────────┘
      │                            ▲
      │  Skill Manager             │
      │  capability_skills 表       │
      └────────────────────────────┘
```

### 为什么需要这一层？

| 问题 | 直接映射 | 有数的做法 |
|------|---------|-----------|
| 用户不理解技能名 | 选 `powerpoint` Chip | 选「帮我写作」Chip，不说技术术语 |
| 同一能力多种输出 | 需要记住 4 个技能 | 选「帮我写作」→ clarify 选格式 → 自动路由 |
| 新格式扩展 | 改 App 代码 + 加技能 | Skill Manager PUT API 一条命令 |
| 多 Agent 后端 | 每个后端改 Chip 列表 | 换 Skill Manager 数据库即可 |

### 实际案例：「帮我写作」

```
用户视角                                    Hermes 技术视角
────────                                   ────────────────
                                          ┌─ PowerPoint → hermes:powerpoint
帮我写作 ─→ Skill Manager ─→ clarify 格式 ─┼─ PDF       → hermes:claude-design
          capability_skills   选格式        ─┼─ Word      → hermes:claude-design
          writing-assistant                ─└─ Markdown  → hermes:baoyu-infographic
```

- **Skill Manager 数据库** 管理 `capability_skills`（能力→编排技能）和 `capability_format_skills`（能力→格式→子技能）
- **手机端** 只需展示用户能理解的 Chip 名称，其他全部由后端决策
- **扩展新格式** 无需改 App：`PUT /v1/capabilities/帮我写作/formats` 加一行

### 现有能力映射

| 用户看到 | 能力 ID | 编排技能 | 支持格式 |
|---------|--------|---------|---------|
| 💬 通用助手 | `chat` | *(直接对话，不加载技能)* | — |
| ✍️ 帮我写作 | `writing` | `writing-assistant` | PowerPoint / PDF / Word / Markdown |
| 💻 代码助手 | `coding` | `codex` + `claude-code` | — |
| 📚 知识查询 | `research` | `web_search` + `arxiv` | — |
| 🛠️ 手机工具 | `phone-tools` | *(手机端内置 16 个工具)* | GPS / 电池 / 日历 / 闹钟 等 |

> 💡 **设计理念**：用户只关心「我想做什么」，不关心「用什么技能实现」。Skill Manager 是用户语言到技术语言的翻译层。

## 产品路线图

### ✅ 已实现

- 🎤 离线语音识别 (sherpa-onnx SenseVoice + Silero VAD)
- 💬 实时 WebSocket 通信 + 自动重连
- 📝 AI 回复 Markdown 渲染（表格、代码块、链接）
- 🔊 Android TTS 朗读（支持 CosyVoice2 云端合成，设置页开关）
- 🔐 **危险命令审批** — 风险等级标识 + 30s 超时自动拒绝 + 高风险确认弹窗
- ❓ **Clarify 交互卡片** — 选项可点击，选中后绿色高亮
- 🛠️ **工具进度卡** — 内联显示执行进度，带旋转动画
- 📄 **文件预览卡** — 文件类型图标 + 大小 + 一键下载
- ⚠️ **错误提示** — 错误消息以独立气泡展示
- 📋 **步骤卡** — 多步骤任务进度 `✅ ○ ⏳ ❌`
- 💡 **建议卡** — 可折叠提示，支持"采用建议"
- 📎 文件附件推送和下载
- 💾 多会话管理（Room 数据库持久化 + Profile 隔离）
- ⚙️ 双 Profile 切换（工作 / 家里，均通过 frp）
- 📱 Jetpack Compose Material3 现代 UI
- 🧭 **微信风格会话列表** — 活跃/归档分区域，预览文本+时间
- 🎛️ **紧凑顶栏** — 极简设计，会话名+模式标签，键盘弹起不遮挡
- 🔧 **技能 Chips** — Skill Manager 驱动，聊天窗口横滚条快捷选能力

### ✅ P0 — 数据底座

- [x] 🔍 **全文搜索** — 搜索所有历史对话 + 文件内容
- [x] 📊 **数据仪表盘** — 本地存储占用、消息数量、文件统计
- [x] 🗄️ **会话导出/导入** — JSON 格式，导出后可导入恢复
- [x] 🗄️ **全量备份/恢复** — 导出所有数据到 .sagebackup 文件
- [x] 🗄️ **批量管理** — 清空消息、重命名会话

### ✅ P1 — 闭环体验

- [x] 🔔 **本地推送通知** — WorkManager 每15分钟轮询 Hermes
- [x] 📎 **文件上传** — 从手机选文件/图片发给 Agent
- [x] 📷 **拍照输入** — 拍照直接给 Agent 分析（图片存本地）
- [x] ✍️ **会话管理** — 重命名、置顶、归档
- [x] 🔗 **系统分享集成** — 任何 App 内容"分享到有数"
- [x] 🌐 **frp 远程穿透** — 公网服务器转发，随时随地连 Hermes
- [x] ⚡ **消息发送状态** — 发送中/已发送/失败，失败可重发
- [x] 💬📚🧠 **助手模式** — 顶栏下拉直接切换，无需进菜单
- [x] 🔧 **能力管理** — 设置页开关用户能力，聊天窗口 chips 快捷切换

### ✅ P2 — 手机特有能力

- [x] 📋 **剪贴板感知** — 外部复制内容弹出"填到输入框"
- [x] 🖼️ **多媒体内联渲染** — 图片直接在对话中展示
- [x] 🎨 **图表渲染** — Mermaid 流程图、架构图
- [x] 📝 **消息复制** — 长按消息气泡填入输入框
- [x] 🎙️ **录音即发** — 录音中点发送自动停录+发送，识别有误可停录修改
- [x] 🟢 **连接状态简化** — 绿点指示连接，已连接不显文字，仅异常时显示标签
- [x] 🔀 **能力路由** — Chip 选中 → Adapter 自动加载技能 → Skill Manager 格式路由 → clarify 选格式

### 🚧 P3 — 高级能力

- [x] 🛠️ **手机端工具注册** — 16个手机工具：GPS/电池/日历/闹钟/通讯录/短信等
- [ ] 🤖 **离线小模型兜底** — 断网时用本地量化模型应急
- [ ] 🫧 **悬浮球 / Widget** — 不打开 App 也能交互
- [ ] 🏠 **主屏小组件** — 一句话快捷入口
- [ ] 🌓 **主题切换** — 暗黑 / 浅色
- [ ] ✋ **手势控制** — 摇一摇唤起 / 双击电源键唤起
- [ ] 🎙️ **全双工语音对话** — 实时打断/插话
- [ ] 🔥 **唤醒词** — 本地 KWS，完全离线
- [ ] 🧪 **ASR 训练流水线** — 校对→微调→导出，持续优化语音识别。独立项目 asr-trainer（proofreader.py ✅ / finetune.py 🚧 / export_onnx.py 🚧）

## 致谢 — 构建基石

有数站在以下开源项目的肩膀上：

| 项目 | 说明 | 链接 |
|------|------|------|
| **Hermes Agent** | AI Agent 框架，提供工具调用、审批、多平台网关 | [hermes-agent.nousresearch.com](https://hermes-agent.nousresearch.com) |
| **sherpa-onnx** | 端侧语音识别推理引擎，离线 ASR | [github.com/k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| **FunASR** | 工业级语音识别工具包，模型训练与校对 | [github.com/modelscope/FunASR](https://github.com/modelscope/FunASR) |
| **CosyVoice** | 阿里通义语音合成，中文 TTS | [github.com/FunAudioLLM/CosyVoice](https://github.com/FunAudioLLM/CosyVoice) |
| **frp** | 内网穿透，手机随时连 Hermes | [github.com/fatedier/frp](https://github.com/fatedier/frp) |
| **Skill Manager** | 能力→技能映射服务，格式路由引擎 | [github.com/netment/skill-manager](https://github.com/netment/skill-manager) |
| **asr-trainer** | ASR 训练流水线：校对→微调→导出→部署 | [github.com/netment/asr-trainer](https://github.com/netment/asr-trainer) |
| **SenseVoice** | 多语言语音识别模型（中英日韩粤+情感） | [github.com/FunAudioLLM/SenseVoice](https://github.com/FunAudioLLM/SenseVoice) |

---

📖 English：[README.en.md](README.en.md)
📖 技术文档：[DEVELOPMENT.md](DEVELOPMENT.md) — 架构、技术栈、构建指南、项目结构。
