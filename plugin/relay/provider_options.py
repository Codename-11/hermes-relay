"""Provider option discovery for relay-owned voice providers."""

from __future__ import annotations

import json
import os
import hashlib
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from ..voice_lab.auth import load_voice_lab_env_file

OPTION_FETCH_TIMEOUT_SECONDS = 5.0
DEFAULT_CACHE_TTL_SECONDS = 600
MAX_XAI_CUSTOM_VOICE_PAGES = 20
PROVIDER_OPTIONS_SCHEMA_VERSION = 1

XAI_VOICE_LABELS = {
    "eve": "Eve - energetic, upbeat",
    "ara": "Ara - warm, friendly",
    "rex": "Rex - confident, clear",
    "sal": "Sal - smooth, balanced",
    "leo": "Leo - authoritative, strong",
}
XAI_BUILTIN_VOICES = tuple(XAI_VOICE_LABELS)
XAI_RECOMMENDED_VOICES = ("eve", "ara", "sal")

OPENAI_TTS_VOICES = (
    "alloy",
    "ash",
    "ballad",
    "coral",
    "echo",
    "fable",
    "onyx",
    "nova",
    "sage",
    "shimmer",
    "verse",
    "marin",
    "cedar",
)
OPENAI_TTS_MODEL_VOICES = {
    "gpt-4o-mini-tts": list(OPENAI_TTS_VOICES),
    "tts-1": ["alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer"],
    "tts-1-hd": ["alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer"],
}
OPENAI_REALTIME_VOICES = (
    "alloy",
    "ash",
    "ballad",
    "coral",
    "echo",
    "sage",
    "shimmer",
    "verse",
    "marin",
    "cedar",
)
OPENAI_RECOMMENDED_VOICES = ("marin", "cedar", "coral")

_OPTION_CACHE: dict[tuple[str, str, str], tuple[float, dict[str, Any]]] = {}


@dataclass(frozen=True, slots=True)
class XAIOptionAuth:
    access_token: str
    base_url: str | None = None
    source: str | None = None


def static_provider_options() -> dict[str, Any]:
    return {
        "provider": {},
        "dynamic": {
            "attempted": False,
            "status": "static",
            "source": "provider_info",
        },
    }


def openai_static_provider_options() -> dict[str, Any]:
    provider = {
        "voice_groups": [
            {
                "id": "openai_builtin",
                "label": "OpenAI voices",
                "values": list(OPENAI_TTS_VOICES),
                "source": "openai_docs",
                "recommended": list(OPENAI_RECOMMENDED_VOICES),
            }
        ],
        "voice_metadata": _voice_metadata(
            OPENAI_TTS_VOICES,
            source="openai_docs",
            recommended=OPENAI_RECOMMENDED_VOICES,
        ),
        "model_voice_compatibility": OPENAI_TTS_MODEL_VOICES,
        "recommended_voices": list(OPENAI_RECOMMENDED_VOICES),
        "requires_manual_id": False,
    }
    return {
        "provider": provider,
        "dynamic": {
            "attempted": False,
            "status": "static",
            "source": "openai_docs",
            "message": (
                "OpenAI publishes built-in voice choices in docs/API schema; "
                "no general voice-list endpoint is used by the relay."
            ),
        },
    }


def fetch_voice_output_provider_options(
    provider_id: str,
    *,
    xai_auth: XAIOptionAuth | None = None,
) -> dict[str, Any]:
    cache_key = _cache_key("voice_output", provider_id, xai_auth)
    cached = _cache_get(cache_key)
    if cached is not None:
        return cached
    if provider_id == "elevenlabs_tts":
        result = _fetch_elevenlabs_provider_options()
    elif provider_id == "xai_tts":
        result = _fetch_xai_provider_options(xai_auth=xai_auth)
    elif provider_id == "openai_tts":
        result = openai_static_provider_options()
    else:
        result = static_provider_options()
    _cache_put(cache_key, result)
    return result


