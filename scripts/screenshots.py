#!/usr/bin/env python3
"""Interactive Play Store screenshot capture tool for Hermes-Relay.

Replaces the old PowerShell/batch implementations, which hit too many
Windows shell landmines (UTF-8 parsing, ANSI code pages, .NET Framework
API gaps, positional parameter binding). Python + rich sidesteps all of
them: subprocess.run passes args as an explicit list, rich handles
Unicode/colors natively on Windows Terminal, conhost, and *nix.

Usage:
    python scripts/screenshots.py
    # or via the thin shim:
    scripts\\screenshots.bat
"""
from __future__ import annotations

import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path

# Auto-install rich on first run — one-time cost, zero-friction UX.
try:
    from rich.console import Console
    from rich.markup import escape
    from rich.panel import Panel
    from rich.prompt import Confirm, Prompt
    from rich.table import Table
    from rich.text import Text
except ImportError:
    print("Installing 'rich' (one-time setup)...", flush=True)
    subprocess.check_call(
        [sys.executable, "-m", "pip", "install", "--quiet", "rich"]
    )
    from rich.console import Console
    from rich.markup import escape
    from rich.panel import Panel
    from rich.prompt import Confirm, Prompt
    from rich.table import Table
    from rich.text import Text

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "assets" / "screenshots"
DEVICE_TMP = "/sdcard/hermes_screenshot.png"

console = Console()


# ── adb helpers ──────────────────────────────────────────────────────────

def adb(*args: str) -> tuple[int, str]:
    """Run adb with the given args. Returns (exit_code, stdout)."""
    try:
        result = subprocess.run(
            ["adb", *args],
            capture_output=True,
            text=True,
            check=False,
            encoding="utf-8",
            errors="replace",
        )
        return result.returncode, (result.stdout or "").strip()
    except FileNotFoundError:
        console.print(
            "[bold red]adb not found on PATH.[/] "
            "Install Android platform-tools and retry."
        )
        sys.exit(1)


def device_connected() -> bool:
    code, _ = adb("get-state")
    return code == 0


def device_model() -> str:
    _, model = adb("shell", "getprop", "ro.product.model")
    return model or "(unknown)"


def count_screenshots() -> int:
    return len(list(OUT_DIR.glob("*.png")))


# ── rendering ────────────────────────────────────────────────────────────

def render_header(device: str, demo_on: bool) -> None:
    rel = str(OUT_DIR.relative_to(REPO_ROOT))
    status = (
        "[bold green]DEMO MODE (clean)[/]"
        if demo_on
        else "[bold yellow]normal[/]"
    )
    # NOTE: os.sep (backslash on Windows) must live OUTSIDE the [bold]…[/]
    # span and must NOT be passed through rich.markup.escape(). Two rich
    # quirks to work around:
    #   1. `\[` inside a span is parsed as a literal bracket escape, so
    #      ending with `...\[/]` breaks the closing tag.
    #   2. rich.markup.escape('\\') returns '\\\\' — it doubles backslashes
    #      so they survive a second markup parse. We don't want that here
    #      because os.sep is already a literal, not user markup input.
    body = Text.from_markup(
        f"Device:      [bold]{escape(device)}[/]\n"
        f"Output:      [bold]{escape(rel)}[/]{os.sep}\n"
        f"Screenshots: [bold]{count_screenshots()}[/]\n"
        f"Status bar:  {status}"
    )
    console.print(
        Panel(
            body,
            title="[bold cyan]Hermes-Relay — Screenshot Capture[/]",
            border_style="cyan",
            padding=(0, 2),
        )
    )


def render_menu() -> None:
    menu = Text.from_markup(
        "[bold cyan]D[/] Demo mode on        "
        "[bold cyan]R[/] Restore status bar\n"
        "[bold cyan]S[/] Snap screenshot     "
        "[bold cyan]L[/] List screenshots\n"
        "[bold cyan]O[/] Open folder         "
        "[bold cyan]Q[/] Quit"
    )
    console.print(Panel(menu, border_style="dim", padding=(0, 2)))


# ── actions ──────────────────────────────────────────────────────────────

DEMO_BROADCASTS: list[tuple[str, list[str]]] = [
    ("clock", ["--es", "hhmm", "1200"]),
    ("battery", ["--es", "level", "100", "--es", "plugged", "false"]),
    ("network", ["--es", "wifi", "show", "--es", "level", "4"]),
    (
        "network",
        ["--es", "mobile", "show", "--es", "level", "4",
         "--es", "datatype", "none"],
    ),
    ("notifications", ["--es", "visible", "false"]),
]


def enable_demo() -> None:
    console.print("[dim]Enabling demo mode...[/]")
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for cmd, extras in DEMO_BROADCASTS:
        adb(
            "shell", "am", "broadcast",
            "-a", "com.android.systemui.demo",
            "--es", "command", cmd,
            *extras,
        )
    console.print(
        "[green]✓[/] Demo mode active "
        "(12:00, full battery, no notifications)"
    )
    console.print(
        "[yellow]TIP:[/] If the status bar didn't change, enable "
        "[italic]Demo Mode[/] under Settings > Developer Options first."
    )


def disable_demo() -> None:
    console.print("[dim]Restoring status bar...[/]")
    adb(
        "shell", "am", "broadcast",
        "-a", "com.android.systemui.demo",
        "--es", "command", "exit",
    )
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "0")
    console.print("[green]✓[/] Status bar restored")


