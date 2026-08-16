#!/usr/bin/env python3
"""Record the Google Play foreground-service demonstration over ADB.

The driver uses the Android accessibility hierarchy for app controls and only
falls back to system status-bar commands for opening and closing notifications.
It intentionally records real foreground services; it does not mock notification
content or invoke service components directly.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


DEFAULT_PACKAGE = "com.axiomlabs.hermesrelay.sideload"
REMOTE_UI = "/sdcard/hermes-fgs-demo.xml"
REMOTE_VIDEO = "/sdcard/hermes-fgs-demo.mp4"


@dataclass(frozen=True)
class UiNode:
    text: str
    description: str
    bounds: tuple[int, int, int, int]
    checkable: bool
    checked: bool

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return ((left + right) // 2, (top + bottom) // 2)


class DemoError(RuntimeError):
    pass


class AdbDemo:
    def __init__(self, serial: str, package: str, pause_scale: float) -> None:
        self.serial = serial
        self.package = package
        self.pause_scale = pause_scale

    def adb(
        self,
        *args: str,
        check: bool = True,
        timeout: float = 30,
    ) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            ["adb", "-s", self.serial, *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
        if check and result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            raise DemoError(f"adb {' '.join(args)} failed: {detail}")
        return result

    def pause(self, seconds: float) -> None:
        time.sleep(seconds * self.pause_scale)

    def launch(self) -> None:
        self.adb("shell", "cmd", "statusbar", "collapse")
        self.pause(0.5)
        result = self.adb(
            "shell",
            "monkey",
            "-p",
            self.package,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        )
        if "Events injected: 1" not in result.stdout:
            raise DemoError(f"Could not launch {self.package}: {result.stdout.strip()}")
        self.pause(2)

    @staticmethod
    def parse_bounds(raw: str) -> tuple[int, int, int, int]:
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
        if not match:
            raise DemoError(f"Unexpected UI bounds: {raw!r}")
        return tuple(int(value) for value in match.groups())  # type: ignore[return-value]

    def dump(self) -> list[UiNode]:
        self.adb("shell", "uiautomator", "dump", REMOTE_UI)
        xml = self.adb("exec-out", "cat", REMOTE_UI).stdout
        try:
            root = ET.fromstring(xml)
        except ET.ParseError as error:
            raise DemoError("Could not parse the Android UI hierarchy") from error
        return [
            UiNode(
                text=node.attrib.get("text", ""),
                description=node.attrib.get("content-desc", ""),
                bounds=self.parse_bounds(node.attrib["bounds"]),
                checkable=node.attrib.get("checkable") == "true",
                checked=node.attrib.get("checked") == "true",
            )
            for node in root.iter("node")
            if "bounds" in node.attrib
        ]

    @staticmethod
    def find(
        nodes: list[UiNode],
        *,
        text: str | None = None,
        description: str | None = None,
    ) -> UiNode | None:
        matches = [
            node
            for node in nodes
            if (text is None or node.text == text)
            and (description is None or node.description == description)
        ]
        if len(matches) > 1:
            raise DemoError(f"Ambiguous UI selector text={text!r} description={description!r}")
        return matches[0] if matches else None

    def require(
        self,
        nodes: list[UiNode],
        *,
        text: str | None = None,
        description: str | None = None,
    ) -> UiNode:
        node = self.find(nodes, text=text, description=description)
        if node is None:
            raise DemoError(f"Missing UI selector text={text!r} description={description!r}")
        return node

    def tap(self, node: UiNode) -> None:
        x, y = node.center
        self.adb("shell", "input", "tap", str(x), str(y))

    def tap_selector(
        self,
        *,
        text: str | None = None,
        description: str | None = None,
        settle: float = 0.8,
    ) -> None:
        self.tap(self.require(self.dump(), text=text, description=description))
        self.pause(settle)

    def back(self) -> None:
        self.adb("shell", "input", "keyevent", "4")
        self.pause(0.8)

    def home(self) -> None:
        self.adb("shell", "input", "keyevent", "3")
        self.pause(1.2)

    def expand_notifications(self) -> list[UiNode]:
        self.adb("shell", "cmd", "statusbar", "expand-notifications")
        self.pause(1.5)
        return self.dump()

    def collapse_notifications(self) -> None:
        self.adb("shell", "cmd", "statusbar", "collapse")
        self.pause(0.8)

    def persistent_toggle(self, nodes: list[UiNode]) -> UiNode:
        label = self.require(nodes, text="Persistent connection")
        left, top, right, bottom = label.bounds
        candidates = [
            node
            for node in nodes
            if node.checkable
            and node.bounds[0] > right
            and node.bounds[1] < bottom + 100
            and node.bounds[3] > top - 100
        ]
        if len(candidates) != 1:
            raise DemoError(f"Expected one Persistent connection switch; found {len(candidates)}")
        return candidates[0]

    def open_settings(self) -> list[UiNode]:
        self.tap_selector(description="Settings")
        nodes = self.dump()
        self.require(nodes, text="Persistent connection")
        return nodes

    def set_persistent(self, enabled: bool) -> None:
        nodes = self.dump()
        toggle = self.persistent_toggle(nodes)
        if toggle.checked != enabled:
            self.tap(toggle)
            self.pause(1.5)
        current = self.persistent_toggle(self.dump())
        if current.checked != enabled:
            raise DemoError(f"Persistent connection did not become {enabled}")

    def ensure_chat(self) -> None:
        nodes = self.dump()
        if self.find(nodes, description="Start voice conversation") is not None:
            return
        back = self.find(nodes, description="Back")
        if back is not None:
            self.tap(back)
            self.pause(1)
        nodes = self.dump()
        self.require(nodes, description="Start voice conversation")

    def send_message(self, message: str) -> None:
        if not re.fullmatch(r"[A-Za-z0-9 ]+", message):
            raise DemoError("Demo message must contain only ASCII letters, digits, and spaces")
        nodes = self.dump()
        composer = self.find(nodes, description="Message…") or self.find(nodes, text="Message…")
        if composer is None:
            raise DemoError("Missing chat composer labeled Message…")
        self.tap(composer)
        self.pause(0.5)
        encoded = message.replace(" ", "%s")
        self.adb("shell", "input", "text", encoded)
        self.pause(0.5)
        self.tap_selector(description="Send message", settle=0.4)

    def notification_posted(self, title: str) -> bool:
        notifications = self.adb(
            "shell",
            "dumpsys",
            "notification",
            "--noredact",
        ).stdout
        return title in notifications

    def wait_for_notification_state(
        self,
        title: str,
        *,
        posted: bool,
        timeout: float = 30,
    ) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.notification_posted(title) is posted:
                return
            self.pause(1)
        state = "appear" if posted else "clear"
        raise DemoError(f"Timed out waiting for notification to {state}: {title}")

    def wait_absent_from_shade(self, title: str, timeout: float = 30) -> list[UiNode]:
        self.wait_for_notification_state(title, posted=False, timeout=timeout)
        # Open the shade exactly once for the recorded visual proof. The service
        # state assertion above avoids an open/close polling loop and prevents a
        # grouped notification from producing a false absence result.
        return self.expand_notifications()

    def wait_for_notification(self, title: str, timeout: float = 30) -> tuple[list[UiNode], UiNode]:
        # First wait against Android's notification registry without touching
        # the UI, then open the shade once and reveal any Samsung app grouping.
        self.wait_for_notification_state(title, posted=True, timeout=timeout)
        nodes = self.expand_notifications()
        deadline = time.monotonic() + timeout
        expanded_groups: set[tuple[str, tuple[int, int, int, int]]] = set()
        while time.monotonic() < deadline:
            visible = self.find(nodes, text=title)
            if visible is not None:
                return nodes, visible
            group_counts = [
                node
                for node in nodes
                if (node.text.isdigit() or node.description.isdigit())
                and node.center[1] > 550
            ]
            untried = [
                node
                for node in group_counts
                if (node.description, node.bounds) not in expanded_groups
            ]
            if untried:
                group = min(untried, key=lambda node: node.center[1])
                expanded_groups.add((group.description, group.bounds))
                self.tap(group)
                self.pause(0.6)
            else:
                self.pause(1)
            nodes = self.dump()
        raise DemoError(f"Timed out revealing notification in the open shade: {title}")

    def expose_notification_action(self, title: str, action: str) -> list[UiNode]:
        nodes = self.dump()
        for _ in range(3):
            if self.find(nodes, text=action) is not None:
                return nodes
            anchor = self.require(nodes, text=title)
            _, anchor_y = anchor.center
            candidates = [
                node
                for node in nodes
                    if node.description == "Expand"
                    or node.description.isdigit()
                    or node.text.isdigit()
            ]
            if not candidates:
                raise DemoError(f"No expansion affordance found for {title}")
            nearest = min(candidates, key=lambda node: abs(node.center[1] - anchor_y))
            self.tap(nearest)
            self.pause(0.6)
            nodes = self.dump()
        raise DemoError(f"Could not expose notification action: {action}")

    def start_recording(self) -> str:
        self.adb("shell", "rm", "-f", REMOTE_VIDEO)
        command = (
            "screenrecord --bit-rate 8000000 --time-limit 180 "
            f"{REMOTE_VIDEO} >/dev/null 2>&1 & echo $!"
        )
        pid = self.adb("shell", command).stdout.strip()
        if not pid.isdigit():
            raise DemoError(f"Could not start screenrecord: {pid!r}")
        self.pause(1)
        return pid

    def stop_recording(self, pid: str, output: Path) -> None:
        self.adb("shell", "kill", "-2", pid, check=False)
        self.pause(2)
        output.parent.mkdir(parents=True, exist_ok=True)
        self.adb("pull", REMOTE_VIDEO, str(output), timeout=120)
        if not output.exists() or output.stat().st_size == 0:
            raise DemoError("ADB screen recording was not pulled successfully")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit ADB device serial")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("artifacts/android-fgs-demo.mp4"),
    )
    parser.add_argument(
        "--message",
        default="Confirm background connection in five words",
    )
    parser.add_argument(
        "--pause-scale",
        type=float,
        default=1.0,
        help="Scale visual pauses; values below 1 are useful only for dry runs",
    )
    parser.add_argument(
        "--no-record",
        action="store_true",
        help="Exercise and assert the sequence without creating an MP4",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    demo = AdbDemo(args.serial, args.package, args.pause_scale)
    recording_pid: str | None = None
    try:
        if not re.fullmatch(r"[A-Za-z0-9 ]+", args.message):
            raise DemoError("Demo message must contain only ASCII letters, digits, and spaces")
        if demo.adb("get-state").stdout.strip() != "device":
            raise DemoError(f"ADB device is not ready: {args.serial}")
        installed = demo.adb("shell", "pm", "path", args.package).stdout.strip()
        if not installed.startswith("package:"):
            raise DemoError(f"Package is not installed for the active user: {args.package}")

        demo.launch()
        demo.ensure_chat()
        settings = demo.open_settings()
        if demo.persistent_toggle(settings).checked:
            demo.set_persistent(False)
        demo.ensure_chat()

        if not args.no_record:
            recording_pid = demo.start_recording()
            demo.pause(3)

        print("1/6 Enable Persistent connection")
        demo.open_settings()
        demo.set_persistent(True)
        demo.pause(2)

        print("2/6 Show the ongoing connection notification")
        nodes = demo.expand_notifications()
        demo.require(nodes, text="Hermes connection active")
        demo.pause(3)
        demo.collapse_notifications()
        demo.ensure_chat()

        print("3/6 Send a chat turn, background Hermes, and reopen it from the notification")
        demo.send_message(args.message)
        demo.home()
        demo.pause(10)
        nodes, connection = demo.wait_for_notification("Hermes connection active")
        demo.pause(3)
        demo.tap(connection)
        demo.pause(2)

        print("4/6 Disable Persistent connection and prove the notification clears")
        demo.open_settings()
        demo.set_persistent(False)
        demo.pause(2)
        demo.wait_absent_from_shade("Hermes connection active")
        demo.pause(3)
        demo.collapse_notifications()
        demo.ensure_chat()

        print("5/6 Start the app-owned voice overlay and show its microphone notification")
        demo.tap_selector(description="Start voice conversation", settle=1)
        demo.tap_selector(description="Expand voice controls", settle=0.7)
        demo.tap_selector(text="Overlay", settle=1.5)
        demo.home()
        demo.pause(3)
        nodes, _ = demo.wait_for_notification("Hermes voice overlay active")
        nodes = demo.expose_notification_action("Hermes voice overlay active", "Stop voice")
        demo.pause(3)

        print("6/6 Stop voice from the notification and prove it clears")
        demo.tap(demo.require(nodes, text="Stop voice"))
        demo.pause(2)
        nodes = demo.dump()
        if demo.find(nodes, text="Hermes voice overlay active") is not None:
            raise DemoError("Voice overlay notification remained after Stop voice")
        demo.pause(3)
        demo.collapse_notifications()

        if recording_pid is not None:
            demo.stop_recording(recording_pid, args.output.resolve())
            recording_pid = None
            print(f"Recorded: {args.output.resolve()}")
        else:
            print("Dry run passed; no recording created")
        return 0
    except (DemoError, subprocess.TimeoutExpired) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    finally:
        if recording_pid is not None:
            demo.stop_recording(recording_pid, args.output.resolve())


if __name__ == "__main__":
    raise SystemExit(main())