def fetch_realtime_provider_options(
    provider_id: str,
    *,
    xai_auth: XAIOptionAuth | None = None,
) -> dict[str, Any]:
    cache_key = _cache_key("realtime_voice", provider_id, xai_auth)
    cached = _cache_get(cache_key)
    if cached is not None:
        return cached
    if provider_id == "xai_realtime":
        result = _fetch_xai_provider_options(xai_auth=xai_auth)
    elif provider_id == "openai_realtime":
        result = _openai_realtime_static_provider_options()
    else:
        result = static_provider_options()
    _cache_put(cache_key, result)
    return result


def merge_provider_options(provider: dict[str, Any], override: dict[str, Any]) -> None:
    for key in ("models", "voices", "languages"):
        values = override.get(key)
        if isinstance(values, list):
            provider[key] = unique_strings(
                [*provider.get(key, []), *values],
            )
    values = override.get("sample_rates")
    if isinstance(values, list):
        provider["sample_rates"] = unique_ints(
            [*provider.get("sample_rates", []), *values],
        )
    for key in ("model_labels", "voice_labels", "language_labels", "voice_metadata"):
        labels = override.get(key)
        if isinstance(labels, dict):
            existing = provider.get(key)
            if not isinstance(existing, dict):
                existing = {}
            provider[key] = {**existing, **labels}
    for key in ("voice_groups", "recommended_voices"):
        values = override.get(key)
        if isinstance(values, list):
            provider[key] = values
    compatibility = override.get("model_voice_compatibility")
    if isinstance(compatibility, dict):
        provider["model_voice_compatibility"] = compatibility
    if "requires_manual_id" in override:
        provider["requires_manual_id"] = bool(override["requires_manual_id"])


def _fetch_elevenlabs_provider_options() -> dict[str, Any]:
    api_key = _elevenlabs_api_key()
    if not api_key:
        return {
            "provider": {},
            "dynamic": {
                "attempted": False,
                "status": "auth_missing",
                "source": "elevenlabs_api",
            },
        }

    base_url = os.getenv("ELEVENLABS_BASE_URL", "https://api.elevenlabs.io").rstrip("/")
    headers = {
        "Accept": "application/json",
        "xi-api-key": api_key,
    }
    voices_payload, voices_error = _get_json_result(
        f"{base_url}/v1/voices",
        headers=headers,
        label="ElevenLabs voices",
    )
    models_payload, models_error = _get_json_result(
        f"{base_url}/v1/models",
        headers=headers,
        label="ElevenLabs models",
    )

    provider: dict[str, Any] = {}
    messages = [message for message in (voices_error, models_error) if message]

    if voices_payload is not None:
        voices, labels = _parse_elevenlabs_voices(voices_payload)
        provider["voices"] = voices
        provider["voice_labels"] = labels
        provider["voice_groups"] = [
            {
                "id": "elevenlabs_account",
                "label": "ElevenLabs account voices",
                "values": voices,
                "source": "elevenlabs_api",
                "custom": True,
            }
        ]
        provider["voice_metadata"] = _voice_metadata(
            voices,
            source="elevenlabs_api",
            custom=True,
            labels=labels,
        )
        provider["requires_manual_id"] = False

    if models_payload is not None:
        models, labels, languages, language_labels = _parse_elevenlabs_models(
            models_payload,
        )
        provider["models"] = models
        provider["model_labels"] = labels
        provider["languages"] = languages
        provider["language_labels"] = language_labels

    status = "ok" if provider else "error"
    dynamic: dict[str, Any] = {
        "attempted": True,
        "status": status,
        "source": "elevenlabs_api",
        "voice_count": len(provider.get("voices", [])),
        "model_count": len(provider.get("models", [])),
    }
    if messages:
        dynamic["message"] = "; ".join(messages)
    return {"provider": provider, "dynamic": dynamic}


