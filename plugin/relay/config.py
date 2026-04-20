"""Relay configuration — loaded from environment variables and config files."""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

logger = logging.getLogger(__name__)

# Characters used for pairing codes. Full A-Z + 0-9 alphabet (36 chars) so
# codes the phone generates with AuthManager.PAIRING_CODE_CHARS always
# validate cleanly. The earlier "no ambiguous 0/O/1/I" restriction only
# mattered when a human had to retype the code from a display; when the
# phone is the source of truth, that restriction silently rejected valid
# codes containing any of those four characters.
PAIRING_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
PAIRING_CODE_LENGTH = 6


@dataclass
class RelayConfig:
    """Configuration for the relay server."""

    host: str = "0.0.0.0"
    port: int = 8767
    ssl_cert: str | None = None
    ssl_key: str | None = None
    webapi_url: str = "http://localhost:8642"
    hermes_config_path: str = "~/.hermes/config.yaml"
    log_level: str = "INFO"
    profiles: list[dict[str, Any]] = field(default_factory=list)
    terminal_shell: str | None = None

    # Profile discovery — scan ``~/.hermes/profiles/*/`` for upstream-style
    # isolated profile directories. When False, ``_load_profiles`` returns an
    # empty list without touching the filesystem. Mirrors the existing
    # env-var-driven toggle pattern used for the media knobs above
    # (``RELAY_MEDIA_STRICT_SANDBOX`` etc.). Set
    # ``RELAY_PROFILE_DISCOVERY_ENABLED=0`` to disable.
    profile_discovery_enabled: bool = True

    # Absolute path to the SessionManager persistence file. When ``None``
    # (the default — the convention tests rely on), SessionManager runs
    # fully in-memory and restarting the relay wipes paired devices.
    # ``RelayConfig.from_env`` sets this to
    # ``<hermes_config_path.parent>/hermes-relay-sessions.json`` so the
    # real deployment keeps sessions across restarts automatically.
    # Override with ``RELAY_SESSIONS_FILE=/abs/path`` (set to empty
    # string to force in-memory mode even in production — rare, useful
    # for stateless-container deployments that rely on an external
    # secret-manager side-channel).
    session_persistence_path: str | None = None

    # Media registry (inbound media for screenshot / attachment tools)
    media_max_size_mb: int = 100
    media_ttl_seconds: int = 86400
    media_lru_cap: int = 500
    # Extra allowed roots beyond the automatic tmp+workspace defaults.
    # MediaRegistry always appends these on top of its own defaults — they
    # do not replace the base list.
    media_allowed_roots: list[str] = field(default_factory=list)
    # Strict sandbox on /media/by-path. Default False: LLM-emitted
    # MEDIA:/abs/path markers are served as long as the file exists, is
    # a regular file, and fits under max_size. Set True (via
    # RELAY_MEDIA_STRICT_SANDBOX=1) to re-enable the allowed_roots check
    # on the phone-side direct-path route. The token path (loopback-only
    # /media/register) is ALWAYS strict regardless of this flag.
    media_strict_sandbox: bool = False

    @classmethod
    def from_env(cls) -> RelayConfig:
        """Build config from environment variables, falling back to defaults."""
        config = cls(
            host=os.getenv("RELAY_HOST", cls.host),
            port=int(os.getenv("RELAY_PORT", str(cls.port))),
            ssl_cert=os.getenv("RELAY_SSL_CERT"),
            ssl_key=os.getenv("RELAY_SSL_KEY"),
            webapi_url=os.getenv("RELAY_WEBAPI_URL", cls.webapi_url),
            hermes_config_path=os.getenv(
                "RELAY_HERMES_CONFIG", cls.hermes_config_path
            ),
            log_level=os.getenv("RELAY_LOG_LEVEL", cls.log_level),
            terminal_shell=os.getenv("RELAY_TERMINAL_SHELL") or None,
        )

        # ── Profile discovery toggle ────────────────────────────────────
        discovery = os.getenv("RELAY_PROFILE_DISCOVERY_ENABLED", "").strip().lower()
        if discovery in ("0", "false", "no", "off"):
            config.profile_discovery_enabled = False
        elif discovery in ("1", "true", "yes", "on"):
            config.profile_discovery_enabled = True

        # ── Media knobs ─────────────────────────────────────────────────
        media_max_size = os.getenv("RELAY_MEDIA_MAX_SIZE_MB")
        if media_max_size:
            try:
                config.media_max_size_mb = int(media_max_size)
            except ValueError:
                logger.warning(
                    "Invalid RELAY_MEDIA_MAX_SIZE_MB=%r — using default %d",
                    media_max_size,
                    config.media_max_size_mb,
                )

        media_ttl = os.getenv("RELAY_MEDIA_TTL_SECONDS")
        if media_ttl:
            try:
                config.media_ttl_seconds = int(media_ttl)
            except ValueError:
                logger.warning(
                    "Invalid RELAY_MEDIA_TTL_SECONDS=%r — using default %d",
                    media_ttl,
                    config.media_ttl_seconds,
                )

        media_lru = os.getenv("RELAY_MEDIA_LRU_CAP")
        if media_lru:
            try:
                config.media_lru_cap = int(media_lru)
            except ValueError:
                logger.warning(
                    "Invalid RELAY_MEDIA_LRU_CAP=%r — using default %d",
                    media_lru,
                    config.media_lru_cap,
                )

        media_roots = os.getenv("RELAY_MEDIA_ALLOWED_ROOTS")
        if media_roots:
            config.media_allowed_roots = [
                r.strip() for r in media_roots.split(os.pathsep) if r.strip()
            ]

        strict = os.getenv("RELAY_MEDIA_STRICT_SANDBOX", "").strip().lower()
        if strict in ("1", "true", "yes", "on"):
            config.media_strict_sandbox = True

        config.profiles = _load_profiles(
            config.hermes_config_path,
            enabled=config.profile_discovery_enabled,
        )

        # ── Session persistence file ────────────────────────────────────
        # When ``from_env`` builds the config (i.e. a real relay startup
        # via ``python -m plugin.relay``), default to the canonical
        # location alongside ``config.yaml``. ``RELAY_SESSIONS_FILE``
        # overrides; empty-string forces in-memory mode.
        raw_sessions_file = os.getenv("RELAY_SESSIONS_FILE")
        if raw_sessions_file is None:
            config.session_persistence_path = str(
                Path(config.hermes_config_path).expanduser().parent
                / "hermes-relay-sessions.json"
            )
        elif raw_sessions_file.strip() == "":
            config.session_persistence_path = None
        else:
            config.session_persistence_path = raw_sessions_file

        return config


