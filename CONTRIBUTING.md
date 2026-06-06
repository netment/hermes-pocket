# 贡献指南

感谢你对有数的关注！欢迎提交 Issue 和 Pull Request。

## 如何贡献

### 报告问题

在 GitHub Issues 中提交，请包含：
- App 版本号
- Android 系统版本
- 复现步骤
- 期望行为 vs 实际行为

### 提交代码

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feat/your-feature`
3. 提交代码：`git commit -m "feat: 描述你的改动"`
4. 推送到你的 Fork
5. 提交 Pull Request

### 代码规范

- Kotlin 代码遵循 Android Kotlin Style Guide
- 提交信息使用约定式提交格式：`feat:` / `fix:` / `docs:` / `refactor:`
- 新增功能请更新相应文档

### 开发环境

见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 项目结构

```
voice-assistant-android/
├── app/src/main/java/com/example/voiceassistant/
│   ├── MainActivity.kt              # 主 Activity
│   ├── ui/                          # Compose UI 组件
│   ├── network/                     # WebSocket 客户端
│   ├── data/                        # Room 数据库
│   └── service/                     # ASR 引擎
├── README.md
├── DEVELOPMENT.md
└── LICENSE
```

## 行为准则

- 尊重所有贡献者
- 建设性的代码评审
- 保持友好和包容的氛围