def _fetch_xai_provider_options(
    *,
    xai_auth: XAIOptionAuth | None,
) -> dict[str, Any]:
    auth = xai_auth or _env_xai_option_auth()
    if auth is None or not auth.access_token:
        voices = list(XAI_BUILTIN_VOICES)
        return {
            "provider": {
                "voices": voices,
                "voice_labels": dict(XAI_VOICE_LABELS),
                "voice_groups": [
                    {
                        "id": "xai_builtin",
                        "label": "xAI built-in voices",
                        "values": voices,
                        "source": "xai_docs",
                        "recommended": list(XAI_RECOMMENDED_VOICES),
                    }
                ],
                "voice_metadata": _voice_metadata(
                    voices,
                    source="xai_docs",
                    recommended=XAI_RECOMMENDED_VOICES,
                    labels=XAI_VOICE_LABELS,
                ),
                "model_voice_compatibility": {
                    "xai-tts": voices,
                    "grok-voice-latest": voices,
                },
                "recommended_voices": list(XAI_RECOMMENDED_VOICES),
                "requires_manual_id": False,
            },
            "dynamic": {
                "attempted": False,
                "status": "auth_missing",
                "source": "xai_api",
            },
        }

    base_url = _xai_http_base_url(auth.base_url)
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {auth.access_token}",
    }
    voices_payload, voices_error = _get_json_result(
        f"{base_url}/tts/voices",
        headers=headers,
        label="xAI voices",
    )
    custom_payloads, custom_error = _get_paginated_xai_custom_voices(
        base_url,
        headers=headers,
    )

    provider: dict[str, Any] = {
        "voice_labels": dict(XAI_VOICE_LABELS),
        "voice_groups": [],
        "voice_metadata": _voice_metadata(
            XAI_BUILTIN_VOICES,
            source="xai_api",
            recommended=XAI_RECOMMENDED_VOICES,
            labels=XAI_VOICE_LABELS,
        ),
        "recommended_voices": list(XAI_RECOMMENDED_VOICES),
        "requires_manual_id": False,
    }
    messages = [message for message in (voices_error, custom_error) if message]

    if voices_payload is not None:
        voices, labels = _parse_xai_voices(voices_payload)
        provider["voices"] = voices
        provider["voice_labels"] = {**provider["voice_labels"], **labels}
        provider["voice_groups"].append(
            {
                "id": "xai_builtin",
                "label": "xAI built-in voices",
                "values": voices,
                "source": "xai_api",
                "recommended": [
                    voice for voice in XAI_RECOMMENDED_VOICES if voice in voices
                ],
            }
        )

    custom_voices: list[str] = []
    custom_labels: dict[str, str] = {}
    for custom_payload in custom_payloads:
        page_voices, page_labels = _parse_xai_voices(custom_payload, custom=True)
        custom_voices.extend(page_voices)
        custom_labels.update(page_labels)
    custom_voices = unique_strings(custom_voices)
    if custom_voices:
        provider["voices"] = unique_strings(
            [*provider.get("voices", []), *custom_voices],
        )
        provider["voice_labels"] = {
            **provider["voice_labels"],
            **custom_labels,
        }
        provider["voice_groups"].append(
            {
                "id": "xai_custom",
                "label": "xAI custom voices",
                "values": custom_voices,
                "source": "xai_api",
                "custom": True,
            }
        )
        provider["voice_metadata"] = {
            **provider["voice_metadata"],
            **_voice_metadata(
                custom_voices,
                source="xai_api",
                custom=True,
                labels=custom_labels,
            ),
        }

    if provider.get("voices"):
        provider["model_voice_compatibility"] = {
            "xai-tts": list(provider.get("voices", [])),
            "grok-voice-latest": list(provider.get("voices", [])),
        }

    status = "ok" if provider.get("voices") else "error"
    dynamic: dict[str, Any] = {
        "attempted": True,
        "status": status,
        "source": "xai_api",
        "voice_count": len(provider.get("voices", [])),
        "custom_voice_count": len(custom_voices),
        "auth_source": auth.source,
        "cache_ttl_seconds": _cache_ttl_seconds(),
    }
    if messages:
        dynamic["message"] = "; ".join(messages)
    return {"provider": provider, "dynamic": dynamic}


