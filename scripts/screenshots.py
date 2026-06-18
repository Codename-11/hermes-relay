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
import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

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
DEFAULT_PLAN = REPO_ROOT / "docs" / "media" / "screenshots.json"
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


# ── plan + image helpers ─────────────────────────────────────────────────

def repo_path(value: str | Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else REPO_ROOT / path


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def load_plan(path: Path = DEFAULT_PLAN) -> dict[str, Any]:
    plan_path = repo_path(path)
    if not plan_path.exists():
        console.print(f"[bold red]Plan not found:[/] {escape(str(plan_path))}")
        sys.exit(1)
    try:
        data = json.loads(plan_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        console.print(f"[bold red]Invalid JSON plan:[/] {exc}")
        sys.exit(1)
    scenes = data.get("scenes")
    if not isinstance(scenes, list) or not scenes:
        console.print("[bold red]Plan must contain a non-empty scenes list.[/]")
        sys.exit(1)
    return data


def find_scene(plan: dict[str, Any], scene_id: str) -> dict[str, Any]:
    for scene in plan["scenes"]:
        if scene.get("id") == scene_id:
            return scene
    console.print(f"[bold red]Unknown scene:[/] {escape(scene_id)}")
    sys.exit(1)


def scene_source(scene: dict[str, Any]) -> Path:
    source = scene.get("source")
    if not isinstance(source, str) or not source:
        console.print(f"[bold red]Scene {escape(str(scene.get('id')))} has no source.[/]")
        sys.exit(1)
    return repo_path(source)


def ensure_pillow():
    try:
        from PIL import Image
    except ImportError:
        console.print("Installing 'Pillow' (one-time setup)...", flush=True)
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", "--quiet", "Pillow"]
        )
        from PIL import Image
    return Image


def crop_for_play(image: Any, crop_config: dict[str, Any]) -> Any:
    max_ratio = float(crop_config.get("maxAspectRatio", 2.0))
    width, height = image.size
    target_width = crop_config.get("width")
    target_height = crop_config.get("height")

    if isinstance(target_width, int) and isinstance(target_height, int):
        if target_width > width or target_height > height:
            raise ValueError(
                f"cannot crop {width}x{height} to larger {target_width}x{target_height}"
            )
    elif height >= width and height / width > max_ratio:
        target_width = width
        target_height = int(width * max_ratio)
    elif width > height and width / height > max_ratio:
        target_width = int(height * max_ratio)
        target_height = height
    else:
        return image.copy()

    anchor = str(crop_config.get("anchor", "center")).lower()
    if anchor == "top":
        left = (width - target_width) // 2
        top = 0
    elif anchor == "bottom":
        left = (width - target_width) // 2
        top = height - target_height
    else:
        left = (width - target_width) // 2
        top = (height - target_height) // 2
    return image.crop((left, top, left + target_width, top + target_height))


def normalize_for_png(image: Any) -> Any:
    if image.mode in ("RGBA", "LA"):
        background = ensure_pillow().new("RGB", image.size, (8, 9, 13))
        alpha = image.getchannel("A")
        background.paste(image, mask=alpha)
        return background
    if image.mode != "RGB":
        return image.convert("RGB")
    return image.copy()


def write_png(image: Any, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    image = normalize_for_png(image)
    image.save(dest, "PNG", optimize=True)


def image_matches(path: Path, expected: Any) -> bool:
    image_mod = ensure_pillow()
    expected = normalize_for_png(expected)
    with image_mod.open(path) as actual:
        actual = normalize_for_png(actual)
        return actual.size == expected.size and actual.tobytes() == expected.tobytes()


def capture_to(dest: Path) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with console.status("[dim]Capturing...[/]"):
        adb("shell", "screencap", "-p", DEVICE_TMP)
        adb("pull", DEVICE_TMP, str(dest))
        adb("shell", "rm", "-f", DEVICE_TMP)
    return dest.exists()


def export_plan(plan: dict[str, Any], targets: set[str]) -> list[Path]:
    outputs = plan.get("outputs", {})
    changed: list[Path] = []

    if "readme" in targets:
        for scene in plan["scenes"]:
            readme_path = scene.get("readme")
            if not readme_path:
                continue
            source = scene_source(scene)
            dest = repo_path(readme_path)
            if source.resolve() == dest.resolve():
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, dest)
            changed.append(dest)

    if "play" in targets:
        play = outputs.get("play", {})
        graphics_dir = repo_path(play.get(
            "graphicsDir",
            "app/src/googlePlay/play/listings/en-US/graphics",
        ))
        crop_config = dict(play.get("screenshotCrop", {}))
        crop_config.setdefault("maxAspectRatio", play.get("maxAspectRatio", 2.0))
        phone_dir = repo_path(play.get(
            "phoneScreenshotsDir",
            graphics_dir / "phone-screenshots",
        ))
        image_mod = ensure_pillow()

        assets = play.get("assets", {})
        if isinstance(assets, dict):
            for media_type, asset in assets.items():
                if not isinstance(asset, dict):
                    continue
                source = repo_path(asset["source"])
                dest = graphics_dir / media_type / asset.get("file", "1.png")
                with image_mod.open(source) as image:
                    write_png(image.copy(), dest)
                changed.append(dest)

        for index, scene in enumerate(plan["scenes"], start=1):
            source = scene_source(scene)
            dest = phone_dir / str(scene.get("play", f"{index}.png"))
            with image_mod.open(source) as image:
                cropped = crop_for_play(image, crop_config)
                write_png(cropped, dest)
            changed.append(dest)

    return changed


def image_size(path: Path) -> tuple[int, int]:
    image_mod = ensure_pillow()
    with image_mod.open(path) as image:
        return image.size


def validate_plan(plan: dict[str, Any]) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    outputs = plan.get("outputs", {})
    play = outputs.get("play", {})
    play_dir = repo_path(play.get("playDir", "app/src/googlePlay/play"))
    graphics_dir = repo_path(play.get(
        "graphicsDir",
        "app/src/googlePlay/play/listings/en-US/graphics",
    ))
    phone_dir = repo_path(play.get(
        "phoneScreenshotsDir",
        graphics_dir / "phone-screenshots",
    ))
    image_mod = ensure_pillow()

    if len(plan["scenes"]) > 8:
        errors.append("Google Play allows at most 8 phone screenshots.")

    for index, scene in enumerate(plan["scenes"], start=1):
        source = scene_source(scene)
        if not source.exists():
            errors.append(f"missing scene source: {display_path(source)}")
            continue
        width, height = image_size(source)
        if min(width, height) < 320 or max(width, height) > 3840:
            errors.append(
                f"source {display_path(source)} is outside Play bounds: {width}x{height}"
            )
        if max(width, height) / min(width, height) > 2:
            warnings.append(
                f"source {display_path(source)} exceeds 2:1; Play export crops it"
            )

        play_name = scene.get("play", f"{index}.png")
        play_path = phone_dir / str(play_name)
        if not play_path.exists():
            errors.append(f"missing Play screenshot export: {display_path(play_path)}")
            continue
        play_w, play_h = image_size(play_path)
        if min(play_w, play_h) < 320 or max(play_w, play_h) > 3840:
            errors.append(
                f"Play screenshot {display_path(play_path)} outside bounds: "
                f"{play_w}x{play_h}"
            )
        if max(play_w, play_h) / min(play_w, play_h) > 2:
            errors.append(
                f"Play screenshot {display_path(play_path)} exceeds 2:1: "
                f"{play_w}x{play_h}"
            )

    assets = play.get("assets", {})
    expected_sizes = {
        "icon": (512, 512),
        "feature-graphic": (1024, 500),
    }
    if isinstance(assets, dict):
        for media_type, asset in assets.items():
            if not isinstance(asset, dict):
                continue
            source = repo_path(asset["source"])
            if not source.exists():
                errors.append(f"missing Play graphic source: {display_path(source)}")
                continue
            dest = graphics_dir / media_type / asset.get("file", "1.png")
            if not dest.exists():
                errors.append(f"missing Play graphic export: {display_path(dest)}")
                continue
            size = image_size(dest)
            expected = expected_sizes.get(media_type)
            if expected and size != expected:
                errors.append(
                    f"{display_path(dest)} is {size[0]}x{size[1]}, expected "
                    f"{expected[0]}x{expected[1]}"
                )
            with image_mod.open(source) as source_image:
                if not image_matches(dest, source_image):
                    errors.append(
                        f"stale Play graphic export: {display_path(dest)} "
                        "(run python scripts/screenshots.py export --target play)"
                    )

    crop_config = dict(play.get("screenshotCrop", {}))
    crop_config.setdefault("maxAspectRatio", play.get("maxAspectRatio", 2.0))
    for index, scene in enumerate(plan["scenes"], start=1):
        source = scene_source(scene)
        play_name = scene.get("play", f"{index}.png")
        play_path = phone_dir / str(play_name)
        if not source.exists() or not play_path.exists():
            continue
        with image_mod.open(source) as source_image:
            expected_export = crop_for_play(source_image, crop_config)
            if not image_matches(play_path, expected_export):
                errors.append(
                    f"stale Play screenshot export: {display_path(play_path)} "
                    "(run python scripts/screenshots.py export --target play)"
                )

    listing_limits = {
        "listings/en-US/title.txt": 30,
        "listings/en-US/short-description.txt": 80,
        "listings/en-US/full-description.txt": 4000,
    }
    default_language = play_dir / "default-language.txt"
    if not default_language.exists():
        errors.append(f"missing Play default language: {display_path(default_language)}")
    elif default_language.read_text(encoding="utf-8").strip() != "en-US":
        errors.append(
            f"{display_path(default_language)} must be en-US to match listing paths"
        )

    for rel_path, limit in listing_limits.items():
        path = play_dir / rel_path
        if not path.exists():
            errors.append(f"missing Play listing text: {display_path(path)}")
            continue
        text = path.read_text(encoding="utf-8").strip()
        if not text:
            errors.append(f"empty Play listing text: {display_path(path)}")
        if len(text) > limit:
            errors.append(
                f"{display_path(path)} is {len(text)} chars, limit is {limit}"
            )

    return errors, warnings


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

    if not capture_to(temp_dest):
        console.print(f"[red]✗ Failed to pull[/] {escape(str(temp_dest))}")
        return

    size = temp_dest.stat().st_size
    console.print(
        f"[green]✓ Captured[/] — [bold]{size:,}[/] bytes "
        f"[dim](saved as {escape(temp_name)}.png)[/]"
    )

    console.print("\n[dim]Suggested names:[/]")
    console.print("[dim]  01_startup      05_commands[/]")
    console.print("[dim]  02_chat         06_manage[/]")
    console.print("[dim]  03_voice        07_connections[/]")
    console.print("[dim]  04_sessions     08_settings[/]")

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


# ── non-interactive commands ─────────────────────────────────────────────

def command_list(args: argparse.Namespace) -> int:
    plan = load_plan(args.plan)
    table = Table(
        title=f"Screenshot plan — {display_path(repo_path(args.plan))}",
        border_style="dim",
        title_style="bold",
        show_header=True,
        header_style="bold cyan",
    )
    table.add_column("Scene", style="bold")
    table.add_column("Title")
    table.add_column("Source", style="dim")
    table.add_column("Play", style="dim")
    for scene in plan["scenes"]:
        table.add_row(
            str(scene.get("id", "")),
            str(scene.get("title", "")),
            display_path(scene_source(scene)),
            str(scene.get("play", "")),
        )
    console.print(table)
    return 0


def command_capture(args: argparse.Namespace) -> int:
    plan = load_plan(args.plan)
    scene = find_scene(plan, args.scene)
    dest = scene_source(scene)

    if not device_connected():
        console.print("[bold red]✗ No device connected.[/] Plug in via ADB and retry.")
        return 1
    if dest.exists() and not args.force:
        if not Confirm.ask(
            f"[yellow]{escape(display_path(dest))} exists. Overwrite?[/]",
            default=False,
        ):
            return 1

    demo_enabled = False
    try:
        if args.demo:
            enable_demo()
            demo_enabled = True
        if not capture_to(dest):
            console.print(f"[bold red]✗ Failed to capture[/] {escape(display_path(dest))}")
            return 1
        console.print(f"[green]OK Captured[/] {escape(display_path(dest))}")
    finally:
        if demo_enabled and args.restore_demo:
            disable_demo()
    return 0


def command_export(args: argparse.Namespace) -> int:
    plan = load_plan(args.plan)
    targets = set(args.target or ["play"])
    changed = export_plan(plan, targets)
    if not changed:
        console.print("[green]OK[/] Nothing to export.")
        return 0
    for path in changed:
        console.print(f"[green]OK[/] {escape(display_path(path))}")
    console.print(f"\nExported [bold]{len(changed)}[/] file(s).")
    return 0


def command_validate(args: argparse.Namespace) -> int:
    plan = load_plan(args.plan)
    errors, warnings = validate_plan(plan)
    for warning in warnings:
        console.print(f"[yellow]warning:[/] {escape(warning)}")
    for error in errors:
        console.print(f"[bold red]error:[/] {escape(error)}")
    if errors:
        return 1
    console.print("[green]OK[/] Screenshot plan and exported Play assets are valid.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Capture and export Hermes-Relay screenshots.",
    )
    parser.add_argument(
        "--plan",
        type=Path,
        default=DEFAULT_PLAN,
        help=f"Screenshot plan JSON (default: {display_path(DEFAULT_PLAN)})",
    )
    subparsers = parser.add_subparsers(dest="command")

    list_parser = subparsers.add_parser("list", help="List planned screenshot scenes")
    list_parser.set_defaults(func=command_list)

    capture_parser = subparsers.add_parser("capture", help="Capture one planned scene")
    capture_parser.add_argument("scene", help="Scene id from the plan")
    capture_parser.add_argument("--force", action="store_true", help="Overwrite without prompting")
    capture_parser.add_argument("--demo", action="store_true", help="Enable clean Android demo mode first")
    capture_parser.add_argument(
        "--no-restore-demo",
        dest="restore_demo",
        action="store_false",
        help="Leave Android demo mode enabled after capture",
    )
    capture_parser.set_defaults(func=command_capture, restore_demo=True)

    export_parser = subparsers.add_parser(
        "export",
        help="Export planned screenshots to README and/or Play directories",
    )
    export_parser.add_argument(
        "--target",
        choices=("readme", "play"),
        action="append",
        default=None,
        help="Export target; repeat for multiple targets (default: play)",
    )
    export_parser.set_defaults(func=command_export)

    validate_parser = subparsers.add_parser(
        "validate",
        help="Validate scene sources and exported Play graphics",
    )
    validate_parser.set_defaults(func=command_validate)

    interactive_parser = subparsers.add_parser(
        "interactive",
        help="Open the original key-driven capture helper",
    )
    interactive_parser.set_defaults(func=lambda _args: interactive_main())

    return parser


# ── main loop ────────────────────────────────────────────────────────────

def interactive_main() -> int:
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


def main() -> int:
    if len(sys.argv) == 1:
        return interactive_main()
    parser = build_parser()
    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        return 1
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
