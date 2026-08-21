from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest import mock


SCRIPT = Path(__file__).parents[1] / "android-gateway-certify.py"
SPEC = importlib.util.spec_from_file_location("android_gateway_certify", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class AndroidGatewayCertifyTest(unittest.TestCase):
    def config(self, **overrides):
        values = dict(
            target_flag="-s",
            target_value="device-secret-serial",
            apk=Path("C:/private/build/app.apk"),
            install=False,
            test_apk=None,
            install_test=False,
            instrumentation_component=module.DEFAULT_INSTRUMENTATION_COMPONENT,
            instrumentation_class=module.DEFAULT_INSTRUMENTATION_CLASS,
            fixture_url="http://private-host.example:8765",
            fixture_host_port=8765,
            fixture_device_port=8765,
            fixture_state_path="/__fixture__/state",
            fixture_evidence_path="/__fixture__/evidence",
            scenario="missing-terminal-foreground",
            output_dir=Path("C:/private/evidence"),
            screenshots=False,
            synthetic_fixture_confirmed=False,
            radio_cycle=None,
            radio_confirmation=None,
            radio_preview_receipt=None,
            pause_seconds=0,
            dry_run=True,
            app_lifecycle_smoke=False,
        )
        values.update(overrides)
        return module.Config(**values)

    def test_plan_targets_only_sideload_package_and_uses_fixture_socket_loss(self):
        self.assertEqual(
            "com.hermesandroid.relay.viewmodel.GatewayExternalFixtureInstrumentedTest"
            "#terminalGapActivate_externalFixtureRecoversFromAuthoritativeHttpHistory",
            module.DEFAULT_INSTRUMENTATION_CLASS,
        )
        plan = module.build_plan(self.config())
        commands = [operation.args for operation in plan if operation.args]
        flattened = "\n".join(" ".join(command) for command in commands)
        self.assertIn(module.SIDELOAD_PACKAGE, flattened)
        self.assertNotIn("com.axiomlabs.hermesrelay ", flattened)
        self.assertTrue(all(command[1:3] == ("-s", "device-secret-serial") for command in commands))
        instrumentation = next(operation for operation in plan if operation.kind == "instrumentation")
        self.assertIn("gatewayFixtureBaseUrl", instrumentation.args)
        self.assertIn("http://127.0.0.1:8765", instrumentation.args)
        self.assertIn(module.DEFAULT_INSTRUMENTATION_CLASS, instrumentation.args)
        stop_index = next(
            index for index, operation in enumerate(plan)
            if operation.label == "stop sideload app before instrumentation"
        )
        instrumentation_index = plan.index(instrumentation)
        self.assertLess(stop_index, instrumentation_index)
        self.assertNotIn("svc wifi", flattened)
        self.assertNotIn("svc data", flattened)

    def test_install_replace_is_present_only_with_explicit_install_flag(self):
        without = module.build_plan(self.config())
        with_install = module.build_plan(self.config(install=True))
        self.assertFalse(any("install sideload" in operation.label for operation in without))
        install = next(operation for operation in with_install if "install sideload" in operation.label)
        self.assertIn("-r", install.args)

    def test_screenshots_require_synthetic_fixture_confirmation(self):
        with self.assertRaisesRegex(module.CertificationError, "app-lifecycle-smoke"):
            module.build_plan(self.config(screenshots=True))
        with self.assertRaisesRegex(module.CertificationError, "synthetic"):
            module.build_plan(self.config(screenshots=True, app_lifecycle_smoke=True))
        plan = module.build_plan(
            self.config(
                screenshots=True,
                synthetic_fixture_confirmed=True,
                app_lifecycle_smoke=True,
            )
        )
        self.assertEqual(3, sum(operation.kind == "screenshot" for operation in plan))

    def test_radio_cycle_requires_explicit_confirmation(self):
        preview = module.build_plan(self.config(radio_cycle="wifi", dry_run=True))
        self.assertIn("disable device-wide wifi", [operation.label for operation in preview])
        with self.assertRaisesRegex(module.CertificationError, "dangerous-radio-confirm"):
            module.build_plan(self.config(radio_cycle="wifi", dry_run=False))
        with self.assertRaisesRegex(module.CertificationError, "prior --dry-run"):
            module.build_plan(
                self.config(
                    radio_cycle="wifi",
                    dry_run=False,
                    radio_confirmation=module.RADIO_CONFIRMATION,
                )
            )
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / "receipt.json"
            config = self.config(
                radio_cycle="wifi",
                dry_run=False,
                radio_confirmation=module.RADIO_CONFIRMATION,
                radio_preview_receipt=receipt,
            )
            receipt.write_text(
                '{"fingerprint":"' + module.radio_preview_fingerprint(config) + '"}', encoding="utf-8"
            )
            plan = module.build_plan(config)
            self.assertIn("enable device-wide wifi", [operation.label for operation in plan])

    def test_redaction_removes_secrets_hosts_addresses_paths_and_hashes_ids(self):
        raw = (
            "Gateway socket down session=abc123 run=run-secret "
            "Authorization=Bearer-secret https://private.example/api 10.2.3.4:8765 "
            "C:\\Users\\ExampleUser\\secret.txt /home/example-user/token\n"
            "conversation payload should be discarded\n"
            "Gateway turn settled generation=gen-9"
        )
        safe = module.sanitize_logcat(raw)
        rendered = "\n".join(safe)
        self.assertEqual(2, len(safe))
        for secret in (
            "abc123",
            "run-secret",
            "Bearer-secret",
            "private.example",
            "10.2.3.4",
            "ExampleUser",
            "gen-9",
            "conversation payload",
        ):
            self.assertNotIn(secret, rendered)
        self.assertIn("session=<id:", rendered)
        self.assertIn("<url>", rendered)
        self.assertIn("<address>", rendered)
        self.assertIn("<path>", rendered)

    def test_preview_does_not_expose_apk_path(self):
        operation = module.Operation(
            "adb",
            "install sideload APK",
            ("adb", "-s", "serial", "install", "-r", "C:/private/build/app.apk"),
        )
        preview = module.operation_preview(operation)
        self.assertEqual("<apk>", preview["command"].split()[-1])
        self.assertNotIn("private", preview["command"])

    def test_preview_does_not_expose_device_selector(self):
        operation = module.Operation("adb", "verify", ("adb", "-s", "secret-serial", "get-state"))
        preview = module.operation_preview(operation)
        self.assertNotIn("secret-serial", preview["command"])
        self.assertIn("<device>", preview["command"])

    def test_apk_inspection_rejects_non_sideload_package(self):
        badging = "package: name='com.axiomlabs.hermesrelay' versionCode='46' versionName='1.11.0'\n"
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            apk.write_bytes(b"not-a-real-apk")
            with mock.patch.object(
                module.subprocess,
                "run",
                return_value=CompletedProcess(["aapt2"], 0, badging, ""),
            ):
                with self.assertRaisesRegex(module.CertificationError, "unexpected APK"):
                    module.inspect_apk(apk, "aapt2")

    def test_instrumentation_apk_normalizes_empty_manifest_version(self):
        badging = (
            "package: name='com.axiomlabs.hermesrelay.sideload.test' "
            "versionCode='' versionName=''\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            apk.write_bytes(b"not-a-real-apk")
            with mock.patch.object(
                module.subprocess,
                "run",
                return_value=CompletedProcess(["aapt2"], 0, badging, ""),
            ):
                metadata = module.inspect_apk(
                    apk,
                    "aapt2",
                    expected_package=module.SIDELOAD_TEST_PACKAGE,
                    allow_empty_version=True,
                )
        self.assertEqual("0", metadata.version_code)
        self.assertEqual("null", metadata.version_name)

    def test_installed_version_must_match_inspected_apk(self):
        config = self.config(dry_run=False)
        apk = module.ApkMetadata(module.SIDELOAD_PACKAGE, "1.11.0-sideload", "46", "digest")
        with mock.patch.object(module, "inspect_installed_package", return_value=("1.10.0-sideload", "45")):
            with self.assertRaisesRegex(module.CertificationError, "does not match"):
                module.validate_installed_version(config, apk)

    def test_fixture_evidence_keeps_only_bounded_metadata(self):
        raw = {
            "scenario": "missing-terminal-foreground",
            "entries": [
                {
                    "sequence": 1,
                    "kind": "event",
                    "event_type": "message.delta",
                    "scope": "exact",
                    "payload": "private conversation",
                    "token": "secret",
                }
            ],
        }
        safe = module.safe_fixture_evidence(raw, "missing-terminal-foreground")
        self.assertEqual(
            {"sequence": 1, "kind": "event", "event_type": "message.delta", "scope": "exact"},
            safe["entries"][0],
        )

    def test_fixture_evidence_redacts_unexpected_metadata_strings(self):
        raw = {
            "scenario": "missing-terminal-foreground",
            "entries": [{"sequence": 1, "kind": "event with private content"}],
        }
        safe = module.safe_fixture_evidence(raw, "missing-terminal-foreground")
        self.assertEqual("<redacted>", safe["entries"][0]["kind"])

    def test_fixture_proof_requires_full_terminal_gap_contract(self):
        entries = [
            {"kind": "rpc", "method": "prompt.submit"},
            {"kind": "fault", "outcome": "socket_gap"},
            {"kind": "rpc", "method": "session.activate"},
            {"kind": "history", "outcome": "read"},
            {"kind": "runtime", "outcome": "settled"},
        ]
        module.validate_fixture_proof({"entries": entries}, [{"running": False}])
        with self.assertRaisesRegex(module.CertificationError, "session.activate"):
            module.validate_fixture_proof({"entries": entries[:2] + entries[3:]}, [{"running": False}])

    def test_test_apk_install_requires_both_path_and_flag(self):
        with self.assertRaisesRegex(module.CertificationError, "requires --test-apk"):
            module.build_plan(self.config(install_test=True))
        plan = module.build_plan(
            self.config(test_apk=Path("C:/private/test.apk"), install_test=True)
        )
        install = next(operation for operation in plan if operation.label == "install sideload test APK")
        self.assertEqual(("install", "-r"), install.args[3:5])

    def test_instrumentation_result_must_report_at_least_one_success(self):
        self.assertEqual(1, module.require_instrumentation_success("OK (1 test)\n"))
        for output in ("OK (0 tests)\n", "FAILURES!!!\n", "INSTRUMENTATION_FAILED: crash\n"):
            with self.subTest(output=output):
                with self.assertRaises(module.CertificationError):
                    module.require_instrumentation_success(output)


if __name__ == "__main__":
    unittest.main()
