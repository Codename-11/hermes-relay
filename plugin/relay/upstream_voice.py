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


def synthesize_text_to_speech(text: str) -> Any:
    """Call upstream's synchronous TTS helper."""
    from tools.tts_tool import text_to_speech_tool  # type: ignore

    return text_to_speech_tool(text)


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