def validate_provider_selection(
    provider: dict[str, Any],
    *,
    model: str | None,
    voice: str | None,
    sample_rate: int | None = None,
    language: str | None = None,
) -> dict[str, Any]:
    checks: list[dict[str, str]] = []
    model = (model or "").strip()
    voice = (voice or "").strip()
    language = (language or "").strip()

    models = set(_string_list(provider.get("models")))
    voices = set(_string_list(provider.get("voices")))
    languages = set(_string_list(provider.get("languages")))
    sample_rates = set(unique_ints(provider.get("sample_rates", [])))
    compatibility = provider.get("model_voice_compatibility")
    compatible_voices = None
    if isinstance(compatibility, dict) and model:
        raw = compatibility.get(model)
        if isinstance(raw, list):
            compatible_voices = set(_string_list(raw))

    if models and model:
        if model in models:
            checks.append(_check("model_known", "ok", f"Model {model} is advertised."))
        else:
            checks.append(
                _check(
                    "model_known",
                    "warning",
                    f"Model {model} is not advertised; treating as manual entry.",
                )
            )
    elif models:
        checks.append(_check("model_present", "error", "Model is required."))

    if voices and voice:
        if compatible_voices is not None and voice not in compatible_voices:
            checks.append(
                _check(
                    "voice_compatible",
                    "error",
                    f"Voice {voice} is not advertised for model {model}.",
                )
            )
        elif voice in voices:
            checks.append(_check("voice_known", "ok", f"Voice {voice} is advertised."))
        else:
            checks.append(
                _check(
                    "voice_known",
                    "warning",
                    f"Voice {voice} is not advertised; treating as manual entry.",
                )
            )
    elif voices:
        checks.append(_check("voice_present", "error", "Voice is required."))

    if sample_rates and sample_rate is not None:
        if sample_rate in sample_rates:
            checks.append(
                _check("sample_rate_known", "ok", f"Sample rate {sample_rate} Hz is advertised.")
            )
        else:
            checks.append(
                _check(
                    "sample_rate_known",
                    "error",
                    f"Sample rate {sample_rate} Hz is not advertised.",
                )
            )

    if languages and language:
        if language in languages:
            checks.append(_check("language_known", "ok", f"Language {language} is advertised."))
        else:
            checks.append(
                _check(
                    "language_known",
                    "warning",
                    f"Language {language} is not advertised; treating as manual entry.",
                )
            )

    valid = not any(check["status"] == "error" for check in checks)
    return {
        "valid": valid,
        "checks": checks,
        "summary": "ok" if valid else "invalid",
    }


def _get_json_result(
    url: str,
    *,
    headers: dict[str, str],
    label: str,
    tolerate_statuses: set[int] | None = None,
) -> tuple[Any | None, str | None]:
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(
            request,
            timeout=OPTION_FETCH_TIMEOUT_SECONDS,
        ) as response:
            return json.loads(response.read().decode("utf-8", errors="replace")), None
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")[:160]
        if tolerate_statuses and exc.code in tolerate_statuses:
            return None, f"{label} unavailable: HTTP {exc.code}"
        return None, f"{label} returned HTTP {exc.code}: {body}"
    except (OSError, TimeoutError, json.JSONDecodeError) as exc:
        return None, f"{label} unavailable: {exc.__class__.__name__}"


def _get_paginated_xai_custom_voices(
    base_url: str,
    *,
    headers: dict[str, str],
) -> tuple[list[Any], str | None]:
    pages: list[Any] = []
    token: str | None = None
    messages: list[str] = []
    for _ in range(MAX_XAI_CUSTOM_VOICE_PAGES):
        query = {"limit": "100"}
        if token:
            query["pagination_token"] = token
        url = f"{base_url}/custom-voices?{urllib.parse.urlencode(query)}"
        payload, error = _get_json_result(
            url,
            headers=headers,
            label="xAI custom voices",
            tolerate_statuses={403, 404},
        )
        if error:
            messages.append(error)
        if payload is None:
            break
        pages.append(payload)
        if not isinstance(payload, dict):
            break
        next_token = str(payload.get("pagination_token") or "").strip()
        if not next_token:
            break
        token = next_token
    else:
        messages.append("xAI custom voices stopped at pagination safety limit")
    return pages, "; ".join(messages) if messages else None


