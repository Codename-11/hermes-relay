#!/usr/bin/env python3
"""Run an opt-in Android lifecycle certification against a Gateway fixture.

This runner is deliberately not wired into Gradle or CI. It operates only on
the sideload application ID and requires an explicit ADB serial or transport
ID. The selected fixture scenario must already be running; this process never
starts or owns the fixture. Run with ``--dry-run`` first to inspect the plan.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


SIDELOAD_PACKAGE = "com.axiomlabs.hermesrelay.sideload"
SIDELOAD_TEST_PACKAGE = f"{SIDELOAD_PACKAGE}.test"
DEFAULT_INSTRUMENTATION_COMPONENT = (
    f"{SIDELOAD_TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
)
DEFAULT_INSTRUMENTATION_CLASS = (
    "com.hermesandroid.relay.viewmodel.GatewayExternalFixtureInstrumentedTest"
    "#terminalGapActivate_externalFixtureRecoversFromAuthoritativeHttpHistory"
)
RADIO_CONFIRMATION = "I_REVIEWED_THE_DEVICE_WIDE_RADIO_PREVIEW"
SAFE_LOG_MARKERS = (
    "Gateway connected",
    "Gateway socket down",
    "Gateway socket rejoined",
    "Gateway turn submitted",
    "Gateway turn settled",
    "Mid-turn reconnect",
    "Background-turn reconnect",
    "AndroidRuntime",
    "ANR in",
    "FATAL EXCEPTION",
)


class CertificationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ApkMetadata:
    package: str
    version_name: str
    version_code: str
    sha256: str


@dataclass(frozen=True)
class Operation:
    kind: str
    label: str
    args: tuple[str, ...] = ()
    fixture_action: str | None = None
    screenshot_name: str | None = None
    pause_seconds: float = 0.0


@dataclass(frozen=True)
class Config:
    target_flag: str
    target_value: str
    apk: Path
    install: bool
    test_apk: Path | None
    install_test: bool
    instrumentation_component: str
    instrumentation_class: str
    fixture_url: str
    fixture_host_port: int
    fixture_device_port: int
    fixture_state_path: str
    fixture_evidence_path: str
    scenario: str
    output_dir: Path
    screenshots: bool
    synthetic_fixture_confirmed: bool
    radio_cycle: str | None
    radio_confirmation: str | None
    radio_preview_receipt: Path | None
    pause_seconds: float
    dry_run: bool
    app_lifecycle_smoke: bool


def target_args(config: Config) -> tuple[str, str]:
    return (config.target_flag, config.target_value)


def adb_args(config: Config, *args: str) -> tuple[str, ...]:
    return ("adb", *target_args(config), *args)


def build_plan(config: Config) -> list[Operation]:
    if config.screenshots and not config.app_lifecycle_smoke:
        raise CertificationError("Screenshot capture requires --app-lifecycle-smoke")
    if config.screenshots and not config.synthetic_fixture_confirmed:
        raise CertificationError(
            "Screenshot capture requires --confirm-synthetic-fixture so real chat content is not persisted"
        )
    validate_radio_authorization(config)

    plan = [Operation("adb", "verify device", adb_args(config, "get-state"))]
    if config.install:
        plan.append(Operation("adb", "install sideload APK", adb_args(config, "install", "-r", str(config.apk))))
    if config.install_test:
        if not config.test_apk:
            raise CertificationError("--install-test requires --test-apk")
        plan.append(
            Operation(
                "adb",
                "install sideload test APK",
                adb_args(config, "install", "-r", str(config.test_apk)),
            )
        )
    plan.extend(
        [
            Operation(
                "adb",
                "reverse fixture port",
                adb_args(
                    config,
                    "reverse",
                    f"tcp:{config.fixture_device_port}",
                    f"tcp:{config.fixture_host_port}",
                ),
            ),
            Operation("adb", "clear bounded log buffer", adb_args(config, "logcat", "-c")),
            Operation(
                "adb",
                "stop sideload app before instrumentation",
                adb_args(config, "shell", "am", "force-stop", SIDELOAD_PACKAGE),
            ),
            Operation("fixture", "verify fixture scenario", fixture_action="state"),
            Operation(
                "instrumentation",
                "run external-fixture instrumentation",
                adb_args(
                    config,
                    "shell",
                    "am",
                    "instrument",
                    "-w",
                    "-r",
                    "-e",
                    "gatewayFixtureBaseUrl",
                    f"http://127.0.0.1:{config.fixture_device_port}",
                    "-e",
                    "class",
                    config.instrumentation_class,
                    config.instrumentation_component,
                ),
            ),
            Operation("fixture", "collect fixture evidence", fixture_action="evidence"),
            Operation("fixture", "verify fixture settled state", fixture_action="state"),
        ]
    )
    if config.app_lifecycle_smoke:
        plan.extend(
            [
            Operation(
                "adb",
                "launch sideload app",
                adb_args(
                    config,
                    "shell",
                    "monkey",
                    "-p",
                    SIDELOAD_PACKAGE,
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "1",
                ),
                pause_seconds=config.pause_seconds,
            ),
            Operation(
                "adb",
                "background app",
                adb_args(config, "shell", "input", "keyevent", "KEYCODE_HOME"),
                pause_seconds=config.pause_seconds,
            ),
            Operation(
                "adb",
                "foreground app",
                adb_args(
                    config,
                    "shell",
                    "monkey",
                    "-p",
                    SIDELOAD_PACKAGE,
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "1",
                ),
                pause_seconds=config.pause_seconds,
            ),
            ]
        )
        if config.screenshots:
            plan.insert(-2, Operation("screenshot", "capture foreground", screenshot_name="01-foreground.png"))
            plan.append(Operation("screenshot", "capture reattached", screenshot_name="02-reattached.png"))
    if config.radio_cycle:
        radio = config.radio_cycle
        plan.extend(
            [
                Operation(
                    "adb",
                    f"disable device-wide {radio}",
                    adb_args(config, "shell", "svc", radio, "disable"),
                    pause_seconds=config.pause_seconds,
                ),
                Operation(
                    "adb",
                    f"enable device-wide {radio}",
                    adb_args(config, "shell", "svc", radio, "enable"),
                    pause_seconds=config.pause_seconds,
                ),
            ]
        )
    if config.app_lifecycle_smoke:
        plan.extend(
            [
            Operation(
                "adb",
                "stop app process",
                adb_args(config, "shell", "am", "force-stop", SIDELOAD_PACKAGE),
                pause_seconds=config.pause_seconds,
            ),
            Operation(
                "adb",
                "recreate app process",
                adb_args(
                    config,
                    "shell",
                    "monkey",
                    "-p",
                    SIDELOAD_PACKAGE,
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "1",
                ),
                pause_seconds=config.pause_seconds,
            ),
            ]
        )
        if config.screenshots:
            plan.append(Operation("screenshot", "capture recreated process", screenshot_name="03-recreated.png"))
    plan.extend(
        [
            Operation("logcat", "collect bounded privacy-safe logcat"),
            Operation(
                "adb",
                "remove fixture reverse",
                adb_args(config, "reverse", "--remove", f"tcp:{config.fixture_device_port}"),
            ),
        ]
    )
    return plan


def find_aapt2(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which("aapt2")
    if found:
        return found
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        roots.append(str(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"))
    candidates: list[Path] = []
    for root in filter(None, roots):
        candidates.extend(Path(root).glob("build-tools/*/aapt2*"))
    if not candidates:
        raise CertificationError("aapt2 was not found; pass --aapt2 or configure the Android SDK")
    return str(sorted(candidates, key=lambda path: path.parent.name)[-1])


def inspect_apk(
    apk: Path,
    aapt2: str,
    *,
    expected_package: str = SIDELOAD_PACKAGE,
    allow_empty_version: bool = False,
) -> ApkMetadata:
    if not apk.is_file():
        raise CertificationError("APK does not exist")
    result = subprocess.run(
        [aapt2, "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode:
        raise CertificationError("Could not inspect APK manifest with aapt2")
    match = re.search(
        r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'",
        result.stdout,
        re.MULTILINE,
    )
    if not match:
        raise CertificationError("Could not read APK package/version metadata")
    package, version_code, version_name = match.groups()
    if package != expected_package:
        raise CertificationError(f"Refusing unexpected APK package: {package}")
    if not version_code or not version_name:
        if not allow_empty_version:
            raise CertificationError("Could not read APK package/version metadata")
        # AGP-generated instrumentation APKs intentionally carry empty manifest
        # versions; PackageManager normalizes them to 0/null after install.
        version_code = "0"
        version_name = "null"
    digest_builder = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest_builder.update(chunk)
    digest = digest_builder.hexdigest()
    return ApkMetadata(package, version_name, version_code, digest)


def run_command(args: Sequence[str], *, timeout: float = 45.0) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        list(args),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )
    if result.returncode:
        raise CertificationError(f"Command failed: {sanitize_text(result.stderr or result.stdout)}")
    return result


def require_instrumentation_success(output: str) -> int:
    success = re.search(r"\bOK \((\d+) tests?\)", output)
    if (
        not success
        or int(success.group(1)) < 1
        or any(
            marker in output
            for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "shortMsg=Process crashed")
        )
    ):
        raise CertificationError("Android instrumentation did not report a successful test result")
    return int(success.group(1))


def inspect_installed_package(config: Config, package: str = SIDELOAD_PACKAGE) -> tuple[str, str]:
    result = run_command(adb_args(config, "shell", "dumpsys", "package", package))
    version_name = re.search(r"\bversionName=([^\s]+)", result.stdout)
    version_code = re.search(r"\bversionCode=(\d+)", result.stdout)
    if not version_name or not version_code:
        raise CertificationError("Sideload package is not installed or its version could not be read")
    return version_name.group(1), version_code.group(1)


def validate_installed_version(config: Config, apk: ApkMetadata) -> None:
    version_name, version_code = inspect_installed_package(config, apk.package)
    if (version_name, version_code) != (apk.version_name, apk.version_code):
        raise CertificationError(
            "Installed sideload version does not match the inspected APK "
            f"(installed {version_name}/{version_code}, APK {apk.version_name}/{apk.version_code})"
        )


def verify_test_package_present(config: Config) -> None:
    result = run_command(adb_args(config, "shell", "pm", "path", SIDELOAD_TEST_PACKAGE))
    if not result.stdout.strip().startswith("package:"):
        raise CertificationError("Sideload instrumentation test package is not installed")


def fixture_request(config: Config, action: str) -> dict[str, object]:
    base = config.fixture_url.rstrip("/") + "/"
    path = config.fixture_state_path if action == "state" else config.fixture_evidence_path
    url = urllib.parse.urljoin(base, path.lstrip("/"))
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            body = response.read(32_768)
    except OSError as error:
        raise CertificationError(f"Fixture action {action!r} failed") from error
    if not body:
        return {}
    try:
        value = json.loads(body)
    except json.JSONDecodeError as error:
        raise CertificationError(f"Fixture action {action!r} returned invalid JSON") from error
    return value if isinstance(value, dict) else {}


def safe_fixture_evidence(value: dict[str, object], expected_scenario: str) -> dict[str, object]:
    if value.get("scenario") != expected_scenario:
        raise CertificationError("Fixture scenario does not match --scenario")
    raw_entries = value.get("entries", [])
    if not isinstance(raw_entries, list):
        raw_entries = []
    allowed_fields = {"sequence", "kind", "connection", "method", "event_type", "scope", "outcome"}
    entries: list[dict[str, object]] = []
    for raw in raw_entries[:512]:
        if not isinstance(raw, dict):
            continue
        entry: dict[str, object] = {}
        for key, field_value in raw.items():
            if key not in allowed_fields or not isinstance(field_value, (str, int)):
                continue
            if isinstance(field_value, str) and not re.fullmatch(r"[A-Za-z0-9_.:-]{1,120}", field_value):
                entry[key] = "<redacted>"
            else:
                entry[key] = field_value
        entries.append(entry)
    return {
        "schema_version": 1,
        "scenario": expected_scenario,
        "bounded": True,
        "entries": entries,
    }


def validate_fixture_proof(
    evidence: dict[str, object], snapshots: list[dict[str, object]],
) -> None:
    entries = evidence.get("entries", [])
    if not isinstance(entries, list):
        raise CertificationError("Fixture evidence is missing")

    def contains(**expected: object) -> bool:
        return any(
            isinstance(entry, dict)
            and all(entry.get(key) == value for key, value in expected.items())
            for entry in entries
        )

    required = {
        "prompt.submit": contains(kind="rpc", method="prompt.submit"),
        "socket_gap": contains(kind="fault", outcome="socket_gap"),
        "session.activate": contains(kind="rpc", method="session.activate"),
        "history read": contains(kind="history", outcome="read"),
        "runtime settled": contains(kind="runtime", outcome="settled"),
    }
    missing = [name for name, present in required.items() if not present]
    if missing:
        raise CertificationError("Fixture proof is incomplete: " + ", ".join(missing))
    if not snapshots or snapshots[-1].get("running") is not False:
        raise CertificationError("Fixture did not report a settled final state")


def hashed_identifier(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


def radio_preview_fingerprint(config: Config) -> str:
    material = json.dumps(
        {
            "device": hashed_identifier(config.target_value),
            "selector": config.target_flag,
            "radio": config.radio_cycle,
            "host_port": config.fixture_host_port,
            "device_port": config.fixture_device_port,
            "scenario": config.scenario,
        },
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def validate_radio_authorization(config: Config) -> None:
    if not config.radio_cycle or config.dry_run:
        return
    if config.radio_confirmation != RADIO_CONFIRMATION:
        raise CertificationError(
            "Radio cycling requires --dangerous-radio-confirm " + RADIO_CONFIRMATION
        )
    if not config.radio_preview_receipt or not config.radio_preview_receipt.is_file():
        raise CertificationError("Radio cycling requires the receipt produced by a prior --dry-run preview")
    try:
        receipt = json.loads(config.radio_preview_receipt.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CertificationError("Radio preview receipt is unreadable") from error
    if receipt.get("fingerprint") != radio_preview_fingerprint(config):
        raise CertificationError("Radio preview receipt does not match this device/scenario plan")


def sanitize_text(value: str) -> str:
    value = re.sub(r"(?i)\b(bearer|token|authorization|password|secret)\s*[:=]\s*\S+", r"\1=<redacted>", value)
    value = re.sub(r"(?i)\b(?:https?|wss?)://[^\s]+", "<url>", value)
    value = re.sub(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?", "<address>", value)
    value = re.sub(
        r"[A-Za-z]:\\[^\s]+|/(?:Users|home|data|sdcard|storage|mnt|system|apex|vendor|product|cache)/[^\s]+",
        "<path>",
        value,
    )
    value = re.sub(r"\b(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}\b", "<host>", value)

    def replace_identifier(match: re.Match[str]) -> str:
        return f"{match.group(1)}=<id:{hashed_identifier(match.group(2))}>"

    return re.sub(r"(?i)\b(session|run|generation)=([^\s,)]+)", replace_identifier, value)


def sanitize_logcat(raw: str, *, max_lines: int = 500) -> list[str]:
    safe: list[str] = []
    for line in raw.splitlines():
        if any(marker in line for marker in SAFE_LOG_MARKERS):
            safe.append(sanitize_text(line)[:1000])
            if len(safe) >= max_lines:
                break
    return safe


def capture_screenshot(config: Config, destination: Path) -> None:
    command = adb_args(config, "exec-out", "screencap", "-p")
    result = subprocess.run(list(command), capture_output=True, timeout=30, check=False)
    if result.returncode or not result.stdout.startswith(b"\x89PNG"):
        raise CertificationError("Could not capture synthetic-fixture screenshot")
    destination.write_bytes(result.stdout)


def operation_preview(operation: Operation) -> dict[str, object]:
    item: dict[str, object] = {"kind": operation.kind, "label": operation.label}
    if operation.args:
        args = list(operation.args)
        if len(args) >= 3 and args[0] == "adb" and args[1] in ("-s", "-t"):
            args[2] = "<device>"
        if "install" in args:
            args[-1] = "<apk>"
        item["command"] = sanitize_text(" ".join(args))
    if operation.fixture_action:
        item["fixture_action"] = operation.fixture_action
    if operation.screenshot_name:
        item["screenshot"] = operation.screenshot_name
    return item


def execute(
    config: Config,
    apk: ApkMetadata,
    test_apk: ApkMetadata | None,
    plan: Iterable[Operation],
) -> dict[str, object]:
    config.output_dir.mkdir(parents=True, exist_ok=True)
    started = time.time()
    completed: list[str] = []
    fixture_snapshots: list[dict[str, object]] = []
    fixture_evidence: dict[str, object] = {}
    instrumentation_tests = 0
    log_lines: list[str] = []
    reverse_active = False
    try:
        for operation in plan:
            if operation.kind == "adb":
                run_command(operation.args, timeout=120 if "install" in operation.args else 45)
                if operation.label == "reverse fixture port":
                    reverse_active = True
                if operation.label == "install sideload APK":
                    validate_installed_version(config, apk)
                elif operation.label == "install sideload test APK" and test_apk:
                    validate_installed_version(config, test_apk)
            elif operation.kind == "instrumentation":
                output = run_command(operation.args, timeout=180).stdout
                instrumentation_tests = require_instrumentation_success(output)
            elif operation.kind == "fixture":
                result = fixture_request(config, operation.fixture_action or "")
                if result.get("scenario") != config.scenario:
                    raise CertificationError("Fixture scenario does not match --scenario")
                if operation.fixture_action == "evidence":
                    fixture_evidence = safe_fixture_evidence(result, config.scenario)
                else:
                    fixture_snapshots.append(
                        {
                            "running": bool(result.get("running", False)),
                            "remaining_turns": int(result.get("remaining_turns", 0)),
                            "queued_turns": int(result.get("queued_turns", 0)),
                            "history_rows": int(result.get("history_rows", 0)),
                        }
                    )
            elif operation.kind == "screenshot":
                capture_screenshot(config, config.output_dir / (operation.screenshot_name or "capture.png"))
            elif operation.kind == "logcat":
                raw = run_command(
                    adb_args(
                        config,
                        "logcat",
                        "-d",
                        "-t",
                        "2000",
                        "GatewayChatClient:I",
                        "ChatViewModel:I",
                        "AndroidRuntime:E",
                        "ActivityManager:I",
                        "*:S",
                    )
                ).stdout
                log_lines = sanitize_logcat(raw)
                (config.output_dir / "logcat-safe.txt").write_text(
                    "\n".join(log_lines) + ("\n" if log_lines else ""), encoding="utf-8"
                )
            completed.append(operation.label)
            if operation.pause_seconds:
                time.sleep(operation.pause_seconds)
        validate_installed_version(config, apk)
        validate_fixture_proof(fixture_evidence, fixture_snapshots)
    finally:
        if reverse_active and "remove fixture reverse" not in completed:
            subprocess.run(
                list(adb_args(config, "reverse", "--remove", f"tcp:{config.fixture_device_port}")),
                capture_output=True,
                check=False,
            )

    return {
        "schema_version": 1,
        "result": "passed",
        "duration_seconds": round(time.time() - started, 3),
        "device": {"selector": config.target_flag, "id_hash": hashed_identifier(config.target_value)},
        "application": {
            "package": SIDELOAD_PACKAGE,
            "version_name": apk.version_name,
            "version_code": apk.version_code,
            "apk_sha256": apk.sha256,
        },
        "fixture": {
            "scenario": sanitize_text(config.scenario)[:120],
            "host_port": config.fixture_host_port,
            "device_port": config.fixture_device_port,
            "state_snapshots": fixture_snapshots,
            "evidence": fixture_evidence,
        },
        "radio_cycle": config.radio_cycle or "none",
        "screenshots": [path.name for path in sorted(config.output_dir.glob("*.png"))],
        "safe_log_lines": len(log_lines),
        "instrumentation": {"result": "passed", "tests": instrumentation_tests},
        "completed": completed,
    }


def parse_args(argv: Sequence[str] | None = None) -> tuple[Config, str | None]:
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--serial", help="Exact ADB device serial")
    target.add_argument("--transport-id", help="Exact ADB transport ID")
    parser.add_argument("--apk", type=Path, required=True, help="Sideload APK to inspect and certify")
    parser.add_argument("--install", action="store_true", help="Explicitly allow adb install -r")
    parser.add_argument("--test-apk", type=Path, help="Optional sideload instrumentation test APK")
    parser.add_argument(
        "--install-test",
        action="store_true",
        help="Explicitly allow adb install -r for --test-apk",
    )
    parser.add_argument(
        "--instrumentation-component",
        default=DEFAULT_INSTRUMENTATION_COMPONENT,
    )
    parser.add_argument(
        "--instrumentation-class",
        default=DEFAULT_INSTRUMENTATION_CLASS,
    )
    parser.add_argument("--aapt2", help="Path to aapt2 (auto-discovered by default)")
    parser.add_argument("--fixture-url", required=True, help="Host fixture control URL")
    parser.add_argument("--fixture-host-port", type=int, required=True)
    parser.add_argument("--fixture-device-port", type=int, required=True)
    parser.add_argument("--fixture-state-path", default="/__fixture__/state")
    parser.add_argument("--fixture-evidence-path", default="/__fixture__/evidence")
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--capture-screenshots", action="store_true")
    parser.add_argument("--confirm-synthetic-fixture", action="store_true")
    parser.add_argument("--radio-cycle", choices=("wifi", "data"))
    parser.add_argument("--dangerous-radio-confirm")
    parser.add_argument(
        "--radio-preview-receipt",
        type=Path,
        help="Receipt written by a matching radio-enabled --dry-run",
    )
    parser.add_argument("--pause-seconds", type=float, default=1.0)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--app-lifecycle-smoke",
        action="store_true",
        help="After instrumentation, launch/background/foreground/recreate the installed sideload app",
    )
    args = parser.parse_args(argv)
    if not 1 <= args.fixture_host_port <= 65535 or not 1 <= args.fixture_device_port <= 65535:
        parser.error("fixture ports must be in the range 1..65535")
    if args.pause_seconds < 0 or args.pause_seconds > 30:
        parser.error("--pause-seconds must be between 0 and 30")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,120}", args.scenario):
        parser.error("--scenario must be a short fixture scenario name, not content or a path")
    if args.install_test and not args.test_apk:
        parser.error("--install-test requires --test-apk")
    component_pattern = rf"{re.escape(SIDELOAD_TEST_PACKAGE)}/[A-Za-z0-9_.$]+"
    if not re.fullmatch(component_pattern, args.instrumentation_component):
        parser.error("--instrumentation-component must target the sideload test package")
    if not re.fullmatch(r"[A-Za-z0-9_.$]+#[A-Za-z0-9_$]+", args.instrumentation_class):
        parser.error("--instrumentation-class must be one exact Class#method selector")
    selector = ("-s", args.serial) if args.serial else ("-t", args.transport_id)
    config = Config(
        target_flag=selector[0],
        target_value=selector[1],
        apk=args.apk.resolve(),
        install=args.install,
        test_apk=args.test_apk.resolve() if args.test_apk else None,
        install_test=args.install_test,
        instrumentation_component=args.instrumentation_component,
        instrumentation_class=args.instrumentation_class,
        fixture_url=args.fixture_url,
        fixture_host_port=args.fixture_host_port,
        fixture_device_port=args.fixture_device_port,
        fixture_state_path=args.fixture_state_path,
        fixture_evidence_path=args.fixture_evidence_path,
        scenario=args.scenario,
        output_dir=args.output_dir.resolve(),
        screenshots=args.capture_screenshots,
        synthetic_fixture_confirmed=args.confirm_synthetic_fixture,
        radio_cycle=args.radio_cycle,
        radio_confirmation=args.dangerous_radio_confirm,
        radio_preview_receipt=args.radio_preview_receipt.resolve() if args.radio_preview_receipt else None,
        pause_seconds=args.pause_seconds,
        dry_run=args.dry_run,
        app_lifecycle_smoke=args.app_lifecycle_smoke,
    )
    return config, args.aapt2


def main(argv: Sequence[str] | None = None) -> int:
    try:
        config, aapt2_override = parse_args(argv)
        aapt2 = find_aapt2(aapt2_override)
        apk = inspect_apk(config.apk, aapt2)
        test_apk = (
            inspect_apk(
                config.test_apk,
                aapt2,
                expected_package=SIDELOAD_TEST_PACKAGE,
                allow_empty_version=True,
            )
            if config.test_apk
            else None
        )
        plan = build_plan(config)
        if config.dry_run:
            preview = {
                "mode": "dry-run",
                "application": {
                    "package": apk.package,
                    "version_name": apk.version_name,
                    "version_code": apk.version_code,
                    "apk_sha256": apk.sha256,
                },
                "test_application": (
                    {
                        "package": test_apk.package,
                        "version_name": test_apk.version_name,
                        "version_code": test_apk.version_code,
                        "apk_sha256": test_apk.sha256,
                    }
                    if test_apk
                    else {"package": SIDELOAD_TEST_PACKAGE, "source": "already-installed"}
                ),
                "device": {"selector": config.target_flag, "id_hash": hashed_identifier(config.target_value)},
                "fixture": {
                    "scenario": sanitize_text(config.scenario)[:120],
                    "host_port": config.fixture_host_port,
                    "device_port": config.fixture_device_port,
                },
                "operations": [operation_preview(operation) for operation in plan],
            }
            if config.radio_cycle:
                receipt_path = config.radio_preview_receipt or config.output_dir / "radio-preview-receipt.json"
                receipt_path.parent.mkdir(parents=True, exist_ok=True)
                receipt_path.write_text(
                    json.dumps(
                        {
                            "schema_version": 1,
                            "fingerprint": radio_preview_fingerprint(config),
                            "device_id_hash": hashed_identifier(config.target_value),
                            "radio_cycle": config.radio_cycle,
                            "scenario": sanitize_text(config.scenario)[:120],
                        },
                        indent=2,
                    )
                    + "\n",
                    encoding="utf-8",
                )
                preview["radio_preview_receipt"] = receipt_path.name
            print(json.dumps(preview, indent=2))
            return 0
        if not config.install:
            # Fail before fixture or lifecycle mutation if the selected device
            # does not already have this exact inspected sideload artifact.
            validate_installed_version(config, apk)
        if test_apk and not config.install_test:
            validate_installed_version(config, test_apk)
        elif not test_apk:
            verify_test_package_present(config)
        evidence = execute(config, apk, test_apk, plan)
        evidence_path = config.output_dir / "evidence.json"
        evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(f"Certification passed; privacy-safe evidence: {evidence_path}")
        return 0
    except (CertificationError, subprocess.TimeoutExpired) as error:
        print(f"Certification failed: {sanitize_text(str(error))}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
