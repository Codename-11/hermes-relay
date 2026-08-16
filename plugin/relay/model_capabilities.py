"""Plugin-owned provider/model reasoning capability resolution.

The Relay extension cannot require changes to upstream Hermes.  This module
therefore reads only the selected profile's existing files and probes provider
endpoints directly with bounded aiohttp calls.  Cache keys include endpoint and
credential fingerprints; secrets are never retained in capability results or
returned on the wire.
"""

from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass
import hashlib
import json
import logging
import time
from pathlib import Path
from typing import Any, Iterable

import aiohttp

from .config import (
    RelayConfig,
    _effective_default_profile_home,
    _load_yaml_mapping,
)

logger = logging.getLogger("hermes_relay.model_capabilities")

SCHEMA_VERSION = 1
CONTRACT_VERSION = "1.0"
MAX_MODEL_PAIRS = 64
_MAX_OLLAMA_PROBES = 16
_MAX_PROBE_CONCURRENCY = 4
_CACHE_TTL_SECONDS = 300.0
_PROFILE_CONTEXT_TIMEOUT_SECONDS = 1.0
_REQUEST_TIMEOUT_SECONDS = 2.5
_PROBE_TIMEOUT_SECONDS = 1.25
_CANONICAL_EFFORTS = (
    "none",
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
    "ultra",
)
_EFFORT_RANK = {value: index for index, value in enumerate(_CANONICAL_EFFORTS)}

_PROVIDER_ALIASES = {
    "github": "copilot",
    "github-copilot": "copilot",
    "github-models": "copilot",
    "github-model": "copilot",
    "lm-studio": "lmstudio",
    "ollama": "ollama-cloud",
}


@dataclass(frozen=True)
class ReasoningCapability:
    efforts: tuple[str, ...]
    exact: bool
    source: str

    def wire(self, provider: str, model: str) -> dict[str, Any]:
        return {
            "provider": provider,
            "model": model,
            "reasoning": bool(self.efforts),
            "reasoning_efforts": list(self.efforts),
            "reasoning_efforts_exact": self.exact,
            "source": self.source,
        }


@dataclass(frozen=True)
class _ProfileContext:
    name: str
    home: Path
    config: dict[str, Any]
    env: dict[str, str]
    credential_pool: dict[str, list[dict[str, Any]]]


def _provider_key(provider: str) -> str:
    value = str(provider or "").strip().lower()
    return _PROVIDER_ALIASES.get(value, value)


def _model_key(model: str) -> str:
    return str(model or "").strip().lower().rsplit("/", 1)[-1]


def _ordered_efforts(values: Iterable[Any]) -> tuple[str, ...]:
    aliases = {"off": "none", "on": "medium"}
    selected = {
        aliases.get(str(value).strip().lower(), str(value).strip().lower())
        for value in values
    }
    return tuple(value for value in _CANONICAL_EFFORTS if value in selected)


def _static_capability(provider: str, model: str) -> ReasoningCapability:
    normalized_provider = _provider_key(provider)
    normalized_model = _model_key(model)

    if normalized_provider == "zai" and any(
        token in normalized_model for token in ("glm-5.2", "glm-5-2", "glm-5p2")
    ):
        return ReasoningCapability(("none", "high", "max"), True, "provider-adapter")

    deepseek_thinking = (
        normalized_model.startswith("deepseek-v")
        and not normalized_model.startswith("deepseek-v3")
    ) or normalized_model == "deepseek-reasoner"
    if normalized_provider in {"deepseek", "opencode-go"} and deepseek_thinking:
        return ReasoningCapability(
            ("none", "low", "medium", "high", "max"),
            True,
            "provider-adapter",
        )

    if normalized_provider == "opencode-go" and any(
        token in normalized_model for token in ("glm-5.2", "glm-5-2", "glm-5p2")
    ):
        return ReasoningCapability(("high", "max"), True, "provider-adapter")

    if normalized_provider == "kimi-for-coding" or (
        normalized_provider == "opencode-go" and normalized_model.startswith("kimi-k2")
    ):
        return ReasoningCapability(
            ("none", "low", "medium", "high"), True, "provider-adapter"
        )

    if normalized_provider == "upstage":
        if any(marker in normalized_model for marker in ("solar-mini", "syn-pro")):
            return ReasoningCapability((), True, "provider-adapter")
        return ReasoningCapability(
            ("none", "low", "medium", "high"), True, "provider-adapter"
        )

    if normalized_provider == "actual":
        return ReasoningCapability(
            ("none", "low", "medium", "high", "max"),
            True,
            "provider-adapter",
        )

    return ReasoningCapability(_CANONICAL_EFFORTS, False, "canonical-fallback")


