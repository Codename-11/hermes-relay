"""Adapter boundary for upstream hermes-agent voice helpers.

The relay exposes custom HTTP `/voice/*` routes, but the actual STT/TTS work
should stay delegated to hermes-agent's voice tools. Keep all imports from
`tools.tts_tool`, `tools.transcription_tools`, and `tools.voice_mode` in this
module so upstream API drift has one patch point.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class VoiceConfigHelpers:
    check_voice_requirements: Callable[[], Any]
    load_tts_config: Callable[[], dict[str, Any] | None]
    load_stt_config: Callable[[], dict[str, Any] | None]


def transcribe_audio_file(file_path: str) -> Any:
    """Call upstream's synchronous STT helper."""
    from tools.transcription_tools import transcribe_audio  # type: ignore

    return transcribe_audio(file_path)


def synthesize_text_to_speech(
    text: str,
    output_path: str | None = None,
    *,
    voice_overrides: dict[str, Any] | None = None,
) -> Any:
    """Call upstream's synchronous TTS helper.

    Passing ``output_path`` lets the relay own the artifact (and delete it
    after streaming) instead of leaving files behind in ``~/voice-memos``.
    Upstream rejects ``..`` traversal components, so callers always pass an
    absolute temp path.

    ``voice_overrides`` enables per-request enhanced control (Gemini:
    ``voice`` / ``model`` / ``audio_tags`` / ``persona_prompt``; xAI: ``voice``
    → ``voice_id``, ``audio_tags`` → ``auto_speech_tags``, ``language``)
    WITHOUT mutating the user's saved ``~/.hermes/config.yaml``.
    ``text_to_speech_tool`` has no per-call override surface, so for those
    providers the relay merges the overrides into a copy of the loaded config
    and invokes the provider generator directly. For any other provider — or
    when overrides are absent — the standard tool runs against saved config.
    """
    from tools.tts_tool import text_to_speech_tool  # type: ignore

    if voice_overrides:
        result = _synthesize_with_voice_overrides(text, output_path, voice_overrides)
        if result is not None:
            return result

    if output_path is not None:
        return text_to_speech_tool(text, output_path)
    return text_to_speech_tool(text)


def _synthesize_with_voice_overrides(
    text: str,
    output_path: str | None,
    overrides: dict[str, Any],
) -> dict[str, Any] | None:
    """Dispatch per-request enhanced overrides to the active provider's
    generator.

    Returns a ``text_to_speech_tool``-shaped result dict on success, or ``None``
    to tell the caller to fall back to the standard path (provider without a
    per-request surface, no output path, or unavailable upstream symbols).
    Generator exceptions (e.g. missing API key) propagate so the caller
    surfaces them.
    """
    import copy

    if output_path is None:
        return None
    try:
        from tools.tts_tool import _get_provider, _load_tts_config  # type: ignore
    except ImportError:
        return None

    base_config = _load_tts_config() or {}
    if not isinstance(base_config, dict):
        base_config = {}
    requested = str(overrides.get("provider") or "").strip().lower()
    effective = requested or str(_get_provider(base_config) or "").strip().lower()

    config = copy.deepcopy(base_config)
    config["provider"] = effective
    if effective == "gemini":
        return _synthesize_gemini(config, text, output_path, overrides)
    if effective == "xai":
        return _synthesize_xai(config, text, output_path, overrides)
    return None


def _synthesize_gemini(
    config: dict[str, Any],
    text: str,
    output_path: str,
    overrides: dict[str, Any],
) -> dict[str, Any] | None:
    """Merge Gemini overrides into ``config`` and run the Gemini generator.

    The audio-tag rewrite (``agent.auxiliary_client.call_llm``) fails soft
    upstream — unavailable in the relay process just yields untagged text — so
    enabling ``audio_tags`` never hard-fails synthesis.
    """
    import os
    import tempfile

    try:
        from tools.tts_tool import _generate_gemini_tts  # type: ignore
    except ImportError:
        return None

    raw = config.get("gemini")
    gemini = dict(raw) if isinstance(raw, dict) else {}
    for key in ("model", "voice", "base_url"):
        value = overrides.get(key)
        if isinstance(value, str) and value.strip():
            gemini[key] = value.strip()
    if overrides.get("audio_tags") is not None:
        gemini["audio_tags"] = bool(overrides["audio_tags"])

    # Upstream reads persona direction from a file; an inline per-request
    # persona is written to a short-lived temp file and removed after the call.
    persona_text = overrides.get("persona_prompt")
    persona_path: str | None = None
    if isinstance(persona_text, str) and persona_text.strip():
        fd, persona_path = tempfile.mkstemp(suffix=".md", prefix="hermes-tts-persona-")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(persona_text.strip())
        gemini["persona_prompt_file"] = persona_path

    config["gemini"] = gemini
    try:
        path = _generate_gemini_tts(text, output_path, config)
    finally:
        if persona_path:
            try:
                os.unlink(persona_path)
            except OSError:
                pass
    return {"success": True, "file_path": path}


def _synthesize_xai(
    config: dict[str, Any],
    text: str,
    output_path: str,
    overrides: dict[str, Any],
) -> dict[str, Any] | None:
    """Merge xAI overrides into ``config`` and run the xAI generator.

    Maps the generic override vocabulary onto xAI's config: ``voice`` →
    ``voice_id``, ``audio_tags`` → ``auto_speech_tags`` (xAI's expressive
    inline/wrapping speech markers), plus ``language``.
    """
    try:
        from tools.tts_tool import _generate_xai_tts  # type: ignore
    except ImportError:
        return None

    raw = config.get("xai")
    xai = dict(raw) if isinstance(raw, dict) else {}
    voice = overrides.get("voice")
    if isinstance(voice, str) and voice.strip():
        xai["voice_id"] = voice.strip()
    language = overrides.get("language")
    if isinstance(language, str) and language.strip():
        xai["language"] = language.strip()
    if overrides.get("audio_tags") is not None:
        xai["auto_speech_tags"] = bool(overrides["audio_tags"])

    config["xai"] = xai
    path = _generate_xai_tts(text, output_path, config)
    return {"success": True, "file_path": path}


def apply_xai_speech_tags(text: str) -> str:
    """Inject xAI's inline/wrapping expressive speech tags into TTS text.

    Wraps upstream's heuristic tagger (`_apply_xai_auto_speech_tags`) so the
    streaming `/voice/output` renderer (which streams text verbatim) can match
    the basic `/voice/synthesize` path's tone behavior. Fails soft — returns the
    original text if the upstream symbol is missing or raises.
    """
    try:
        from tools.tts_tool import _apply_xai_auto_speech_tags  # type: ignore
    except ImportError:
        return text
    try:
        tagged = _apply_xai_auto_speech_tags(text)
    except Exception:
        return text
    return tagged if isinstance(tagged, str) and tagged.strip() else text


def load_voice_config_helpers() -> VoiceConfigHelpers:
    """Load upstream voice configuration helpers.

    `_load_tts_config` and `_load_stt_config` are private upstream helpers. They
    are intentionally isolated here until Hermes exposes a public voice config
    API that can replace them.
    """
    from tools.voice_mode import check_voice_requirements  # type: ignore
    from tools.tts_tool import _load_tts_config  # type: ignore  # private
    from tools.transcription_tools import _load_stt_config  # type: ignore  # private

    return VoiceConfigHelpers(
        check_voice_requirements=check_voice_requirements,
        load_tts_config=_load_tts_config,
        load_stt_config=_load_stt_config,
    )