def _parse_elevenlabs_voices(data: Any) -> tuple[list[str], dict[str, str]]:
    if not isinstance(data, dict):
        return [], {}
    voices = []
    labels: dict[str, str] = {}
    for item in data.get("voices", []):
        if not isinstance(item, dict):
            continue
        voice_id = str(item.get("voice_id") or item.get("id") or "").strip()
        if not voice_id:
            continue
        voices.append(voice_id)
        name = str(item.get("name") or "").strip()
        if name:
            labels[voice_id] = name
    return unique_strings(voices), labels


def _parse_elevenlabs_models(
    data: Any,
) -> tuple[list[str], dict[str, str], list[str], dict[str, str]]:
    if not isinstance(data, list):
        return [], {}, [], {}
    models = []
    model_labels: dict[str, str] = {}
    languages = []
    language_labels: dict[str, str] = {}
    for item in data:
        if not isinstance(item, dict):
            continue
        if item.get("can_do_text_to_speech") is False:
            continue
        model_id = str(item.get("model_id") or item.get("id") or "").strip()
        if model_id:
            models.append(model_id)
            name = str(item.get("name") or "").strip()
            if name:
                model_labels[model_id] = name
        for language in item.get("languages", []):
            if not isinstance(language, dict):
                continue
            language_id = str(language.get("language_id") or "").strip()
            if not language_id:
                continue
            languages.append(language_id)
            name = str(language.get("name") or "").strip()
            if name:
                language_labels[language_id] = name
    return (
        unique_strings(models),
        model_labels,
        unique_strings(languages),
        language_labels,
    )


def _parse_xai_voices(
    data: Any,
    *,
    custom: bool = False,
) -> tuple[list[str], dict[str, str]]:
    items = _voice_items(data)
    voices = []
    labels: dict[str, str] = {}
    for item in items:
        voice_id = str(
            item.get("voice_id")
            or item.get("id")
            or item.get("voice")
            or ""
        ).strip()
        if not voice_id:
            continue
        voices.append(voice_id)
        name = str(item.get("name") or "").strip()
        if name:
            labels[voice_id] = f"{name} (custom)" if custom else name
        elif custom:
            labels[voice_id] = f"{voice_id} (custom)"
    return unique_strings(voices), labels


def _voice_items(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, dict):
        for key in ("voices", "custom_voices", "data", "items"):
            value = data.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
        if any(key in data for key in ("voice_id", "id", "voice")):
            return [data]
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    return []


def _string_list(values: Any) -> list[str]:
    if not isinstance(values, list):
        return []
    return unique_strings(values)


def _check(check_id: str, status: str, message: str) -> dict[str, str]:
    return {"id": check_id, "status": status, "message": message}


def _elevenlabs_api_key() -> str:
    load_voice_lab_env_file()
    for name in ("ELEVENLABS_API_KEY", "VOICE_TOOLS_ELEVENLABS_KEY"):
        value = os.getenv(name, "").strip()
        if value:
            return value
    return ""


def _env_xai_option_auth() -> XAIOptionAuth | None:
    load_voice_lab_env_file()
    for name in (
        "VOICE_TOOLS_XAI_KEY",
        "XAI_API_KEY",
        "GROK_API_KEY",
        "XAI_REALTIME_CLIENT_SECRET",
        "XAI_EPHEMERAL_TOKEN",
        "VOICE_LAB_XAI_OAUTH_ACCESS_TOKEN",
    ):
        value = os.getenv(name, "").strip()
        if value:
            return XAIOptionAuth(
                access_token=value,
                base_url=os.getenv("XAI_BASE_URL") or None,
                source=f"env:{name}",
            )
    return None


