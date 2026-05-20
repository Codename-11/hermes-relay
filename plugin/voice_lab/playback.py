"""Local audio playback helpers for voice-lab runs."""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path


class PlaybackError(RuntimeError):
    """Raised when a generated audio file cannot be played locally."""


def play_audio_file(path: Path) -> None:
    """Play a generated WAV file from the terminal."""
    if not path.is_file():
        raise PlaybackError(f"audio file does not exist: {path}")
    if os.name == "nt":
        import winsound

        winsound.PlaySound(str(path), winsound.SND_FILENAME)
        return

    for command in ("afplay", "aplay", "pw-play", "ffplay"):
        executable = shutil.which(command)
        if not executable:
            continue
        args = [executable, str(path)]
        if command == "ffplay":
            args = [executable, "-nodisp", "-autoexit", str(path)]
        subprocess.run(args, check=True)
        return
    raise PlaybackError("no local audio player found")
