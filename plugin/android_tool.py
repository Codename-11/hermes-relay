"""Compatibility shim for the canonical Android bridge tool module.

Historically this top-level module and ``plugin.tools.android_tool`` drifted
apart, which meant Hermes plugin loading and direct registry imports could
expose different ``android_*`` tool surfaces. Keep this file as a stable
import path, but make the implementation single-source.
"""

from .tools.android_tool import *  # noqa: F401,F403