def snap() -> None:
    # Snap FIRST with a timestamp name so timing-sensitive shots (sphere
    # animation states, ambient mode transitions, etc.) fire the moment
    # S is pressed. Renaming happens after — if the user skips the rename
    # prompt or Ctrl-Cs out, the shot is already safely on disk under the
    # timestamp name.
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    temp_name = f"shot_{timestamp}"
    temp_dest = OUT_DIR / f"{temp_name}.png"

    with console.status("[dim]Capturing...[/]"):
        adb("shell", "screencap", "-p", DEVICE_TMP)
        adb("pull", DEVICE_TMP, str(temp_dest))
        adb("shell", "rm", "-f", DEVICE_TMP)

    if not temp_dest.exists():
        console.print(f"[red]✗ Failed to pull[/] {escape(str(temp_dest))}")
        return

    size = temp_dest.stat().st_size
    console.print(
        f"[green]✓ Captured[/] — [bold]{size:,}[/] bytes "
        f"[dim](saved as {escape(temp_name)}.png)[/]"
    )

    console.print("\n[dim]Suggested names:[/]")
    console.print("[dim]  01_chat_empty          05_sphere_ambient[/]")
    console.print("[dim]  02_chat_conversation   06_markdown[/]")
    console.print("[dim]  03_command_palette     07_reasoning[/]")
    console.print("[dim]  04_settings            08_tool_cards[/]")

    try:
        new_name = Prompt.ask(
            "[bold]Rename?[/] [dim](Enter = keep timestamp)[/]",
            default="",
        ).strip()
    except (KeyboardInterrupt, EOFError):
        console.print()
        new_name = ""

    if not new_name:
        console.print(
            f"[green]✓[/] Kept as [bold]{escape(temp_name)}.png[/]"
        )
    else:
        # Strip any accidental extension the user typed.
        if new_name.lower().endswith(".png"):
            new_name = new_name[:-4]

        final_dest = OUT_DIR / f"{new_name}.png"
        if final_dest.exists() and final_dest.resolve() != temp_dest.resolve():
            if not Confirm.ask(
                f"[yellow]{escape(new_name)}.png already exists. Overwrite?[/]",
                default=False,
            ):
                console.print(
                    f"[yellow]Kept original name:[/] "
                    f"[bold]{escape(temp_name)}.png[/]"
                )
                console.print(
                    f"Total: [bold]{count_screenshots()}[/] screenshots"
                )
                return
            final_dest.unlink()

        temp_dest.rename(final_dest)
        console.print(
            f"[green]✓ Renamed to[/] [bold]{escape(new_name)}.png[/]"
        )

    console.print(f"Total: [bold]{count_screenshots()}[/] screenshots")


def show_list() -> None:
    files = sorted(OUT_DIR.glob("*.png"))
    if not files:
        console.print("[dim](no screenshots yet)[/]")
        return

    table = Table(
        title="Captured Screenshots",
        border_style="dim",
        title_style="bold",
        show_header=True,
        header_style="bold cyan",
    )
    table.add_column("Name", style="bold")
    table.add_column("Size", justify="right", style="dim")
    for f in files:
        table.add_row(f.name, f"{f.stat().st_size:,} bytes")
    console.print(table)


def open_folder() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    try:
        if sys.platform == "win32":
            os.startfile(OUT_DIR)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.Popen(["open", str(OUT_DIR)])
        else:
            subprocess.Popen(["xdg-open", str(OUT_DIR)])
        console.print(f"[green]✓[/] Opened {escape(str(OUT_DIR))}")
    except OSError as e:
        console.print(f"[red]✗ Failed to open folder:[/] {e}")


# ── input helpers ────────────────────────────────────────────────────────

def read_key() -> str:
    """Read a single keypress without requiring Enter. Returns lowercase."""
    if sys.platform == "win32":
        import msvcrt
        ch = msvcrt.getwch()
        # Function/arrow keys send two characters — swallow the second
        # so we don't return a control byte.
        if ch in ("\x00", "\xe0"):
            msvcrt.getwch()
            return ""
        return ch.lower()
    import termios
    import tty
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        ch = sys.stdin.read(1)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
    return ch.lower()


def pause() -> None:
    console.print("\n[dim]Press any key to continue...[/]", end="")
    read_key()
    console.print()


# ── main loop ────────────────────────────────────────────────────────────

def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    if not device_connected():
        console.print(
            "[bold red]✗ No device connected.[/] "
            "Plug in via ADB and retry."
        )
        return 1

    device = device_model()
    demo_on = False

    try:
        while True:
            console.clear()
            render_header(device, demo_on)
            render_menu()
            # Brackets around the key list would be parsed as a rich
            # markup tag, so escape the literal chars via rich's own helper.
            choices = escape("[D/R/S/L/O/Q]")
            console.print(
                f"Press a key [bold cyan]{choices}[/]: ", end=""
            )
            sys.stdout.flush()
            key = read_key()
            console.print(f"[bold]{escape(key) or '?'}[/]")
            console.print()

            if key == "d":
                enable_demo()
                demo_on = True
                pause()
            elif key == "r":
                disable_demo()
                demo_on = False
                pause()
            elif key == "s":
                snap()
                pause()
            elif key == "l":
                show_list()
                pause()
            elif key == "o":
                open_folder()
                pause()
            elif key == "q":
                break
            else:
                console.print(f"[yellow]Unknown key: {escape(repr(key))}[/]")
                pause()
    except KeyboardInterrupt:
        console.print("\n[yellow]Interrupted.[/]")
    finally:
        if demo_on:
            console.print()
            try:
                if Confirm.ask(
                    "Demo mode is still on. Restore before quitting?",
                    default=True,
                ):
                    disable_demo()
            except (KeyboardInterrupt, EOFError):
                pass
        console.print(
            f"\n[bold cyan]Done.[/] Screenshots in "
            f"{escape(str(OUT_DIR))}"
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())
