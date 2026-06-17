"""Profile-scoped voice configuration helpers for relay-owned voice routes."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class ProfileVoiceScope:
    requested_profile: str | None
    profile: str | None
    scope: str
    fallback_to_global: bool
    config_path: Path | None
    data: dict[str, Any]


def request_profile(payload: dict[str, Any] | None, query: Any) -> str | None:
    """Return a profile name from query params or a JSON payload."""
    query_value = _string_value(query.get("profile")) if query is not None else None
    if query_value:
        return query_value
    if payload is not None:
        return _string_value(payload.get("profile"))
    return None


def resolve_profile_voice_scope(config: Any, profile: str | None) -> ProfileVoiceScope:
    """Load ``~/.hermes`` or ``~/.hermes/profiles/<name>`` config safely.

    Missing or invalid profile configs fall back to the root Hermes config
    when present. The fallback is explicit in [ProfileVoiceScope] so clients
    can display whether a setting came from the selected profile or global
    config.
    """
    requested = _string_value(profile)
    root_config = Path(getattr(config, "hermes_config_path", "~/.hermes/config.yaml")).expanduser()
    hermes_dir = root_config.parent

    if not requested:
        return ProfileVoiceScope(
            requested_profile=None,
            profile=None,
            scope="global",
            fallback_to_global=False,
            config_path=root_config if root_config.is_file() else None,
            data=_load_yaml_mapping(root_config),
        )

    if requested == "default":
        return ProfileVoiceScope(
            requested_profile=requested,
            profile="default",
            scope="profile",
            fallback_to_global=False,
            config_path=root_config if root_config.is_file() else None,
            data=_load_yaml_mapping(root_config),
        )

    profile_config: Path | None = None
    if _valid_profile_token(requested):
        candidate = hermes_dir / "profiles" / requested / "config.yaml"
        if candidate.is_file():
            profile_config = candidate

    if profile_config is not None:
        return ProfileVoiceScope(
            requested_profile=requested,
            profile=requested,
            scope="profile",
            fallback_to_global=False,
            config_path=profile_config,
            data=_load_yaml_mapping(profile_config),
        )

    return ProfileVoiceScope(
        requested_profile=requested,
        profile=requested,
        scope="global",
        fallback_to_global=True,
        config_path=root_config if root_config.is_file() else None,
        data=_load_yaml_mapping(root_config),
    )


def selected_provider_config(
    config_data: dict[str, Any],
    section_name: str,
) -> dict[str, Any]:
    section = config_data.get(section_name)
    if not isinstance(section, dict):
        return {}

    provider = _string_value(section.get("provider"))
    selected = section.get(provider) if provider else None
    merged: dict[str, Any] = {}
    if isinstance(selected, dict):
        merged.update(selected)
    for key, value in section.items():
        if key == provider or isinstance(value, dict):
            continue
        if value is not None and value != "":
            merged[key] = value
    if provider:
        merged["provider"] = provider
    return merged


def voice_output_settings(config: Any, profile: str | None) -> dict[str, Any]:
    scope = resolve_profile_voice_scope(config, profile)
    settings = {
        "enabled": bool(getattr(config, "voice_output_enabled", True)),
        "provider": getattr(config, "voice_output_provider", "xai_tts"),
        "model": getattr(config, "voice_output_model", "xai-tts"),
        "voice": getattr(config, "voice_output_voice", "eve"),
        "sample_rate": getattr(config, "voice_output_sample_rate", 24000),
        "language": getattr(config, "voice_output_language", "en"),
        "codec": getattr(config, "voice_output_codec", "pcm"),
        "optimize_streaming_latency": getattr(
            config,
            "voice_output_optimize_streaming_latency",
            1,
        ),
        "text_normalization": bool(
            getattr(config, "voice_output_text_normalization", False)
        ),
        "auto_speech_tags": bool(
            getattr(config, "voice_output_auto_speech_tags", False)
        ),
        "fallback_enabled": bool(getattr(config, "voice_output_fallback_enabled", True)),
    }

    applied_scope = "relay"
    fallback = False
    if scope.requested_profile:
        overrides = _voice_output_overrides(scope.data)
        if overrides:
            settings.update(overrides)
            applied_scope = scope.scope
            fallback = scope.fallback_to_global
        else:
            fallback = True

    return {
        **settings,
        "profile": scope.requested_profile,
        "config_scope": applied_scope,
        "fallback_to_global": fallback,
        "config_path": scope.config_path if applied_scope != "relay" else None,
    }


def realtime_voice_settings(config: Any, profile: str | None) -> dict[str, Any]:
    scope = resolve_profile_voice_scope(config, profile)
    settings = {
        "enabled": bool(getattr(config, "realtime_voice_enabled", True)),
        "provider": getattr(config, "realtime_voice_provider", "xai_realtime"),
        "model": getattr(config, "realtime_voice_model", "grok-voice-latest"),
        "voice": getattr(config, "realtime_voice_voice", "eve"),
        "sample_rate": getattr(config, "realtime_voice_sample_rate", 24000),
        # ADR 33 background-Hermes-run promotion.
        "promotion_enabled": bool(
            getattr(config, "realtime_voice_promotion_enabled", False)
        ),
        "promote_after_ms": int(
            getattr(config, "realtime_voice_promote_after_ms", 6000)
        ),
        "background_default_mode": getattr(
            config, "realtime_voice_background_default_mode", "promote"
        ),
        "spoken_handoff": bool(getattr(config, "realtime_voice_spoken_handoff", True)),
        "progress_spoken_after_ms": int(
            getattr(config, "realtime_voice_progress_spoken_after_ms", 15000)
        ),
        "progress_repeat_ms": int(
            getattr(config, "realtime_voice_progress_repeat_ms", 30000)
        ),
        "result_delivery": getattr(
            config, "realtime_voice_result_delivery", "speak_when_idle"
        ),
        "max_background_runs": int(
            getattr(config, "realtime_voice_max_background_runs", 1)
        ),
    }

    applied_scope = "relay"
    fallback = False
    if scope.requested_profile:
        overrides = _realtime_voice_overrides(scope.data)
        if overrides:
            settings.update(overrides)
            applied_scope = scope.scope
            fallback = scope.fallback_to_global
        else:
            fallback = True

    return {
        **settings,
        "profile": scope.requested_profile,
        "config_scope": applied_scope,
        "fallback_to_global": fallback,
        "config_path": scope.config_path if applied_scope != "relay" else None,
    }


def save_profile_voice_section(
    config: Any,
    profile: str | None,
    section_name: str,
    updates: dict[str, Any],
) -> Path | None:
    """Persist voice defaults into a selected profile config.

    Returns ``None`` when there is no selected profile or the profile path is
    not writable/resolved, allowing callers to use relay-owned global config
    persistence instead.
    """
    if not _string_value(profile):
        return None
    scope = resolve_profile_voice_scope(config, profile)
    if scope.scope != "profile" or scope.config_path is None:
        return None

    data = _load_yaml_mapping(scope.config_path)
    section = data.get(section_name)
    if not isinstance(section, dict):
        section = {}
    section.update(updates)
    data[section_name] = section

    path = scope.config_path
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f".{path.name}.tmp")
    tmp_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    tmp_path.replace(path)
    return path


def _voice_output_overrides(data: dict[str, Any]) -> dict[str, Any]:
    section = selected_provider_config(data, "voice_output")
    source = "voice_output"
    if not section:
        section = selected_provider_config(data, "tts")
        source = "tts"
    if not section:
        return {}

    out: dict[str, Any] = {}
    provider = _normalize_provider(section.get("provider"), realtime=False)
    if provider:
        out["provider"] = provider
    model = _string_value(section.get("model") or section.get("model_id"))
    if model:
        out["model"] = model
    voice = _string_value(section.get("voice") or section.get("voice_id"))
    if voice:
        out["voice"] = voice
    sample_rate = _optional_int(section.get("sample_rate"))
    if sample_rate is not None:
        out["sample_rate"] = sample_rate

    if source == "voice_output":
        _copy_string(out, section, "language")
        _copy_string(out, section, "codec")
        _copy_int(out, section, "optimize_streaming_latency")
        _copy_bool(out, section, "text_normalization")
        _copy_bool(out, section, "auto_speech_tags")
        _copy_bool(out, section, "fallback_enabled")
        _copy_bool(out, section, "enabled")
    return out


def _realtime_voice_overrides(data: dict[str, Any]) -> dict[str, Any]:
    section = selected_provider_config(data, "realtime_voice")
    if not section:
        return {}

    out: dict[str, Any] = {}
    provider = _normalize_provider(section.get("provider"), realtime=True)
    if provider:
        out["provider"] = provider
    model = _string_value(section.get("model") or section.get("model_id"))
    if model:
        out["model"] = model
    voice = _string_value(section.get("voice") or section.get("voice_id"))
    if voice:
        out["voice"] = voice
    sample_rate = _optional_int(section.get("sample_rate"))
    if sample_rate is not None:
        out["sample_rate"] = sample_rate
    _copy_bool(out, section, "enabled")
    # ADR 33 promotion overrides.
    _copy_bool(out, section, "promotion_enabled")
    _copy_int(out, section, "promote_after_ms")
    _copy_string(out, section, "background_default_mode")
    _copy_bool(out, section, "spoken_handoff")
    _copy_int(out, section, "progress_spoken_after_ms")
    _copy_int(out, section, "progress_repeat_ms")
    _copy_string(out, section, "result_delivery")
    _copy_int(out, section, "max_background_runs")
    return out


def _normalize_provider(value: Any, *, realtime: bool) -> str | None:
    provider = _string_value(value)
    if not provider:
        return None
    normalized = provider.lower().replace("-", "_")
    aliases = {
        "xai": "xai_realtime" if realtime else "xai_tts",
        "grok": "xai_realtime" if realtime else "xai_tts",
        "xai_tts": "xai_tts",
        "xai_realtime": "xai_realtime",
        "openai": "openai_realtime" if realtime else "openai_tts",
        "openai_tts": "openai_tts",
        "openai_realtime": "openai_realtime",
        "elevenlabs": "elevenlabs_tts",
        "elevenlabs_tts": "elevenlabs_tts",
        "stub": "stub",
    }
    return aliases.get(normalized, provider)


def _load_yaml_mapping(path: str | Path) -> dict[str, Any]:
    try:
        raw = yaml.safe_load(Path(path).expanduser().read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        logger.debug("Could not read profile voice config at %s: %s", path, exc)
        return {}
    return raw if isinstance(raw, dict) else {}


def _valid_profile_token(value: str) -> bool:
    return bool(value) and "/" not in value and "\\" not in value and value not in (".", "..")


def _string_value(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _optional_bool(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if value is None:
        return None
    text = str(value).strip().lower()
    if text in ("1", "true", "yes", "on"):
        return True
    if text in ("0", "false", "no", "off"):
        return False
    return None


def _copy_string(out: dict[str, Any], source: dict[str, Any], key: str) -> None:
    value = _string_value(source.get(key))
    if value:
        out[key] = value


def _copy_int(out: dict[str, Any], source: dict[str, Any], key: str) -> None:
    value = _optional_int(source.get(key))
    if value is not None:
        out[key] = value


def _copy_bool(out: dict[str, Any], source: dict[str, Any], key: str) -> None:
    value = _optional_bool(source.get(key))
    if value is not None:
        out[key] = value