def _static_capabilities(
    pairs: list[tuple[str, str]],
) -> list[ReasoningCapability]:
    """Apply plugin adapters plus upstream's stable no-reasoning signal."""
    results = [_static_capability(provider, model) for provider, model in pairs]
    try:
        from agent.models_dev import get_model_capabilities
    except Exception:
        return results
    models_dev_provider = {"copilot": "github-copilot"}
    for index, (provider, model) in enumerate(pairs):
        if results[index].exact:
            continue
        normalized = _provider_key(provider)
        try:
            metadata = get_model_capabilities(
                models_dev_provider.get(normalized, normalized), model
            )
        except Exception:
            continue
        if metadata is not None and metadata.supports_reasoning is False:
            results[index] = ReasoningCapability((), True, "models.dev")
    return results


def _fingerprint(secret: str) -> str:
    if not secret:
        return "anonymous"
    return hashlib.blake2b(secret.encode("utf-8"), digest_size=12).hexdigest()


def _chatgpt_account_id(access_token: str) -> str:
    """Extract the non-secret account routing claim from a Codex OAuth JWT."""
    try:
        payload = access_token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload))
        account_id = _mapping(claims.get("https://api.openai.com/auth")).get(
            "chatgpt_account_id"
        )
        return account_id if isinstance(account_id, str) else ""
    except (IndexError, ValueError, TypeError, json.JSONDecodeError):
        return ""


def _mapping(value: object) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _profile_model_config(context: _ProfileContext) -> dict[str, Any]:
    return _mapping(context.config.get("model"))


def _provider_config(context: _ProfileContext, provider: str) -> dict[str, Any]:
    providers = _mapping(context.config.get("providers"))
    return _mapping(providers.get(provider))


def _first_string(*values: object) -> str:
    for value in values:
        if value is not None:
            text = str(value).strip()
            if text:
                return text
    return ""


def _credential_pool_entries(path: Path, provider: str) -> list[dict[str, Any]]:
    try:
        if path.stat().st_size > 1_048_576:
            return []
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    pool = _mapping(_mapping(parsed).get("credential_pool"))
    entries = pool.get(provider)
    if not isinstance(entries, list):
        return []
    return [entry for entry in entries if isinstance(entry, dict)]


def _profile_secret(context: _ProfileContext, provider: str) -> str:
    model = _profile_model_config(context)
    configured = _provider_config(context, provider)
    if provider == "lmstudio":
        env_names = ("LM_API_KEY",)
    elif provider == "ollama-cloud":
        env_names = ("OLLAMA_API_KEY",)
    elif provider == "copilot":
        env_names = ("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")
    else:
        env_names = ()
    for name in env_names:
        value = context.env.get(name)
        if value:
            return value
    value = _first_string(configured.get("api_key"), configured.get("key"))
    if value:
        return value
    if _provider_key(str(model.get("provider") or "")) == provider:
        value = _first_string(model.get("api_key"), model.get("key"))
        if value:
            return value

    entries = context.credential_pool.get(provider, [])
    for entry in entries:
        # Capability discovery must not revive a credential that upstream has
        # marked unavailable. Conservatively fail to non-exact metadata; the
        # normal Hermes runtime owns cooldown expiry and token refresh.
        if str(entry.get("last_status") or "").strip().lower() in {"dead", "exhausted"}:
            continue
        value = _first_string(
            entry.get("access_token"),
            entry.get("api_key"),
            entry.get("token"),
            entry.get("key"),
        )
        if value:
            return value
    return ""