def _extract_description_from_soul(soul_text: str) -> str:
    """Return the first non-blank line of a SOUL.md, stripped of leading
    markdown heading markers and surrounding whitespace.

    Returns an empty string if the file contains no textual content.
    """
    for raw_line in soul_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        # Strip leading '#' characters (markdown headings) then whitespace.
        cleaned = line.lstrip("#").strip()
        if cleaned:
            return cleaned
    return ""


def _pid_is_alive(pid: int) -> bool:
    """Return True if ``pid`` refers to a live process on this host.

    Uses the POSIX ``os.kill(pid, 0)`` "probe" pattern — signal 0 performs
    the permission/existence check without delivering a real signal. On
    Windows, ``os.kill`` with signal 0 on CPython is implemented via
    ``OpenProcess`` and returns success for live PIDs, ``OSError`` with
    ``EINVAL``/``ESRCH``/``EPERM``-ish errno for dead or inaccessible
    ones. We treat any ``OSError`` as "not running" — we prefer
    false-negatives here (the gateway will simply be flagged offline) to
    false-positives that would claim a dead daemon is live.
    """
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    except Exception:  # pragma: no cover — defensive
        return False
    return True


def _read_proc_start_time(pid: int) -> int | None:
    """Return field 22 of ``/proc/<pid>/stat`` (process start time in clock
    ticks since system boot), or ``None`` if the file cannot be read or
    parsed. Returns ``None`` on non-Linux hosts where ``/proc`` does not
    exist — callers then skip the start-time comparison.

    Field 22 is the stable "starttime" field from ``man 5 proc``. The
    second stat field — ``comm`` — may contain spaces/parens, so we parse
    by finding the last ``)`` and tokenizing the tail.
    """
    proc_stat = Path(f"/proc/{pid}/stat")
    try:
        raw = proc_stat.read_text(encoding="utf-8", errors="replace")
    except (OSError, FileNotFoundError):
        return None
    # Skip past "pid (comm) " — comm may contain spaces or parens, so
    # locate the final ")" and tokenize everything after it.
    paren = raw.rfind(")")
    if paren < 0:
        return None
    tail = raw[paren + 1 :].split()
    # After the closing paren, field 3 is "state" and field 22 is
    # "starttime". Zero-indexed in ``tail`` that's tail[0]=state,
    # tail[19]=starttime.
    if len(tail) < 20:
        return None
    try:
        return int(tail[19])
    except (TypeError, ValueError):
        return None


