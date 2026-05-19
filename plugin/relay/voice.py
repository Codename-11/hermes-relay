"""Voice endpoints — relay-side TTS / STT bridge for the Android voice mode.

The hermes-agent venv ships upstream STT/TTS helpers. The relay imports those
through ``plugin.relay.upstream_voice`` so upstream voice API drift has one
patch point. Upstream functions are synchronous, so every call is wrapped in
``asyncio.to_thread`` to keep the aiohttp event loop free.

Routes (bearer-auth'd via relay sessions or narrow Hermes API tokens):

* ``POST /voice/transcribe`` — multipart/form-data audio upload → JSON text
* ``POST /voice/synthesize`` — JSON ``{"text": ...}`` → ``audio/mpeg`` bytes
* ``GET  /voice/config``     — capabilities probe (what's configured)

Auth is handled by ``plugin.relay.voice_auth`` so the optional Hermes API
bearer path stays confined to safe voice routes.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import tempfile
from pathlib import Path
from typing import Any

from aiohttp import web
import yaml

from . import upstream_voice
from .profile_voice import request_profile, resolve_profile_voice_scope
from .voice_auth import require_voice_auth

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
        await require_voice_auth(request, "voice:stt")
        profile = request_profile(None, request.query)

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

            result = await asyncio.to_thread(
                upstream_voice.transcribe_audio_file,
                temp_path,
            )

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
                    "profile": profile,
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
        await require_voice_auth(request, "voice:tts")

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
            raw = await asyncio.to_thread(
                upstream_voice.synthesize_text_to_speech,
                text,
            )
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

        NOTE: voice config still depends on private upstream helpers. The
        imports are isolated in ``plugin.relay.upstream_voice`` until Hermes
        exposes a public voice config API.
        """
        await require_voice_auth(request, "voice:config")
        profile = request_profile(None, request.query)

        try:
            helpers = upstream_voice.load_voice_config_helpers()
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
            requirements = helpers.check_voice_requirements()
        except Exception as exc:
            logger.warning("check_voice_requirements failed: %s", exc)
            requirements = {"error": str(exc)}

        try:
            tts_cfg = helpers.load_tts_config() or {}
        except Exception as exc:
            logger.warning("_load_tts_config failed: %s", exc)
            tts_cfg = {"error": str(exc)}

        try:
            stt_cfg = helpers.load_stt_config() or {}
        except Exception as exc:
            logger.warning("_load_stt_config failed: %s", exc)
            stt_cfg = {"error": str(exc)}

        scope = resolve_profile_voice_scope(self.config, profile)
        hermes_voice_config = scope.data
        profile_selected = bool(scope.requested_profile)
        has_voice_section = isinstance(hermes_voice_config.get("tts"), dict) or isinstance(
            hermes_voice_config.get("stt"),
            dict,
        )
        response_scope = scope.scope
        response_fallback = scope.fallback_to_global
        if profile_selected and scope.scope == "profile" and not has_voice_section:
            response_scope = "global"
            response_fallback = True
        tts_cfg = _merge_selected_provider_config(
            tts_cfg,
            hermes_voice_config,
            "tts",
            prefer_loaded=not profile_selected,
        )
        stt_cfg = _merge_selected_provider_config(
            stt_cfg,
            hermes_voice_config,
            "stt",
            prefer_loaded=not profile_selected,
        )

        return web.json_response(
            {
                "success": True,
                "profile": scope.requested_profile,
                "config_scope": response_scope,
                "config_path": (
                    str(scope.config_path)
                    if scope.config_path and response_scope != "global"
                    else None
                ),
                "fallback_to_global": response_fallback,
                "tts": {
                    "provider": tts_cfg.get("provider"),
                    "voice_id": tts_cfg.get("voice_id"),
                    "voice": tts_cfg.get("voice"),
                    "model": tts_cfg.get("model"),
                    "enabled": _provider_enabled(tts_cfg),
                },
                "stt": {
                    "provider": stt_cfg.get("provider"),
                    "model": stt_cfg.get("model"),
                    "enabled": _provider_enabled(stt_cfg),
                },
                "requirements": requirements,
            }
        )


def _load_hermes_voice_config(config: Any) -> dict[str, Any]:
    path = Path(getattr(config, "hermes_config_path", "~/.hermes/config.yaml")).expanduser()
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        logger.debug("Voice config: could not read Hermes config details: %s", exc)
        return {}
    return raw if isinstance(raw, dict) else {}


def _merge_selected_provider_config(
    loaded: dict[str, Any],
    hermes_config: dict[str, Any],
    section_name: str,
    *,
    prefer_loaded: bool = True,
) -> dict[str, Any]:
    section = hermes_config.get(section_name)
    if not isinstance(section, dict):
        return loaded

    provider = (
        _clean_string(loaded.get("provider")) or _clean_string(section.get("provider"))
        if prefer_loaded
        else _clean_string(section.get("provider")) or _clean_string(loaded.get("provider"))
    )
    if not provider:
        return loaded

    selected = section.get(provider)
    merged: dict[str, Any] = {}
    if isinstance(selected, dict):
        merged.update(selected)
    merged["provider"] = provider
    if prefer_loaded:
        merged.update(_present_values(loaded))
    return merged


def _present_values(values: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in values.items()
        if value is not None and value != ""
    }


def _provider_enabled(config: dict[str, Any]) -> bool:
    if not config.get("provider"):
        return False
    enabled = config.get("enabled")
    if isinstance(enabled, bool):
        return enabled
    if enabled is None:
        return True
    return str(enabled).strip().lower() not in ("0", "false", "no", "off")


def _clean_string(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