def _openai_realtime_static_provider_options() -> dict[str, Any]:
    return {
        "provider": {
            "voice_groups": [
                {
                    "id": "openai_realtime_builtin",
                    "label": "OpenAI realtime voices",
                    "values": list(OPENAI_REALTIME_VOICES),
                    "source": "openai_docs",
                    "recommended": list(OPENAI_RECOMMENDED_VOICES),
                }
            ],
            "voice_metadata": _voice_metadata(
                OPENAI_REALTIME_VOICES,
                source="openai_docs",
                recommended=OPENAI_RECOMMENDED_VOICES,
            ),
            "model_voice_compatibility": {
                "gpt-realtime-2": list(OPENAI_REALTIME_VOICES),
            },
            "recommended_voices": list(OPENAI_RECOMMENDED_VOICES),
            "requires_manual_id": False,
        },
        "dynamic": {
            "attempted": False,
            "status": "static",
            "source": "openai_docs",
            "message": (
                "OpenAI realtime voice choices are advertised from the official "
                "docs/API schema; no general voice-list endpoint is used."
            ),
        },
    }


def _xai_http_base_url(value: str | None) -> str:
    base = (value or os.getenv("XAI_BASE_URL") or "https://api.x.ai/v1").strip()
    base = base.replace("wss://", "https://").replace("ws://", "http://")
    base = base.rstrip("/")
    if base.endswith("/realtime") or base.endswith("/tts"):
        base = base.rsplit("/", 1)[0]
    if not base.endswith("/v1"):
        base = f"{base}/v1"
    return base


def _voice_metadata(
    voices: Iterable[str],
    *,
    source: str,
    custom: bool = False,
    recommended: Iterable[str] = (),
    labels: dict[str, str] | None = None,
) -> dict[str, dict[str, Any]]:
    recommended_set = set(recommended)
    labels = labels or {}
    out: dict[str, dict[str, Any]] = {}
    for voice in voices:
        item: dict[str, Any] = {
            "source": source,
            "custom": custom,
            "recommended": voice in recommended_set,
            "experimental": False,
            "requires_manual_id": False,
        }
        label = labels.get(voice)
        if label:
            item["label"] = label
        out[voice] = item
    return out


def _cache_key(
    mode: str,
    provider_id: str,
    xai_auth: XAIOptionAuth | None,
) -> tuple[str, str, str]:
    auth_part = "none"
    if xai_auth and xai_auth.access_token:
        auth_part = f"{xai_auth.source or 'xai'}:{_secret_digest(xai_auth.access_token)}"
    elif provider_id.startswith("elevenlabs"):
        api_key = _elevenlabs_api_key()
        if api_key:
            auth_part = f"elevenlabs:{_secret_digest(api_key)}"
    return (mode, provider_id, auth_part)


def _cache_get(key: tuple[str, str, str]) -> dict[str, Any] | None:
    ttl = _cache_ttl_seconds()
    if ttl <= 0:
        return None
    item = _OPTION_CACHE.get(key)
    if item is None:
        return None
    expires_at, value = item
    if expires_at <= time.monotonic():
        _OPTION_CACHE.pop(key, None)
        return None
    result = json.loads(json.dumps(value))
    dynamic = result.setdefault("dynamic", {})
    dynamic["cache"] = "hit"
    dynamic["cache_ttl_seconds"] = ttl
    return result


def _cache_put(key: tuple[str, str, str], value: dict[str, Any]) -> None:
    ttl = _cache_ttl_seconds()
    if ttl <= 0:
        return
    stored = json.loads(json.dumps(value))
    dynamic = stored.setdefault("dynamic", {})
    dynamic["cache"] = "miss"
    dynamic["cache_ttl_seconds"] = ttl
    _OPTION_CACHE[key] = (time.monotonic() + ttl, stored)


def clear_provider_option_cache() -> None:
    _OPTION_CACHE.clear()


def _cache_ttl_seconds() -> int:
    try:
        return max(0, int(os.getenv("RELAY_PROVIDER_OPTIONS_CACHE_SECONDS", DEFAULT_CACHE_TTL_SECONDS)))
    except ValueError:
        return DEFAULT_CACHE_TTL_SECONDS


def _secret_digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()[:16]


def unique_strings(values: Iterable[Any]) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value).strip()
        if text and text not in seen:
            out.append(text)
            seen.add(text)
    return out


def unique_ints(values: Iterable[Any]) -> list[int]:
    out: list[int] = []
    seen: set[int] = set()
    for value in values:
        try:
            item = int(value)
        except (TypeError, ValueError):
            continue
        if item not in seen:
            out.append(item)
            seen.add(item)
    return out