def _pid_matches_hermes(pid: int) -> bool:
    """Check ``/proc/<pid>/comm`` + ``/proc/<pid>/cmdline`` for a
    ``hermes``/``gateway`` token. Used as a secondary filter so a
    recycled PID belonging to some other daemon doesn't falsely report
    gateway_running.

    Returns ``True`` when either source mentions the expected identity,
    or when ``/proc`` is unavailable (Windows/macOS — we can't prove the
    mismatch, so we don't downgrade the signal). Returns ``False`` only
    when we successfully read a cmdline/comm that definitely is NOT
    hermes-related.
    """
    comm_path = Path(f"/proc/{pid}/comm")
    cmdline_path = Path(f"/proc/{pid}/cmdline")

    # If neither file is accessible (e.g. non-Linux dev host) we fall
    # back to "assume it matches" — the primary liveness check is the
    # start_time comparison.
    comm_readable = False
    try:
        comm_text = comm_path.read_text(encoding="utf-8", errors="replace").strip()
        comm_readable = True
    except (OSError, FileNotFoundError):
        comm_text = ""

    cmdline_readable = False
    try:
        cmdline_bytes = cmdline_path.read_bytes()
        cmdline_text = cmdline_bytes.replace(b"\0", b" ").decode(
            "utf-8", errors="replace"
        )
        cmdline_readable = True
    except (OSError, FileNotFoundError):
        cmdline_text = ""

    if not comm_readable and not cmdline_readable:
        # No /proc — platform can't help us, don't penalize.
        return True

    haystack = f"{comm_text}\n{cmdline_text}".lower()
    return "hermes" in haystack or "gateway" in haystack


def _probe_gateway_running(profile_home: Path) -> bool:
    """Check the per-profile ``gateway.pid`` file and probe liveness.

    The upstream Hermes CLI writes its daemon PID to ``<profile>/gateway.pid``
    on ``hermes platform start``. Upstream writes JSON with shape
    ``{"pid": N, "start_time": T, "kind": "hermes-gateway", "argv": [...]}``;
    older installs wrote a bare integer — we tolerate both.

    Beyond "PID exists" (cheap but vulnerable to PID reuse), we also:

    * Compare ``start_time`` from the pid file against field 22 of
      ``/proc/<pid>/stat`` on Linux. A reused PID belonging to a
      different process will have a later start-time and we return
      ``False``.
    * Verify ``/proc/<pid>/comm`` or ``/proc/<pid>/cmdline`` mentions
      ``hermes`` or ``gateway``. A PID pointing at (say) ``init`` or
      ``sshd`` reports ``False``.

    On platforms without ``/proc`` (Windows/macOS dev hosts) these
    secondary checks degrade to "don't penalize" — the primary
    ``os.kill(pid, 0)`` probe still runs. Returns ``False`` on any
    filesystem or parse error — the feature is advisory, not
    load-bearing.
    """
    pid_file = profile_home / "gateway.pid"
    try:
        if not pid_file.is_file():
            return False
        raw = pid_file.read_text(encoding="utf-8").strip()
        if not raw:
            return False
        # Upstream Hermes writes JSON — {"pid": N, "start_time": T, ...}.
        # Older installs wrote a bare integer; tolerate both.
        claimed_start_time: int | None = None
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                pid = int(parsed["pid"])
                st = parsed.get("start_time")
                if isinstance(st, (int, float)) and not isinstance(st, bool):
                    claimed_start_time = int(st)
            else:
                pid = int(parsed)
        except (json.JSONDecodeError, KeyError, TypeError):
            pid = int(raw.split()[0])
    except (OSError, ValueError, IndexError):
        return False
    except Exception:  # pragma: no cover — defensive
        return False

    if not _pid_is_alive(pid):
        return False

    # Start-time cross-check (Linux only — no /proc means None and we
    # skip this gate, trusting os.kill alone).
    if claimed_start_time is not None:
        actual_start_time = _read_proc_start_time(pid)
        if actual_start_time is not None and actual_start_time != claimed_start_time:
            logger.info(
                "gateway.pid at %s claims pid=%d start_time=%d but "
                "/proc reports start_time=%d — stale/reused PID, "
                "treating as not running",
                pid_file,
                pid,
                claimed_start_time,
                actual_start_time,
            )
            return False

    # Identity cross-check. On non-Linux this always returns True; on
    # Linux a PID pointing at e.g. init or sshd trips False.
    if not _pid_matches_hermes(pid):
        logger.info(
            "gateway.pid at %s points at pid=%d but /proc/comm + "
            "/proc/cmdline contain neither 'hermes' nor 'gateway' — "
            "treating as not running",
            pid_file,
            pid,
        )
        return False

    return True


