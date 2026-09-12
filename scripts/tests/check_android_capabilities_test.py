import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("capabilities", ROOT / "scripts/check-android-capabilities.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class PlayManifestTest(unittest.TestCase):
    def roots(self):
        return [ET.parse(ROOT / f"app/src/{flavor}/AndroidManifest.xml").getroot()
                for flavor in ("main", "googlePlay")]

    def test_current_voice_only_split(self):
        checker.validate(self.roots())

    def test_transitive_sensitive_permission_rejected(self):
        for permission in checker.FORBIDDEN:
            with self.subTest(permission=permission):
                roots = self.roots()
                ET.SubElement(roots[-1], "uses-permission", {checker.ANDROID + "name": f"android.permission.{permission}"})
                with self.assertRaises(ValueError):
                    checker.validate(roots)

    def test_renamed_accessibility_service_rejected(self):
        roots = self.roots()
        ET.SubElement(roots[-1].find("application"), "service", {
            checker.ANDROID + "name": "third.party.UnexpectedService",
            checker.ANDROID + "permission": "android.permission.BIND_ACCESSIBILITY_SERVICE",
        })
        with self.assertRaises(ValueError):
            checker.validate(roots)

    def test_exported_microphone_service_rejected(self):
        roots = self.roots()
        for node in roots[0].findall("application/service"):
            if node.get(checker.ANDROID + "name").endswith("VoiceOverlayForegroundService"):
                node.set(checker.ANDROID + "exported", "true")
        with self.assertRaises(ValueError):
            checker.validate(roots)
