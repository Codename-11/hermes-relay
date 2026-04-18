"""Voice endpoints — relay-side TTS / STT bridge for the Android voice mode.

The hermes-agent venv ships ``tools.tts_tool.text_to_speech_tool`` and
``tools.transcription_tools.transcribe_audio`` — the relay plugin is
editable-installed into that same venv, so we import them directly. Both
upstream functions are *synchronous*, so every call is wrapped in
``asyncio.to_thread`` to keep the aiohttp event loop free.

Routes (all bearer-auth'd, same trust model as ``/media/*``):

* ``POST /voice/transcribe`` — multipart/form-data audio upload → JSON text
* ``POST /voice/synthesize`` — JSON ``{"text": ...}`` → ``audio/mpeg`` bytes
* ``GET  /voice/config``     — capabilities probe (what's configured)

Auth is handled inline (matching the ``/media/*`` handlers' pattern) rather
than via a wrapper — the media module sets the precedent and we keep this
file self-contained.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import tempfile
from typing import Any

from aiohttp import web

logger = logging.getLogger("hermes_relay.voice")


# Upper bounds. Transcription audio is bounded by a hard byte cap; TTS text
# is bounded by character count (ElevenLabs' own limit is ~5000 chars per
# request, OpenAI TTS allows 4096 — we pick the smaller safe value).
MAX_AUDIO_BYTES = 25 * 1024 * 1024  # 25 MB — Whisper's upstream cap
MAX_TEXT_CHARS = 5000


# Rough content-type → extension table for the temp file Whisper reads.
# Whisper is tolerant about formats but is much happier when the extension
# matches the bytes on disk.
_AUDIO_EXT_BY_CONTENT_TYPE = {
    "audio/wav": ".wav",
    "audio/wave": ".wav",
    "audio/x-wav": ".wav",
    "audio/webm": ".webm",
    "audio/ogg": ".ogg",
    "audio/opus": ".opus",
    "audio/mp4": ".m4a",
    "audio/x-m4a": ".m4a",
    "audio/mpeg": ".mp3",
    "audio/mp3": ".mp3",
    "audio/flac": ".flac",
    "audio/x-flac": ".flac",
    "audio/aac": ".aac",
}


def _ext_for_content_type(content_type: str | None) -> str:
    if not content_type:
        return ".wav"
    base = content_type.split(";", 1)[0].strip().lower()
    return _AUDIO_EXT_BY_CONTENT_TYPE.get(base, ".wav")


def _require_bearer_session(request: web.Request):
    """Validate the bearer token against the relay SessionManager.

    Mirrors the inline auth pattern used by ``handle_media_get`` /
    ``handle_media_by_path`` so all phone-facing endpoints behave the same
    way. Returns the resolved session on success, raises
    :class:`aiohttp.web.HTTPUnauthorized` on any failure.
    """
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        raise web.HTTPUnauthorized(
            text="Authorization: Bearer <session_token> required",
        )
    bearer = auth_header[len("Bearer ") :].strip()
    if not bearer:
        raise web.HTTPUnauthorized(text="empty bearer token")

    server = request.app["server"]
    session = server.sessions.get_session(bearer)
    if session is None:
        logger.info(
            "Voice route rejected: invalid bearer from %s",
            request.remote or "unknown",
        )
        raise web.HTTPUnauthorized(text="invalid or expired session token")
    return server, session, bearer


class VoiceHandler:
    """Routes + small amount of per-request state for the voice endpoints.

    Modeled on the ``MediaRegistry`` pattern: one instance lives on
    ``RelayServer`` and handler methods are registered directly on the
    aiohttp router.
    """

    def __init__(self, config: Any) -> None:
        self.config = config

    # ── POST /voice/transcribe ──────────────────────────────────────────

    async def handle_transcribe(self, request: web.Request) -> web.Response:
        """Upload audio, return transcript.

        Accepts ``multipart/form-data`` with a single file field (any name
        — we take the first file-shaped part we see). The upstream Whisper
        client needs an absolute file path, so we stash bytes in a
        ``NamedTemporaryFile`` with an extension picked from the
        ``Content-Type``, call ``transcribe_audio`` off-loop, and unlink
        the temp file in ``finally``.
        """
        _require_bearer_session(request)

        temp_path: str | None = None
        try:
            reader = await request.multipart()
        except Exception as exc:
            logger.info("Voice transcribe: malformed multipart: %s", exc)
            return web.json_response(
                {"success": False, "error": f"malformed multipart body: {exc}"},
                status=400,
            )

        part = None
        try:
            async for field in reader:
                if field.filename or field.name in ("file", "audio"):
                    part = field
                    break
        except Exception as exc:
            logger.info("Voice transcribe: multipart read failed: %s", exc)
            return web.json_response(
                {"success": False, "error": f"multipart read failed: {exc}"},
                status=400,
            )

        if part is None:
            return web.json_response(
                {
                    "success": False,
                    "error": "no audio file in multipart body",
                },
                status=400,
            )

        ext = _ext_for_content_type(part.headers.get("Content-Type"))

        try:
            # NamedTemporaryFile with delete=False so we can close it and
            # hand the path to a sync function running on a worker thread.
            tmp = tempfile.NamedTemporaryFile(
                suffix=ext, prefix="hermes_voice_", delete=False
            )
            temp_path = tmp.name
            total = 0
            try:
                while True:
                    chunk = await part.read_chunk(64 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > MAX_AUDIO_BYTES:
                        tmp.close()
                        return web.json_response(
                            {
                                "success": False,
                                "error": (
                                    f"audio exceeds {MAX_AUDIO_BYTES} bytes"
                                ),
                            },
                            status=413,
                        )
                    tmp.write(chunk)
            finally:
                tmp.close()

            if total == 0:
                return web.json_response(
                    {"success": False, "error": "empty audio upload"},
                    status=400,
                )

            # Imported lazily so tests can monkey-patch the attribute on
            # ``tools.transcription_tools`` after the relay is already up,
            # and so a missing hermes-agent venv doesn't blow up import.
            from tools.transcription_tools import transcribe_audio  # type: ignore

            result = await asyncio.to_thread(transcribe_audio, temp_path)

            if not isinstance(result, dict):
                logger.warning(
                    "transcribe_audio returned non-dict: %r", result
                )
                return web.json_response(
                    {
                        "success": False,
                        "error": "transcription backend returned unexpected shape",
                    },
                    status=500,
                )

            if not result.get("success"):
                return web.json_response(
                    {
                        "success": False,
                        "error": result.get("error", "transcription failed"),
                        "provider": result.get("provider"),
                    },
                    status=500,
                )

            return web.json_response(
                {
                    "success": True,
                    "text": result.get("transcript", ""),
                    "provider": result.get("provider"),
                }
            )
        except web.HTTPException:
            raise
        except Exception as exc:
            logger.exception("Voice transcribe failed: %s", exc)
            return web.json_response(
                {"success": False, "error": f"transcription error: {exc}"},
                status=500,
            )
        finally:
            if temp_path:
                try:
                    os.unlink(temp_path)
                except OSError:
                    pass

    # ── POST /voice/synthesize ──────────────────────────────────────────

    async def handle_synthesize(self, request: web.Request) -> web.StreamResponse:
        """Synthesize text, stream the mp3 file back.

        Reads ``{"text": "..."}`` as JSON, validates length, runs the sync
        ``text_to_speech_tool`` on a worker thread, then streams the
        resulting file via :class:`aiohttp.web.FileResponse`.

        TODO(voice): the TTS tool writes files under ``~/voice-memos/`` and
        never cleans them up. For V1 we leave that alone — clean-up is
        upstream's concern. A future LRU pass over that directory would
        pair nicely with the existing MediaRegistry LRU pattern.
        """
        _require_bearer_session(request)

        try:
            payload = await request.json()
        except (json.JSONDecodeError, ValueError):
            return web.json_response(
                {"success": False, "error": "invalid JSON body"},
                status=400,
            )

        if not isinstance(payload, dict):
            return web.json_response(
                {"success": False, "error": "body must be a JSON object"},
                status=400,
            )

        text = payload.get("text")
        if not isinstance(text, str):
            return web.json_response(
                {"success": False, "error": "'text' must be a string"},
                status=400,
            )
        text = text.strip()
        if not text:
            return web.json_response(
                {"success": False, "error": "'text' must not be empty"},
                status=400,
            )
        if len(text) > MAX_TEXT_CHARS:
            return web.json_response(
                {
                    "success": False,
                    "error": f"text exceeds {MAX_TEXT_CHARS} characters",
                },
                status=400,
            )

        # Strip markdown / tool annotations / URLs / emoji before TTS.
        # See ``plugin/relay/tts_sanitizer.py`` for the rules; upstream's
        # ``text_to_speech_tool`` does NOT run its own stripper so we
        # have to do it here.
        from .tts_sanitizer import sanitize_for_tts

        sanitized = sanitize_for_tts(text)
        if not sanitized:
            return web.json_response(
                {
                    "success": False,
                    "error": "'text' is empty after sanitization",
                },
                status=400,
            )
        text = sanitized

        try:
            from tools.tts_tool import text_to_speech_tool  # type: ignore

            raw = await asyncio.to_thread(text_to_speech_tool, text)
        except Exception as exc:
            logger.exception("Voice synthesize: TTS call failed: %s", exc)
            return web.json_response(
                {"success": False, "error": f"tts backend error: {exc}"},
                status=500,
            )

        # The upstream tool returns a JSON string, not a dict.
        try:
            result = json.loads(raw) if isinstance(raw, str) else raw
        except (json.JSONDecodeError, ValueError) as exc:
            logger.warning(
                "text_to_speech_tool returned non-JSON: %r", raw
            )
            return web.json_response(
                {
                    "success": False,
                    "error": f"tts backend returned non-JSON: {exc}",
                },
                status=500,
            )

        if not isinstance(result, dict) or not result.get("success"):
            err = (
                result.get("error", "tts failed")
                if isinstance(result, dict)
                else "tts returned unexpected shape"
            )
            return web.json_response(
                {"success": False, "error": err},
                status=500,
            )

        file_path = result.get("file_path")
        if not isinstance(file_path, str) or not file_path:
            return web.json_response(
                {"success": False, "error": "tts result missing file_path"},
                status=500,
            )
        if not os.path.isfile(file_path):
            return web.json_response(
                {
                    "success": False,
                    "error": f"tts file missing on disk: {file_path}",
                },
                status=500,
            )

        headers = {
            "Content-Type": "audio/mpeg",
            "Cache-Control": "private, no-store",
            "Content-Disposition": 'inline; filename="tts.mp3"',
        }
        return web.FileResponse(file_path, headers=headers)

    # ── GET /voice/config ───────────────────────────────────────────────

    async def handle_voice_config(self, request: web.Request) -> web.Response:
        """Report voice capability status to the phone.

        Aggregates:
          * ``check_voice_requirements()`` — global availability check.
          * ``_load_tts_config()`` / ``_load_stt_config()`` — current
            provider / model / voice selections from ``~/.hermes/config.yaml``.

        NOTE: ``_load_tts_config`` and ``_load_stt_config`` are *private*
        upstream helpers — they're not part of hermes-agent's public API.
        We use them here because there is no public alternative at V1 and
        the config shape is stable across recent versions. If upstream
        refactors, this handler is the only thing that needs patching.
        """
        _require_bearer_session(request)

        try:
            from tools.voice_mode import check_voice_requirements  # type: ignore
            from tools.tts_tool import _load_tts_config  # type: ignore  # private
            from tools.transcription_tools import _load_stt_config  # type: ignore  # private
        except Exception as exc:
            logger.warning("Voice config: imports failed: %s", exc)
            return web.json_response(
                {
                    "success": False,
                    "error": f"voice backend not importable: {exc}",
                },
                status=500,
            )

        try:
            requirements = check_voice_requirements()
        except Exception as exc:
            logger.warning("check_voice_requirements failed: %s", exc)
            requirements = {"error": str(exc)}

        try:
            tts_cfg = _load_tts_config() or {}
        except Exception as exc:
            logger.warning("_load_tts_config failed: %s", exc)
            tts_cfg = {"error": str(exc)}

        try:
            stt_cfg = _load_stt_config() or {}
        except Exception as exc:
            logger.warning("_load_stt_config failed: %s", exc)
            stt_cfg = {"error": str(exc)}

        return web.json_response(
            {
                "success": True,
                "tts": {
                    "provider": tts_cfg.get("provider"),
                    "voice_id": tts_cfg.get("voice_id"),
                    "model": tts_cfg.get("model"),
                    "enabled": bool(tts_cfg.get("provider")),
                },
                "stt": {
                    "provider": stt_cfg.get("provider"),
                    "model": stt_cfg.get("model"),
                    "enabled": bool(stt_cfg.get("provider")),
                },
                "requirements": requirements,
            }
        )