def _provider_base_url(context: _ProfileContext, provider: str) -> str:
    model = _profile_model_config(context)
    configured = _provider_config(context, provider)
    if provider == "openai-codex":
        return _first_string(
            context.env.get("OPENAI_CODEX_BASE_URL"),
            configured.get("base_url"),
            "https://chatgpt.com/backend-api/codex",
        ).rstrip("/")
    if provider == "lmstudio":
        env_name, default = "LM_BASE_URL", "http://127.0.0.1:1234/v1"
    elif provider == "ollama-cloud":
        env_name, default = "OLLAMA_BASE_URL", "https://ollama.com/v1"
    else:
        return _first_string(
            context.env.get("COPILOT_BASE_URL"),
            context.env.get("GITHUB_COPILOT_BASE_URL"),
            configured.get("base_url"),
            "https://api.githubcopilot.com",
        ).rstrip("/")
    model_url = (
        model.get("base_url")
        if _provider_key(str(model.get("provider") or "")) == provider
        else None
    )
    return _first_string(
        context.env.get(env_name), configured.get("base_url"), model_url, default
    ).rstrip("/")


class ModelCapabilityResolver:
    """Resolve exact capabilities with bounded, profile-isolated probes."""

    def __init__(self, config: RelayConfig) -> None:
        self._config = config
        self._cache: dict[
            tuple[str, str, str, str, str], tuple[ReasoningCapability, float]
        ] = {}
        self._cache_lock = asyncio.Lock()
        self._profile_generations: dict[str, int] = {}
        # One limiter for every dynamic provider and every HTTP request. A
        # burst of concurrent inventory refreshes cannot multiply LM Studio,
        # Ollama, and Copilot sessions beyond this process-wide resolver cap.
        self._probe_semaphore = asyncio.Semaphore(_MAX_PROBE_CONCURRENCY)

    def _profile_context(self, profile: str) -> _ProfileContext | None:
        root_config = Path(self._config.hermes_config_path).expanduser()
        root_home = root_config.parent
        if profile == "default":
            home = _effective_default_profile_home(root_home)
        else:
            if not profile or profile in {".", ".."} or Path(profile).name != profile:
                return None
            home = root_home / "profiles" / profile
            if not home.is_dir():
                return None
        config_path = home / "config.yaml"
        if not config_path.is_file():
            return None
        config = _load_yaml_mapping(config_path)
        try:
            from agent.secret_scope import build_profile_secret_scope

            env = build_profile_secret_scope(home)
        except Exception:
            # Older supported upstream builds predate secret_scope. The plugin
            # parser is read-only and never mutates process-global os.environ.
            from .config import _profile_dotenv_values

            env = _profile_dotenv_values(home)

        credential_pool: dict[str, list[dict[str, Any]]] = {}
        try:
            from agent.secret_scope import (
                reset_secret_scope,
                set_secret_scope,
            )
            from hermes_constants import (
                reset_hermes_home_override,
                set_hermes_home_override,
            )
            from hermes_cli.auth import read_credential_pool

            home_token = set_hermes_home_override(home)
            secret_token = set_secret_scope(env)
            try:
                loaded_pool = read_credential_pool(None)
            finally:
                reset_secret_scope(secret_token)
                reset_hermes_home_override(home_token)
            if isinstance(loaded_pool, dict):
                credential_pool = {
                    str(key): [entry for entry in value if isinstance(entry, dict)]
                    for key, value in loaded_pool.items()
                    if isinstance(value, list)
                }
        except Exception:
            # Compatibility fallback mirrors upstream's per-provider root
            # fallback without executing subprocesses or changing env state.
            for provider in (
                "copilot",
                "lmstudio",
                "ollama-cloud",
                "openai-codex",
            ):
                entries = _credential_pool_entries(home / "auth.json", provider)
                if not entries and root_home != home:
                    entries = _credential_pool_entries(
                        root_home / "auth.json", provider
                    )
                if entries:
                    credential_pool[provider] = entries
        return _ProfileContext(profile, home, config, env, credential_pool)

    async def _cached(
        self, key: tuple[str, str, str, str, str]
    ) -> ReasoningCapability | None:
        now = time.monotonic()
        async with self._cache_lock:
            expired = [
                item for item, (_value, expiry) in self._cache.items() if expiry <= now
            ]
            for item in expired:
                self._cache.pop(item, None)
            entry = self._cache.get(key)
            return entry[0] if entry else None

    async def _store(
        self,
        key: tuple[str, str, str, str, str],
        capability: ReasoningCapability,
        generation: int,
    ) -> bool:
        async with self._cache_lock:
            if self._profile_generations.get(key[0], 0) != generation:
                return False
            self._cache[key] = (
                capability,
                time.monotonic() + _CACHE_TTL_SECONDS,
            )
            return True

    async def _generation(self, profile: str) -> int:
        async with self._cache_lock:
            return self._profile_generations.get(profile, 0)

    async def _clear(self, profile: str) -> int:
        async with self._cache_lock:
            generation = self._profile_generations.get(profile, 0) + 1
            self._profile_generations[profile] = generation
            for key in [item for item in self._cache if item[0] == profile]:
                self._cache.pop(key, None)
            return generation

    async def resolve_many(
        self,
        pairs: list[tuple[str, str]],
        *,
        profile: str = "default",
        refresh: bool = False,
    ) -> list[dict[str, Any]]:
        try:
            context = await asyncio.wait_for(
                asyncio.to_thread(self._profile_context, profile),
                timeout=_PROFILE_CONTEXT_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            logger.info("Profile credential resolution timed out for %s", profile)
            return [
                _static_capability(provider, model).wire(provider, model)
                for provider, model in pairs
            ]
        if context is None:
            raise KeyError(profile)
        if refresh:
            generation = await self._clear(context.name)
        else:
            generation = await self._generation(context.name)

        async def _run() -> list[ReasoningCapability]:
            results = await asyncio.to_thread(_static_capabilities, pairs)
            lm_indexes = [
                i
                for i, pair in enumerate(pairs)
                if _provider_key(pair[0]) == "lmstudio"
            ]
            ollama_indexes = [
                i
                for i, pair in enumerate(pairs)
                if _provider_key(pair[0]) == "ollama-cloud"
            ]
            copilot_indexes = [
                i for i, pair in enumerate(pairs) if _provider_key(pair[0]) == "copilot"
            ]
            codex_indexes = [
                i
                for i, pair in enumerate(pairs)
                if _provider_key(pair[0]) == "openai-codex"
            ]

            await asyncio.gather(
                self._resolve_lmstudio(context, pairs, lm_indexes, results, generation),
                self._resolve_ollama(
                    context, pairs, ollama_indexes, results, generation
                ),
                self._resolve_copilot(
                    context, pairs, copilot_indexes, results, generation
                ),
                self._resolve_codex(
                    context, pairs, codex_indexes, results, generation
                ),
            )
            return results

        try:
            resolved = await asyncio.wait_for(_run(), timeout=_REQUEST_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            logger.info("Model capability resolution timed out for profile %s", profile)
            resolved = await asyncio.to_thread(_static_capabilities, pairs)
        return [
            capability.wire(provider, model)
            for (provider, model), capability in zip(pairs, resolved)
        ]

    async def _resolve_lmstudio(
        self,
        context: _ProfileContext,
        pairs: list[tuple[str, str]],
        indexes: list[int],
        results: list[ReasoningCapability],
        generation: int,
    ) -> None:
        if not indexes:
            return
        endpoint = _provider_base_url(context, "lmstudio")
        secret = _profile_secret(context, "lmstudio")
        pending: list[int] = []
        for index in indexes:
            key = (
                context.name,
                "lmstudio",
                pairs[index][1].lower(),
                endpoint.lower(),
                _fingerprint(secret),
            )
            cached = await self._cached(key)
            if cached is not None:
                results[index] = cached
            else:
                pending.append(index)
        if not pending:
            return
        server = endpoint[:-3] if endpoint.endswith("/v1") else endpoint
        headers = {"Authorization": f"Bearer {secret}"} if secret else {}
        try:
            async with self._probe_semaphore:
                timeout = aiohttp.ClientTimeout(total=_PROBE_TIMEOUT_SECONDS)
                async with aiohttp.ClientSession(
                    timeout=timeout, headers=headers
                ) as session:
                    async with session.get(f"{server}/api/v1/models") as response:
                        if response.status != 200:
                            return
                        payload = await response.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
            return
        items = payload.get("models") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            items = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            return
        options: dict[str, tuple[str, ...]] = {}
        for item in items:
            if not isinstance(item, dict):
                continue
            reasoning = _mapping(_mapping(item.get("capabilities")).get("reasoning"))
            allowed = reasoning.get("allowed_options")
            if not isinstance(allowed, list):
                continue
            efforts = _ordered_efforts(allowed)
            for field in ("id", "key"):
                identifier = str(item.get(field) or "").strip()
                if identifier:
                    options[identifier.lower()] = efforts
        for index in pending:
            model = pairs[index][1]
            if model.lower() not in options:
                continue
            capability = ReasoningCapability(
                options[model.lower()], True, "provider-catalog"
            )
            results[index] = capability
            await self._store(
                (
                    context.name,
                    "lmstudio",
                    model.lower(),
                    endpoint.lower(),
                    _fingerprint(secret),
                ),
                capability,
                generation,
            )

    async def _resolve_ollama(
        self,
        context: _ProfileContext,
        pairs: list[tuple[str, str]],
        indexes: list[int],
        results: list[ReasoningCapability],
        generation: int,
    ) -> None:
        endpoint = _provider_base_url(context, "ollama-cloud")
        secret = _profile_secret(context, "ollama-cloud")
        server = endpoint[:-3] if endpoint.endswith("/v1") else endpoint

        async def _probe(index: int) -> None:
            provider, model = pairs[index]
            key = (
                context.name,
                "ollama-cloud",
                model.lower(),
                endpoint.lower(),
                _fingerprint(secret),
            )
            cached = await self._cached(key)
            if cached is not None:
                results[index] = cached
                return
            async with self._probe_semaphore:
                headers = {"Authorization": f"Bearer {secret}"} if secret else {}
                try:
                    timeout = aiohttp.ClientTimeout(total=_PROBE_TIMEOUT_SECONDS)
                    async with aiohttp.ClientSession(
                        timeout=timeout, headers=headers
                    ) as session:
                        async with session.post(
                            f"{server}/api/show",
                            json={"name": model.split(":cloud", 1)[0]},
                        ) as response:
                            if response.status != 200:
                                return
                            payload = await response.json(content_type=None)
                except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
                    return
            capabilities = (
                payload.get("capabilities") if isinstance(payload, dict) else None
            )
            if not isinstance(capabilities, list):
                return
            efforts = (
                ("none", "low", "medium", "high", "max")
                if "thinking" in capabilities
                else ()
            )
            capability = ReasoningCapability(efforts, True, "provider-catalog")
            results[index] = capability
            await self._store(key, capability, generation)

        await asyncio.gather(*(_probe(index) for index in indexes[:_MAX_OLLAMA_PROBES]))

    async def _resolve_copilot(
        self,
        context: _ProfileContext,
        pairs: list[tuple[str, str]],
        indexes: list[int],
        results: list[ReasoningCapability],
        generation: int,
    ) -> None:
        if not indexes:
            return
        raw_token = _profile_secret(context, "copilot")
        if not raw_token:
            return
        account_scope = _fingerprint(raw_token)
        endpoint = _provider_base_url(context, "copilot")
        cache_endpoint = endpoint
        pending: list[int] = []
        for index in indexes:
            key = (
                context.name,
                "copilot",
                pairs[index][1].lower(),
                endpoint.lower(),
                account_scope,
            )
            cached = await self._cached(key)
            if cached is not None:
                results[index] = cached
            else:
                pending.append(index)
        if not pending:
            return
        try:
            async with self._probe_semaphore:
                api_token, endpoint = await self._copilot_api_token(raw_token, endpoint)
                timeout = aiohttp.ClientTimeout(total=_PROBE_TIMEOUT_SECONDS)
                headers = {
                    "Authorization": f"Bearer {api_token}",
                    "Copilot-Integration-Id": "vscode-chat",
                    "Editor-Version": "vscode/1.104.1",
                    "User-Agent": "HermesRelay/1.0",
                }
                async with aiohttp.ClientSession(
                    timeout=timeout, headers=headers
                ) as session:
                    async with session.get(
                        f"{endpoint.rstrip('/')}/models"
                    ) as response:
                        if response.status != 200:
                            return
                        payload = await response.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, KeyError):
            return
        items = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            return
        by_id = {
            str(item.get("id") or "").strip().lower(): item
            for item in items
            if isinstance(item, dict) and str(item.get("id") or "").strip()
        }
        for index in pending:
            model = pairs[index][1]
            item = by_id.get(model.lower())
            # A catalog is authoritative only for rows it actually returned.
            # Missing IDs may be aliases, stale picker entries, or rollout
            # differences; keep their static non-exact fallback untouched.
            if item is None:
                continue
            efforts: tuple[str, ...] = ()
            supports = _mapping(_mapping(item.get("capabilities")).get("supports"))
            raw_efforts = supports.get("reasoning_effort")
            if isinstance(raw_efforts, list):
                efforts = _ordered_efforts(raw_efforts)
            capability = ReasoningCapability(efforts, True, "github-catalog")
            results[index] = capability
            await self._store(
                (
                    context.name,
                    "copilot",
                    model.lower(),
                    cache_endpoint.lower(),
                    account_scope,
                ),
                capability,
                generation,
            )

    async def _resolve_codex(
        self,
        context: _ProfileContext,
        pairs: list[tuple[str, str]],
        indexes: list[int],
        results: list[ReasoningCapability],
        generation: int,
    ) -> None:
        if not indexes:
            return
        access_token = _profile_secret(context, "openai-codex")
        if not access_token:
            return
        endpoint = _provider_base_url(context, "openai-codex")
        account_scope = _fingerprint(access_token)
        pending: list[int] = []
        for index in indexes:
            key = (
                context.name,
                "openai-codex",
                pairs[index][1].lower(),
                endpoint.lower(),
                account_scope,
            )
            cached = await self._cached(key)
            if cached is not None:
                results[index] = cached
            else:
                pending.append(index)
        if not pending:
            return

        headers = {"Authorization": f"Bearer {access_token}"}
        account_id = _chatgpt_account_id(access_token)
        if account_id:
            headers["ChatGPT-Account-Id"] = account_id
        try:
            async with self._probe_semaphore:
                timeout = aiohttp.ClientTimeout(total=_PROBE_TIMEOUT_SECONDS)
                async with aiohttp.ClientSession(
                    timeout=timeout, headers=headers
                ) as session:
                    async with session.get(
                        f"{endpoint}/models?client_version=1.0.0"
                    ) as response:
                        if response.status != 200:
                            return
                        payload = await response.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
            return

        items = payload.get("models") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            return
        by_id: dict[str, dict[str, Any]] = {}
        for item in items:
            if not isinstance(item, dict):
                continue
            identifier = _first_string(item.get("slug"), item.get("id")).lower()
            if identifier:
                by_id[identifier] = item
        for index in pending:
            model = pairs[index][1]
            item = by_id.get(_model_key(model))
            if item is None:
                continue
            raw_levels = item.get("supported_reasoning_levels")
            if not isinstance(raw_levels, list):
                continue
            efforts = _ordered_efforts(
                level.get("effort") if isinstance(level, dict) else level
                for level in raw_levels
            )
            capability = ReasoningCapability(efforts, True, "provider-catalog")
            results[index] = capability
            await self._store(
                (
                    context.name,
                    "openai-codex",
                    model.lower(),
                    endpoint.lower(),
                    account_scope,
                ),
                capability,
                generation,
            )

    async def _copilot_api_token(
        self, raw_token: str, endpoint: str
    ) -> tuple[str, str]:
        # Exchanged Copilot tokens are semicolon-delimited; raw GitHub tokens
        # must be exchanged without invoking profile-global upstream state.
        if ";" in raw_token and "=" in raw_token:
            return raw_token, endpoint
        timeout = aiohttp.ClientTimeout(total=_PROBE_TIMEOUT_SECONDS)
        headers = {
            "Authorization": f"token {raw_token}",
            "Accept": "application/json",
            "Editor-Version": "vscode/1.104.1",
            "User-Agent": "GitHubCopilotChat/0.26.7",
        }
        async with aiohttp.ClientSession(timeout=timeout, headers=headers) as session:
            async with session.get(
                "https://api.github.com/copilot_internal/v2/token"
            ) as response:
                if response.status != 200:
                    raise ValueError("Copilot token exchange rejected")
                payload = await response.json(content_type=None)
        api_token = str(payload.get("token") or "").strip()
        if not api_token:
            raise ValueError("Copilot token exchange returned no token")
        endpoints = _mapping(payload.get("endpoints"))
        api_endpoint = _first_string(endpoints.get("api"), endpoint).rstrip("/")
        return api_token, api_endpoint
