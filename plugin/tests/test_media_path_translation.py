"""Docker media-path translation compatibility tests (HRUI-128)."""

from __future__ import annotations

import json
import os
from pathlib import Path
from unittest import mock

from plugin.relay.media import translate_container_media_path


def _fallback_only():
    return mock.patch("plugin.relay.media._translate_with_upstream", return_value=None)


def test_explicit_workspace_mount_translates_to_host(tmp_path: Path) -> None:
    artifact = tmp_path / "result.png"
    artifact.write_bytes(b"png")
    env = {
        "TERMINAL_ENV": "docker",
        "TERMINAL_DOCKER_VOLUMES": json.dumps([f"{tmp_path}:/workspace:rw"]),
    }
    with _fallback_only(), mock.patch.dict(os.environ, env, clear=False):
        assert translate_container_media_path("/workspace/result.png") == str(artifact)


def test_longest_container_prefix_wins(tmp_path: Path) -> None:
    broad = tmp_path / "broad"
    narrow = tmp_path / "narrow"
    (broad / "exports").mkdir(parents=True)
    narrow.mkdir()
    artifact = narrow / "result.pdf"
    artifact.write_bytes(b"pdf")
    env = {
        "TERMINAL_ENV": "docker",
        "TERMINAL_DOCKER_VOLUMES": json.dumps(
            [f"{broad}:/workspace:rw", f"{narrow}:/workspace/exports:rw"],
        ),
    }
    with _fallback_only(), mock.patch.dict(os.environ, env, clear=False):
        assert translate_container_media_path("/workspace/exports/result.pdf") == str(artifact)


def test_unmatched_path_is_left_for_existing_validator() -> None:
    with _fallback_only(), mock.patch.dict(
        os.environ,
        {"TERMINAL_ENV": "docker", "TERMINAL_DOCKER_VOLUMES": "[]"},
        clear=False,
    ):
        assert translate_container_media_path("/outside/result.png") == "/outside/result.png"


def test_root_hermes_tree_does_not_use_broad_home_mount(tmp_path: Path) -> None:
    env = {
        "TERMINAL_ENV": "docker",
        "TERMINAL_DOCKER_VOLUMES": json.dumps([f"{tmp_path}:/root:rw"]),
    }
    with _fallback_only(), mock.patch.dict(os.environ, env, clear=False):
        assert translate_container_media_path("/root/.hermes/auth.json") == "/root/.hermes/auth.json"
