"""Legacy import shim for the plugin-owned Hermes-Relay bootstrap.

New installs load ``plugin/hermes_relay_bootstrap`` through ``hermes relay
compat install``. This top-level package stays as a backward-compatible import
target for older editable installs and existing ``hermes_relay_bootstrap.pth``
files.
"""

from __future__ import annotations

from importlib import import_module
import sys

_module = import_module("plugin.hermes_relay_bootstrap")
sys.modules[__name__] = _module
