"""
MyBot — WebSocket platform adapter for custom Hermes clients.

Plugin entry point: register(ctx) is called by Hermes plugin discovery.
"""
__version__ = "0.2.0"


import asyncio
import json
import logging
import mimetypes
import os
import re
import uuid
from pathlib import Path
from typing import Any, Dict, Optional

try:
    from aiohttp import web
    AIOHTTP_AVAILABLE = True
except ImportError:
    AIOHTTP_AVAILABLE = False
    web = None  # type: ignore

from gateway.platforms.base import (
    BasePlatformAdapter,
    MessageEvent,
    MessageType,
    SendResult,
    SessionSource,
    is_network_accessible,
)
from gateway.config import Platform, PlatformConfig

logger = logging.getLogger("gateway.platforms.mybot")

# Ensure mybot logs are visible regardless of gateway's handler level.
# We add a dedicated handler so INFO/DEBUG messages survive even when
# the gateway root handler filters at WARNING.
_mybot_level_name = os.getenv("MYBOT_LOG_LEVEL", "INFO").upper()
_mybot_level = getattr(logging, _mybot_level_name, logging.INFO)
logger.setLevel(_mybot_level)
if not any(isinstance(h, logging.StreamHandler) and getattr(h, '_mybot_owned', False) for h in logger.handlers):
    _h = logging.StreamHandler()
    _h.setLevel(_mybot_level)
    _h.setFormatter(logging.Formatter(
        "[mybot] [%(asctime)s] [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    ))
    _h._mybot_owned = True  # tag so we don't add duplicates on reimport
    logger.addHandler(_h)
    # Don't propagate to root — our handler outputs directly
    logger.propagate = False

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8643


# ──────────────────────────────────────────────
# Adapter
# ──────────────────────────────────────────────


class MyBotAdapter(BasePlatformAdapter):
    """WebSocket server adapter for custom Hermes clients."""

    def __init__(self, config: PlatformConfig):
        super().__init__(config, Platform("mybot"))
        extra = config.extra or {}
        self._host = extra.get("host", os.getenv("MYBOT_HOST", DEFAULT_HOST))
        self._port = int(extra.get("port", os.getenv("MYBOT_PORT", str(DEFAULT_PORT))))
        self._api_key = extra.get("api_key", os.getenv("MYBOT_API_KEY", ""))

        self._app = None
        self._runner = None
        self._site = None
        self._ws_sessions: Dict[str, Any] = {}       # device_id → ws
        self._session_to_device: Dict[str, str] = {}  # hermes_session_id → device_id
        self._ws_lock = asyncio.Lock()
        self._file_registry: Dict[str, dict] = {}   # file_id → {path, name, size, mime}
        self._msg_counters: Dict[str, int] = {}     # session_id → last msg id
        self._msg_previews: Dict[str, str] = {}     # session_id → last msg preview
        self._counter_path = Path(os.path.expanduser("~/.hermes/data/mybot_counters.json"))
        self._load_counters()

    # ── Lifecycle ────────────────────────────

    async def connect(self) -> bool:
        if not AIOHTTP_AVAILABLE:
            logger.error("[mybot] aiohttp not installed")
            return False

        self._app = web.Application()
        self._app["mybot_adapter"] = self
        self._app.router.add_get("/v1/ws", self._handle_ws)
        self._app.router.add_get("/health", self._handle_health)
        self._app.router.add_get("/v1/files/{file_id}", self._handle_file_download)
        self._app.router.add_get("/v1/files/{file_id}/info", self._handle_file_info)
        self._app.router.add_post("/v1/upload", self._handle_file_upload)
        self._app.router.add_get("/v1/capabilities", self._handle_capabilities)
        self._app.router.add_get("/v1/poll", self._handle_poll)

        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, self._host, self._port)
        await self._site.start()

        self._mark_connected()
        logger.info("[mybot] v%s ws://%s:%s/v1/ws", __version__, self._host, self._port)
        return True

    async def disconnect(self) -> None:
        async with self._ws_lock:
            sessions = list(self._ws_sessions.items())
            self._ws_sessions.clear()
            self._session_to_device.clear()
        for _, ws in sessions:
            try:
                await ws.close(code=1001, message=b"Server shutdown")
            except Exception:
                pass
        if self._site:
            await self._site.stop()
        if self._runner:
            await self._runner.cleanup()
        self._mark_disconnected()

    async def get_chat_info(self, chat_id: str) -> dict:
        return {"chat_id": chat_id, "platform": "mybot", "type": "direct"}

    # ── Outbound ─────────────────────────────

    # Progress detection: content format is "<emoji> <tool_name>: ..." or "<emoji> <tool_name>..."
    _PROGRESS_RE = re.compile(r'^[\U0001F300-\U0001FAD6\u2699\u2700-\u27BF][\ufe0f\u20e3]*\s+(\S+?)(?::|\.{3})')

    async def send_tool_progress(
        self, chat_id: str, text: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        """Send tool progress as structured WS messages — called by gateway's progress dispatcher."""
        ws = await self._get_ws(chat_id)
        if ws is None:
            return SendResult(success=False, error="Client not connected")
        for line in text.split("\n"):
            line = line.strip()
            if not line:
                continue
            m = self._PROGRESS_RE.match(line)
            tool_name = m.group(1) if m else "?"
            await self._ws_send(ws, {
                "type": "tool_progress",
                "tool": tool_name,
                "status": "running",
                "label": line,
            })
        self._track_msg(chat_id, text)
        return SendResult(success=True, message_id=str(uuid.uuid4()))

    async def send(
        self, chat_id: str, content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        ws = await self._get_ws(chat_id)
        if ws is None:
            return SendResult(success=False, error="Client not connected")
        # Detect tool progress: convert multi-line progress text → structured tool_progress WS messages
        lines = [l.strip() for l in content.split("\n") if l.strip()]
        progress_tools = []
        for line in lines:
            m = self._PROGRESS_RE.match(line)
            if m:
                progress_tools.append((m.group(1), line))
        if progress_tools and len(progress_tools) == len(lines):
            # All lines are progress — send as structured tool_progress
            for tool_name, label in progress_tools:
                await self._ws_send(ws, {
                    "type": "tool_progress",
                    "tool": tool_name,
                    "status": "running",
                    "label": label,
                })
            self._track_msg(chat_id, content)
            return SendResult(success=True, message_id=str(uuid.uuid4()))
        # Regular text message
        await self._ws_send(ws, {
            "type": "assistant_message",
            "text": content,
            "session_id": chat_id,
        })
        self._track_msg(chat_id, content)
        await self._ws_send(ws, {"type": "session_state", "state": "ready"})
        return SendResult(success=True, message_id=str(uuid.uuid4()))

    async def send_exec_approval(
        self, chat_id: str, command: str, session_key: str,
        description: str = "", metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        ws = await self._get_ws(chat_id)
        if ws is None:
            return SendResult(success=False, error="Client not connected")
        await self._ws_send(ws, {
            "type": "approval_required",
            "command": command,
            "description": description,
        })
        await self._ws_send(ws, {"type": "session_state", "state": "awaiting_approval"})
        return SendResult(success=True, message_id=str(uuid.uuid4()))

    # ── Clarify support ───────────────────────

    async def send_clarify(
        self, chat_id: str, question: str, choices, clarify_id: str,
        session_key: str, metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        """Override base: send structured clarify via WebSocket for interactive choices."""
        from tools.clarify_gateway import mark_awaiting_text

        ws = await self._get_ws(chat_id)
        if ws is None:
            return SendResult(success=False, error="Client not connected")

        if choices:
            # Send interactive clarify with tappable choices
            await self._ws_send(ws, {
                "type": "clarify",
                "question": question,
                "choices": choices,
                "clarify_id": clarify_id,
            })
            # Also mark as awaiting text for "Other" / free-text responses
            mark_awaiting_text(clarify_id)
            await self._ws_send(ws, {"type": "session_state", "state": "awaiting_clarify"})
        else:
            # Open-ended — text fallback, gateway intercept catches the reply
            mark_awaiting_text(clarify_id)
            await self.send(chat_id, f"❓ {question}")

        return SendResult(success=True, message_id=clarify_id)

    # ── Native file delivery ─────────────────

    async def send_document(
        self, chat_id: str, file_path: str,
        caption: Optional[str] = None, file_name: Optional[str] = None,
        reply_to: Optional[str] = None, **kwargs,
    ) -> SendResult:
        """Send a document as a downloadable file via WebSocket."""
        return await self._send_file_via_ws(chat_id, file_path, file_name)

    async def send_image_file(
        self, chat_id: str, image_path: str,
        caption: Optional[str] = None, reply_to: Optional[str] = None, **kwargs,
    ) -> SendResult:
        """Send a local image file as a downloadable file via WebSocket."""
        return await self._send_file_via_ws(chat_id, image_path)

    async def _send_file_via_ws(
        self, chat_id: str, file_path: str, display_name: Optional[str] = None,
    ) -> SendResult:
        """Register a local file and push attachment metadata via WebSocket."""
        path = Path(file_path)
        if not path.exists():
            logger.warning("[mybot] file not found: %s", file_path)
            return SendResult(success=False, error=f"File not found: {file_path}")

        file_id = str(uuid.uuid4())
        name = display_name or path.name
        size = path.stat().st_size
        mime = self._guess_mime(path.suffix)

        self._file_registry[file_id] = {
            "path": str(path), "name": name, "size": size, "mime": mime,
        }
        logger.info("[mybot] registered file: %s → %s (%d bytes)", file_id, name, size)

        ws = await self._get_ws(chat_id)
        if ws is None:
            return SendResult(success=False, error="Client not connected")

        await self._ws_send(ws, {
            "type": "file",
            "name": name,
            "url": f"/v1/files/{file_id}",
            "size": size,
            "mime": mime,
        })
        return SendResult(success=True, message_id=file_id)

    @staticmethod
    def _guess_mime(suffix: str) -> str:
        suffix = suffix.lower()
        mime_map = {
            ".pdf": "application/pdf", ".txt": "text/plain", ".md": "text/markdown",
            ".json": "application/json", ".csv": "text/csv", ".html": "text/html",
            ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
            ".gif": "image/gif", ".webp": "image/webp", ".svg": "image/svg+xml",
            ".zip": "application/zip", ".mp3": "audio/mpeg", ".wav": "audio/wav",
            ".mp4": "video/mp4", ".py": "text/x-python", ".js": "text/javascript",
            ".ts": "text/typescript", ".kt": "text/x-kotlin", ".xml": "text/xml",
        }
        return mime_map.get(suffix, mimetypes.types_map.get(suffix, "application/octet-stream"))

    # ── WebSocket handler ────────────────────

    async def _handle_ws(self, request) -> Any:
        ws = web.WebSocketResponse(heartbeat=30.0)
        await ws.prepare(request)
        device_id = request.query.get("device_id", str(uuid.uuid4()))
        logger.info("[mybot] WS connect: device_id=%s remote=%s", device_id, request.remote)

        async with self._ws_lock:
            old = self._ws_sessions.pop(device_id, None)
        if old is not None:
            try:
                await old.close()
            except Exception:
                pass
        async with self._ws_lock:
            self._ws_sessions[device_id] = ws

        await self._ws_send(ws, {"type": "status", "text": "已连接 Hermes"})

        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    await self._on_text(ws, device_id, msg.data)
                elif msg.type in (web.WSMsgType.ERROR, web.WSMsgType.CLOSE):
                    break
        finally:
            async with self._ws_lock:
                self._ws_sessions.pop(device_id, None)
                # Clean up all session→device mappings for this device
                stale = [sid for sid, did in self._session_to_device.items() if did == device_id]
                for sid in stale:
                    self._session_to_device.pop(sid, None)
                logger.info("[mybot] WS disconnect: device_id=%s sessions_cleaned=%d remaining_devices=%d",
                            device_id, len(stale), len(self._ws_sessions))
        return ws

    async def _on_text(self, ws, device_id: str, raw: str):
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            await self._ws_send(ws, {"type": "error", "text": "无效 JSON"})
            return

        msg_type = data.get("type", "")

        if msg_type == "ping":
            await self._ws_send(ws, {"type": "pong"})

        elif msg_type == "user_message":
            text = data.get("text", "").strip()
            if not text:
                return
            # Read hermes session_id from message body; update reverse mapping
            hermes_session_id = data.get("session_id", "") or device_id
            async with self._ws_lock:
                old_device = self._session_to_device.get(hermes_session_id)
                self._session_to_device[hermes_session_id] = device_id
            if old_device != device_id:
                logger.info("[mybot] session mapping: session_id=%s device_id=%s (was=%s)",
                            hermes_session_id[:8], device_id, old_device[:8] if old_device else "new")

            await self._ws_send(ws, {"type": "session_state", "state": "thinking"})
            event = MessageEvent(
                text=text,
                message_type=MessageType.TEXT,
                source=SessionSource(
                    platform=Platform("mybot"),
                    chat_id=hermes_session_id,
                    user_id="mybot-user",
                ),
                message_id=str(uuid.uuid4()),
                raw_message=data,
            )
            # Directly call _message_handler with approval context
            async def _process():
                if not self._message_handler:
                    return
                try:
                    from tools.approval import (
                        register_gateway_notify, unregister_gateway_notify,
                        set_current_session_key,
                    )
                    from gateway.session import build_session_key

                    session_key = build_session_key(event.source)

                    # Register approval callback so dangerous commands trigger
                    # approval_required messages through the adapter's send_exec_approval
                    def _notify(approval_data: dict):
                        cmd = approval_data.get("command", "")
                        desc = approval_data.get("description", "")
                        asyncio.run_coroutine_threadsafe(
                            self.send_exec_approval(hermes_session_id, cmd, session_key, desc),
                            asyncio.get_running_loop()
                        )

                    register_gateway_notify(session_key, _notify)
                    token = set_current_session_key(session_key)

                    try:
                        # Send tool_progress for structured progress card on Android
                        await self._ws_send(ws, {"type": "tool_progress", "tool": "thinking", "status": "running", "label": "🤔 思考中..."})
                        response = await self._message_handler(event)
                        await self._ws_send(ws, {"type": "tool_progress", "tool": "thinking", "status": "completed", "label": "✅ 完成"})

                        # Unwrap response (string or EphemeralReply)
                        text_out = ""
                        if hasattr(self, '_unwrap_ephemeral'):
                            text_out, _ = self._unwrap_ephemeral(response)
                        elif isinstance(response, str):
                            text_out = response

                        if not text_out:
                            return

                        # ── File extraction BEFORE sending text ──
                        # Same order as handle_message() in base.py:
                        # 1. extract_media  (MEDIA: tags from TTS/audio tools)
                        # 2. extract_images (image URLs)
                        # 3. extract_local_files (bare paths like /tmp/hello.txt)
                        media_files, cleaned = self.extract_media(text_out)
                        images, cleaned = self.extract_images(cleaned)
                        local_files, cleaned = self.extract_local_files(cleaned)

                        # Strip any remaining internal directives
                        cleaned = cleaned.replace("[[audio_as_voice]]", "").replace("[[as_document]]", "").strip()
                        cleaned = re.sub(r"MEDIA:\s*\S+", "", cleaned).strip()

                        # Send the CLEANED text (no MEDIA: tags, no raw paths)
                        if cleaned:
                            await self.send(hermes_session_id, cleaned)

                        # Deliver extracted files
                        for file_path, _is_voice in media_files:
                            await self._send_file_via_ws(hermes_session_id, file_path)
                        for file_path in local_files:
                            await self._send_file_via_ws(hermes_session_id, file_path)
                    finally:
                        unregister_gateway_notify(session_key)
                except Exception as e:
                    logger.error("[mybot] handler failed: %s", e)
            asyncio.create_task(_process())

        elif msg_type == "approval_response":
            approved = data.get("approved", False)
            choice = data.get("choice", "once")
            approval_choice = "deny"
            if approved:
                approval_choice = {"once": "once", "session": "session", "always": "always"}.get(choice, "once")
            try:
                from tools.approval import resolve_gateway_approval
                from gateway.session import build_session_key
                # Find which hermes session(s) this device owns with pending approvals
                async with self._ws_lock:
                    candidates = [sid for sid, did in self._session_to_device.items() if did == device_id]
                resolved = False
                for sid in candidates:
                    sk = build_session_key(SessionSource(
                        platform=Platform("mybot"), chat_id=sid, user_id="mybot-user"
                    ))
                    count = resolve_gateway_approval(sk, approval_choice)
                    if count > 0:
                        logger.info("[mybot] resolve approval: sk=%s choice=%s count=%d", sk[:30], approval_choice, count)
                        resolved = True
                        break
                if not resolved and candidates:
                    logger.warning("[mybot] resolve approval: no pending approval found for device=%s", device_id)
                await self._ws_send(ws, {"type": "approval_resolved", "approved": approved, "choice": approval_choice})
            except Exception as e:
                logger.error("[mybot] resolve approval error: %s", e)

        elif msg_type == "clarify_response":
            clarify_id = data.get("clarify_id", "")
            response = data.get("text", "").strip()
            if clarify_id and response:
                try:
                    from tools.clarify_gateway import resolve_gateway_clarify
                    resolve_gateway_clarify(clarify_id, response)
                    await self._ws_send(ws, {"type": "clarify_resolved", "clarify_id": clarify_id, "response": response})
                    logger.info("[mybot] resolved clarify id=%s response=%s", clarify_id, response[:50])
                except Exception as e:
                    logger.error("[mybot] resolve clarify error: %s", e)
            await self._ws_send(ws, {"type": "session_state", "state": "thinking"})

    # ── Helpers ──────────────────────────────

    async def _get_ws(self, chat_id: str):
        """Look up WebSocket by chat_id (hermes session_id or device_id).
        
        Resolution order:
        1. chat_id in _session_to_device → device_id → _ws_sessions[device_id]
        2. chat_id directly in _ws_sessions (device_id or legacy session_id)
        """
        async with self._ws_lock:
            device_id = self._session_to_device.get(chat_id)
            if device_id is not None:
                ws = self._ws_sessions.get(device_id)
                if ws is None:
                    logger.warning("[mybot] _get_ws: session_id=%s → device_id=%s but device not connected",
                                   chat_id[:8], device_id)
                return ws
            # Fallback: try direct lookup (device_id or legacy)
            ws = self._ws_sessions.get(chat_id)
            if ws is None and chat_id:
                logger.warning("[mybot] _get_ws: chat_id=%s not found in mapping or sessions (mappings=%d sessions=%d)",
                               chat_id[:8], len(self._session_to_device), len(self._ws_sessions))
            return ws

    async def _ws_send(self, ws, data: dict):
        try:
            if not ws.closed:
                await ws.send_json(data)
        except Exception:
            pass

    @staticmethod
    async def _handle_health(request) -> Any:
        return web.json_response({"status": "ok", "platform": "mybot"})

    async def _handle_file_download(self, request) -> Any:
        """Serve a registered file for download."""
        file_id = request.match_info["file_id"]
        info = self._file_registry.get(file_id)
        if not info:
            return web.Response(status=404, text="File not found")
        file_path = info["path"]
        if not os.path.isfile(file_path):
            return web.Response(status=404, text="File no longer available")
        return web.FileResponse(
            path=file_path,
            headers={
                "Content-Type": info.get("mime", "application/octet-stream"),
                "Content-Disposition": f'attachment; filename="{info["name"]}"',
            },
        )

    async def _handle_file_info(self, request) -> Any:
        """Return metadata for a registered file."""
        file_id = request.match_info["file_id"]
        info = self._file_registry.get(file_id)
        if not info:
            return web.Response(status=404, text="File not found")
        return web.json_response({
            "file_id": file_id,
            "name": info["name"],
            "size": info["size"],
            "mime": info["mime"],
            "url": f"/v1/files/{file_id}",
        })

    async def _handle_file_upload(self, request) -> Any:
        """Receive a file upload from a client and save to tool_media."""
        reader = await request.multipart()
        field = await reader.next()
        if field is None:
            return web.Response(status=400, text="No file in request")

        filename = field.filename or "upload.bin"
        # Sanitize filename
        safe_name = "".join(c for c in filename if c.isalnum() or c in "._- ")
        if not safe_name.strip():
            safe_name = "upload.bin"

        # Read file data
        data = b""
        while True:
            chunk = await field.read_chunk()
            if not chunk:
                break
            data += chunk

        if not data:
            return web.Response(status=400, text="Empty file")

        # Save to ~/.hermes/tool_media/
        import os as _os
        media_dir = Path(_os.path.expanduser("~/.hermes/tool_media"))
        media_dir.mkdir(parents=True, exist_ok=True)

        file_id = str(uuid.uuid4())
        ext = Path(safe_name).suffix or ".bin"
        saved_name = f"{file_id}{ext}"
        save_path = media_dir / saved_name
        with open(save_path, "wb") as f:
            f.write(data)

        size = save_path.stat().st_size
        mime = self._guess_mime(ext)

        # Register in file registry
        self._file_registry[file_id] = {
            "path": str(save_path),
            "name": safe_name,
            "size": size,
            "mime": mime,
        }
        logger.info("[mybot] uploaded file: %s → %s (%d bytes)", file_id, safe_name, size)

        # Send a message to the agent about the uploaded file
        session_id = request.query.get("session_id", "")
        device_id = request.query.get("device_id", "")
        if session_id:
            # Update reverse mapping so _get_ws can route by session_id
            if device_id:
                async with self._ws_lock:
                    self._session_to_device[session_id] = device_id
            event = MessageEvent(
                text=f"[用户上传了图片: {safe_name}]",
                message_type=MessageType.TEXT,
                source=SessionSource(
                    platform=Platform("mybot"),
                    chat_id=session_id,
                    user_id="mybot-user",
                ),
                message_id=str(uuid.uuid4()),
                raw_message={"file_id": file_id, "name": safe_name, "path": str(save_path)},
            )
            if self._message_handler:
                asyncio.create_task(self._send_file_via_ws(session_id, str(save_path), safe_name))
                asyncio.create_task(self._process_upload_event(session_id, event))

        return web.json_response({
            "status": "ok",
            "file_id": file_id,
            "name": safe_name,
            "size": size,
            "mime": mime,
        })

    async def _process_upload_event(self, session_id: str, event: MessageEvent):
        """Process a file upload event through the agent."""
        try:
            ws = await self._get_ws(session_id)
            if ws is None:
                return
            await self._ws_send(ws, {"type": "session_state", "state": "thinking"})

            from tools.approval import register_gateway_notify, unregister_gateway_notify, set_current_session_key
            from gateway.session import build_session_key

            session_key = build_session_key(event.source)
            token = set_current_session_key(session_key)

            def _notify(approval_data: dict):
                cmd = approval_data.get("command", "")
                desc = approval_data.get("description", "")
                asyncio.run_coroutine_threadsafe(
                    self.send_exec_approval(session_id, cmd, session_key, desc),
                    asyncio.get_running_loop()
                )

            register_gateway_notify(session_key, _notify)
            try:
                await self._ws_send(ws, {"type": "tool_progress", "tool": "thinking", "status": "running", "label": "🤔 思考中..."})
                response = await self._message_handler(event)
                await self._ws_send(ws, {"type": "tool_progress", "tool": "thinking", "status": "completed", "label": "✅ 完成"})

                text_out = ""
                if hasattr(self, '_unwrap_ephemeral'):
                    text_out, _ = self._unwrap_ephemeral(response)
                elif isinstance(response, str):
                    text_out = response

                if text_out:
                    media_files, cleaned = self.extract_media(text_out)
                    images, cleaned = self.extract_images(cleaned)
                    local_files, cleaned = self.extract_local_files(cleaned)
                    cleaned = cleaned.replace("[[audio_as_voice]]", "").replace("[[as_document]]", "").strip()
                    cleaned = re.sub(r"MEDIA:\\s*\\S+", "", cleaned).strip()

                    if cleaned:
                        await self.send(session_id, cleaned)
                    for file_path, _is_voice in media_files:
                        await self._send_file_via_ws(session_id, file_path)
                    for file_path in local_files:
                        await self._send_file_via_ws(session_id, file_path)
            finally:
                unregister_gateway_notify(session_key)
        except Exception as e:
            logger.error("[mybot] upload event processing failed: %s", e)

    # ── Capabilities proxy ───────────────────

    async def _handle_capabilities(self, request) -> Any:
        """Proxy to Skill Manager: GET /v1/capabilities?profile=..."""
        import aiohttp
        profile = request.query.get("profile", "work")
        url = f"http://127.0.0.1:8888/v1/capabilities?profile={profile}"
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                    data = await resp.json()
                    return web.json_response(data)
        except Exception as e:
            logger.warning("[mybot] capabilities proxy failed: %s", e)
            return web.json_response({"capabilities": []})

    # ── Poll endpoint ─────────────────────────

    def _load_counters(self):
        """Load persisted message counters from disk."""
        try:
            if self._counter_path.exists():
                data = json.loads(self._counter_path.read_text())
                self._msg_counters = data.get("counters", {})
                self._msg_previews = data.get("previews", {})
                logger.info("[mybot] loaded counters for %d sessions", len(self._msg_counters))
        except Exception:
            pass

    def _save_counters(self):
        """Persist message counters to disk."""
        try:
            self._counter_path.parent.mkdir(parents=True, exist_ok=True)
            self._counter_path.write_text(json.dumps({
                "counters": self._msg_counters,
                "previews": self._msg_previews,
            }))
        except Exception:
            pass

    def _track_msg(self, session_id: str, text: str):
        """Track message counter and preview for polling."""
        c = self._msg_counters.get(session_id, 0) + 1
        self._msg_counters[session_id] = c
        preview = text.strip().replace('\n', ' ')[:80]
        self._msg_previews[session_id] = preview
        self._save_counters()
        logger.info("[mybot] track_msg: session_id=%s counter=%d preview=%s",
                     session_id[:8], c, preview[:50])

    async def _handle_poll(self, request) -> Any:
        """Poll for new messages since a given message ID."""
        session_id = request.query.get("session_id", "")
        since = int(request.query.get("since_msg_id", "0"))

        current = self._msg_counters.get(session_id, 0)
        has_new = current > since
        preview = self._msg_previews.get(session_id, "")

        logger.info("[mybot] poll: session_id=%s since=%d current=%d has_new=%s count=%d remote=%s",
                    session_id[:8] if session_id else "(empty)", since, current,
                    has_new, current - since if has_new else 0, request.remote)

        return web.json_response({
            "session_id": session_id,
            "has_new": has_new,
            "last_msg_id": current,
            "preview": preview if has_new else "",
            "count": current - since if has_new else 0,
        })


# ──────────────────────────────────────────────
# Plugin entry points
# ──────────────────────────────────────────────


def check_requirements() -> bool:
    return AIOHTTP_AVAILABLE


def validate_config(config) -> bool:
    # Always valid — defaults work without explicit config
    return True


def _env_enablement() -> Optional[dict]:
    """Auto-enable from env vars (MYBOT_HOST / MYBOT_PORT)."""
    host = os.getenv("MYBOT_HOST", "").strip() or DEFAULT_HOST
    port = int(os.getenv("MYBOT_PORT", str(DEFAULT_PORT)))
    return {"host": host, "port": port}


def register(ctx) -> None:
    """Plugin entry point — called by Hermes plugin discovery."""
    ctx.register_platform(
        name="mybot",
        label="MyBot (WebSocket)",
        adapter_factory=lambda cfg: MyBotAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        required_env=[],
        env_enablement_fn=_env_enablement,
        allow_all_env="MYBOT_ALLOW_ALL_USERS",
        max_message_length=0,
        emoji="🤖",
    )
