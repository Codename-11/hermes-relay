#!/usr/bin/env python3
"""Verify Play voice capabilities without allowing Device Control through a manifest merge."""
from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
FORBIDDEN = {
    "BIND_ACCESSIBILITY_SERVICE", "WAKE_LOCK", "FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "READ_CONTACTS", "WRITE_CONTACTS", "SEND_SMS", "READ_SMS", "RECEIVE_SMS",
    "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE", "ACCESS_FINE_LOCATION",
    "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "DISABLE_KEYGUARD",
    "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "QUERY_ALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE",
}
REQUIRED = {"RECORD_AUDIO", "POST_NOTIFICATIONS", "FOREGROUND_SERVICE",
            "FOREGROUND_SERVICE_MICROPHONE", "SYSTEM_ALERT_WINDOW"}


def validate(roots: list[ET.Element]) -> None:
    permissions: set[str] = set()
    services: dict[str, ET.Element] = {}
    for root in roots:
        for node in root:
            if node.tag.startswith("uses-permission"):
                name = node.get(ANDROID + "name", "").removeprefix("android.permission.")
                if node.get(TOOLS + "node") == "remove":
                    permissions.discard(name)
                else:
                    permissions.add(name)
        for node in root.findall("application/service"):
            name = node.get(ANDROID + "name", "")
            services[name.rsplit(".", 1)[-1]] = node
            if node.get(ANDROID + "permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE":
                raise ValueError("Play must not bind an AccessibilityService")
            if "mediaProjection" in node.get(ANDROID + "foregroundServiceType", "").split("|"):
                raise ValueError("Play must not declare a MediaProjection service")
    if permissions & FORBIDDEN:
        raise ValueError(f"Play contains Device Control permissions: {sorted(permissions & FORBIDDEN)}")
    if REQUIRED - permissions:
        raise ValueError(f"Play voice permissions missing: {sorted(REQUIRED - permissions)}")
    if {"BridgeForegroundService", "HermesAccessibilityService"} & services.keys():
        raise ValueError("Play contains a Device Control service")
    voice = services.get("VoiceOverlayForegroundService")
    if voice is None or voice.get(ANDROID + "exported") != "false" or \
            voice.get(ANDROID + "foregroundServiceType") != "microphone":
        raise ValueError("Play voice overlay must use a non-exported microphone FGS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("googlePlayDebug", "googlePlayRelease"))
    parser.add_argument("--build-dir", type=Path, default=ROOT / "app/build")
    args = parser.parse_args()
    validate([ET.parse(ROOT / f"app/src/{flavor}/AndroidManifest.xml").getroot()
              for flavor in ("main", "googlePlay")])
    if args.variant:
        files = list((args.build_dir / "intermediates/merged_manifests" / args.variant)
                     .rglob("AndroidManifest.xml"))
        if len(files) != 1:
            raise SystemExit(f"Expected one merged {args.variant} manifest, found {len(files)}")
        validate([ET.parse(files[0]).getroot()])
    print(f"Play capability manifest validated ({args.variant or 'source overlays'})")


if __name__ == "__main__":
    main()
