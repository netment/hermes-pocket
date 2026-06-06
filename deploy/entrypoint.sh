#!/bin/bash
set -e

echo "=== Hermes Pocket 服务端启动 ==="

# 检查必需的环境变量
if [ -z "$DEEPSEEK_API_KEY" ]; then
    echo "❌ 请设置 DEEPSEEK_API_KEY 环境变量"
    echo "   docker-compose 用户：编辑 deploy/.env 文件"
    echo "   docker run 用户：添加 -e DEEPSEEK_API_KEY=sk-xxx"
    exit 1
fi

# 激活 Hermes 虚拟环境
source /root/.hermes/hermes-agent/venv/bin/activate
export PATH="/root/.local/bin:$PATH"

# 配置 DeepSeek 为默认 provider
echo "📡 配置 DeepSeek 作为 AI 服务商..."
hermes config set model.provider deepseek 2>/dev/null || true
hermes config set model.default deepseek-v4 2>/dev/null || true

# 确保 gateway 启用
echo "🌐 启动 Hermes Gateway + MyBot 插件..."
echo "   WebSocket: ws://0.0.0.0:${MYBOT_PORT:-8643}/v1/ws"
echo "   HTTP API:  http://0.0.0.0:${MYBOT_PORT:-8643}"
echo ""

exec hermes gateway run