def _count_profile_skills(profile_home: Path) -> int:
    """Count ``SKILL.md`` files under ``<profile>/skills/`` recursively.

    Returns 0 if the skills directory doesn't exist or is unreadable.
    """
    skills_dir = profile_home / "skills"
    if not skills_dir.is_dir():
        return 0
    try:
        return sum(1 for _ in skills_dir.rglob("SKILL.md"))
    except OSError:
        return 0
    except Exception:  # pragma: no cover — defensive
        return 0


def _read_profile_entry(
    name: str,
    config_yaml: Path,
    soul_md: Path,
    *,
    profile_home: Path,
) -> dict[str, Any] | None:
    """Read a single profile directory into the wire-shape dict.

    Returns ``None`` if ``config.yaml`` is missing or unreadable (caller
    logs a warning and skips). ``SOUL.md`` is optional. ``profile_home``
    is the directory used for the liveness/has-soul/skill-count probes —
    for directory-based profiles this is the profile directory itself;
    for the synthetic ``default`` profile this is ``~/.hermes``.
    """
    if not config_yaml.is_file():
        logger.warning(
            "Profile %r has no config.yaml at %s — skipping",
            name,
            config_yaml,
        )
        return None

    try:
        with open(config_yaml, "r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
    except Exception:
        logger.warning(
            "Profile %r: failed to parse %s — skipping",
            name,
            config_yaml,
            exc_info=True,
        )
        return None

    if data is None:
        data = {}
    if not isinstance(data, dict):
        logger.warning(
            "Profile %r: %s did not parse to a mapping — skipping",
            name,
            config_yaml,
        )
        return None

    model_section = data.get("model")
    if isinstance(model_section, dict):
        model = model_section.get("default") or "unknown"
    else:
        model = "unknown"
    if not isinstance(model, str):
        model = str(model)

    soul_text: str | None = None
    if soul_md.is_file():
        try:
            soul_text = soul_md.read_text(encoding="utf-8").rstrip()
        except Exception:
            logger.warning(
                "Profile %r: failed to read %s — treating as absent",
                name,
                soul_md,
                exc_info=True,
            )
            soul_text = None

    description = data.get("description")
    if not isinstance(description, str) or not description.strip():
        if soul_text:
            description = _extract_description_from_soul(soul_text)
        else:
            description = ""
    else:
        description = description.strip()

    return {
        "name": name,
        "model": model,
        "description": description,
        "system_message": soul_text if soul_text else None,
        "gateway_running": _probe_gateway_running(profile_home),
        "has_soul": soul_md.is_file(),
        "skill_count": _count_profile_skills(profile_home),
    }


def _load_profiles(
    config_path: str,
    *,
    enabled: bool = True,
) -> list[dict[str, Any]]:
    """Discover agent profiles from the Hermes ``~/.hermes/`` layout.

    Upstream Hermes stores profiles as isolated directories under
    ``~/.hermes/profiles/<name>/``, each with its own ``config.yaml``,
    ``SOUL.md``, ``.env``, memory, and sessions. The root
    ``~/.hermes/config.yaml`` is surfaced as a synthetic ``"default"``
    profile so callers always see at least one entry when the host is
    configured at all.

    Each returned dict has the keys ``name``, ``model``, ``description``,
    and ``system_message`` (snake_case — this is the wire shape consumed
    by the Kotlin client via ``auth.ok``).

    When ``enabled=False`` returns an empty list without scanning — this
    honours the ``profile_discovery_enabled`` config toggle.
    """
    if not enabled:
        logger.info("Profile discovery disabled via config — returning empty list")
        return []

    root_config = Path(config_path).expanduser()
    hermes_dir = root_config.parent
    profiles_dir = hermes_dir / "profiles"

    results: list[dict[str, Any]] = []

    # Synthetic "default" entry mapped to the root config.
    if root_config.is_file():
        default_entry = _read_profile_entry(
            name="default",
            config_yaml=root_config,
            soul_md=hermes_dir / "SOUL.md",
            profile_home=hermes_dir,
        )
        if default_entry is not None:
            results.append(default_entry)
    else:
        logger.info(
            "Root Hermes config not found at %s — skipping default profile",
            root_config,
        )

    # Directory-based profiles.
    if profiles_dir.is_dir():
        # Sort for deterministic ordering across filesystems.
        for child in sorted(profiles_dir.iterdir()):
            if not child.is_dir():
                continue
            entry = _read_profile_entry(
                name=child.name,
                config_yaml=child / "config.yaml",
                soul_md=child / "SOUL.md",
                profile_home=child,
            )
            if entry is not None:
                results.append(entry)
    else:
        logger.debug(
            "No profiles directory at %s — only default profile surfaced",
            profiles_dir,
        )

    logger.info(
        "Discovered %d profile(s) under %s",
        len(results),
        hermes_dir,
    )
    return results
