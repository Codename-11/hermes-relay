"""Short-lived realtime credentials minted by Hermes without exposing its sign-in."""

from __future__ import annotations

from .base import ProviderUnavailable


def mint_client_secret(profile: str | None = None) -> str | None:
    """Mint once per connection; older Hermes keeps the existing explicit-auth path."""
    try:
        from hermes_cli.plugin_provider_requests import (
            ProviderNotSignedIn,
            credentialed_provider_request,
            provider_request_capabilities,
        )
    except ImportError:
        return None
    if "xai-oauth" not in provider_request_capabilities().get("providers", []):
        return None

    from hermes_constants import reset_hermes_home_override, set_hermes_home_override
    from hermes_cli.profiles import get_profile_dir

    scope = set_hermes_home_override(get_profile_dir(profile)) if profile else None
    try:
        try:
            response = credentialed_provider_request(
                "xai-oauth", "POST", "https://api.x.ai/v1/realtime/client_secrets",
                json={"expires_after": {"seconds": 300}}, timeout=20.0,
            )
        except ProviderNotSignedIn:
            return None
        except Exception:
            raise ProviderUnavailable("Hermes could not mint an xAI realtime client secret.") from None
        if response.status != 200:
            raise ProviderUnavailable(f"xAI refused the realtime client secret ({response.status}).")
        try:
            body = response.json()
        except ValueError:
            raise ProviderUnavailable("Invalid xAI realtime client secret response.") from None
        if isinstance(body, dict):
            nested = body.get("client_secret")
            value = body.get("value") or (nested.get("value") if isinstance(nested, dict) else None)
            if isinstance(value, str) and value.strip():
                return value
        raise ProviderUnavailable("Missing xAI realtime client secret.")
    finally:
        if scope is not None:
            reset_hermes_home_override(scope)


def check_secret_destination(url: str, source: str) -> None:
    """A Hermes-minted credential is scoped to the official realtime endpoint."""
    if source == "hermes:xai-oauth" and url != "wss://api.x.ai/v1/realtime":
        raise ProviderUnavailable("Hermes xAI client secrets require wss://api.x.ai/v1/realtime.")
