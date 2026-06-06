# YouShu (有数) — Your AI, your data, your Sage

> **"Your Tomo, your rules."**

Tomo is a local-first Android client for AI Agents. Initially integrated with [Hermes Agent](https://hermes-agent.nousresearch.com), with support for any compatible Agent backend to follow.

Unlike QQ/WeChat/Telegram platform gateways, Tomo keeps everything on your phone — conversations, voice recordings, AI-generated files, images. All yours. View, search, export, or delete at any time.

## Vision

Most AI assistants today rely on third-party platforms. Your conversations live on their servers. You can't export them. You can't truly delete them. You don't own your data.

**Tomo changes this:**

```
Platform Gateway (status quo)     Tomo (vision)
──────────────────────────        ──────────────────────
You ─→ QQ/WeChat server ─→ Agent  You ──── Agent
       ↑ data owned by platform         ↑ data owned by you
        no export/delete                 local SQLite + free export
```

| Principle | Meaning |
|-----------|---------|
| **Local-first** | All conversations, recordings, files, and images stay on your phone by default |
| **User control** | View, search, export, delete your data anytime |
| **Direct connection** | Phone connects directly to Agent (LAN/USB/Tailscale), no third-party servers |
| **Offline capable** | Core features (ASR) work without internet |
| **Privacy first** | Voice is processed locally with configurable auto-cleanup |

## Roadmap

### ✅ Done

- 🎤 Offline speech recognition (sherpa-onnx Paraformer + Silero VAD)
- 💬 Real-time WebSocket communication with auto-reconnect
- 📝 Markdown rendering in replies (tables, code blocks, links)
- 🔊 Android TTS read-aloud
- 🔐 Tool execution approval cards (deny / allow / session-allow) with persistence
- 📎 File attachment push and download
- 💾 Multi-session management (Room database persistence)
- ⚙️ One-tap network switching (USB / Tailscale / LAN)
- 📱 Jetpack Compose Material3 modern UI

### ✅ P0 — Data Foundation

- [x] 🗄️ **Conversation export** — Markdown / JSON export
- [x] 🗄️ **Batch management** — clear messages, rename sessions
- [x] 🔍 **Full-text search** — across all conversations + file contents
- [x] 📊 **Data dashboard** — storage usage, message count, file stats

### ✅ P1 — Complete Experience

- [x] 🔔 **Local notifications** — WorkManager polls Hermes every 15 min, alerts on new messages
- [x] 📎 **File upload** — send files/images from phone to Agent
- [x] 📷 **Camera input** — take a photo, send to Agent (stored locally)
- [x] ✍️ **Session enhancements** — rename
- [x] 🔗 **System share** — "Share to Tomo" from any app
- [x] 🌐 **frp remote access** — tunnel through frp server, connect anytime anywhere
- [ ] ✍️ Pin/archive/tag

### 🚧 P2 — Mobile-native Capabilities

- [x] 📋 **Clipboard awareness** — external copy offers "fill input", internal long-press fills directly
- [x] 🖼️ **Rich media inline** — images displayed inline in chat
- [x] 🎨 **Mermaid diagrams** — flowchart/architecture rendering
- [x] 📝 **Copy messages** — long-press bubble fills input field
- [ ] 🎙️ **Full-duplex voice** — real-time interrupt/overlap, VAD auto-detection
- [ ] 🔥 **Wake word** — local KWS, fully offline

### 🚧 P3 — Advanced

- [x] 🛠️ **Phone tool registration** — 16 tools: GPS, battery, calendar, alarm, contacts, SMS, etc.
- [ ] 🤖 **Offline fallback model** — local quantized model when offline
- [ ] 🫧 **Chat head / Widget** — interact without opening the app
- [ ] 🏠 **Home screen widget** — one-tap quick entry
- [ ] 🌓 **Theme switching** — dark / light
- [ ] ✋ **Gesture control** — shake to wake / double-tap power button

---

📖 中文文档：[README.md](README.md)
📖 Technical docs：[DEVELOPMENT.md](DEVELOPMENT.md) — architecture, tech stack, build guide, project structure.
