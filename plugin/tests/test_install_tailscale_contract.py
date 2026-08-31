"""Static contract checks for install.sh's Tailscale setup guidance."""

from __future__ import annotations

import unittest
from pathlib import Path


class InstallTailscaleContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.script = (
            Path(__file__).resolve().parents[2] / "install.sh"
        ).read_text(encoding="utf-8")

    def test_auto_enable_uses_dashboard_first_defaults(self) -> None:
        self.assertIn(
            'plugin.relay.tailscale_cli enable >/dev/null 2>&1',
            self.script,
        )
        self.assertNotIn(
            'plugin.relay.tailscale_cli enable --port 8767 --api-port 8642',
            self.script,
        )
        self.assertIn(
            "HTTPS :10443 for local Dashboard :9119, plus optional API :8642",
            self.script,
        )

    def test_installed_shim_marks_direct_relay_as_explicit_legacy(self) -> None:
        self.assertIn(
            "hermes-relay-tailscale enable [--dashboard-listener-port 10443] "
            "[--dashboard-target-port 9119] [--api-port 8642] [--no-api]",
            self.script,
        )
        self.assertIn(
            "hermes-relay-tailscale disable [--dashboard-listener-port 10443] "
            "[--dashboard-target-port 9119] [--api-port 8642] [--no-api]",
            self.script,
        )
        self.assertIn(
            "hermes-relay-tailscale enable --dashboard-listener-port 443  "
            "# advanced override; only when :443 is free",
            self.script,
        )
        self.assertIn(
            "hermes-relay-tailscale enable --port 8767   # explicit legacy/direct Relay",
            self.script,
        )
        self.assertIn(
            "hermes-relay-tailscale disable --port 8767  # explicit legacy/direct Relay",
            self.script,
        )

    def test_completion_summary_does_not_recommend_publication_of_8767(self) -> None:
        remote_access = self.script.split(
            '${C_BOLD}${C_CYAN}Remote access${C_RESET}', 1
        )[1].split(
            '${C_BOLD}${C_CYAN}Self-setup / troubleshoot${C_RESET}', 1
        )[0]
        self.assertIn("HTTPS :10443 → Dashboard :9119 + optional API :8642", remote_access)
        self.assertIn(
            "enable --dashboard-listener-port 443${C_RESET} "
            "${C_DIM}# advanced override; only if :443 is free",
            remote_access,
        )
        self.assertIn("legacy/direct Relay only", remote_access)
        self.assertNotIn("publish relay :8767", remote_access.lower())
        self.assertNotIn("--https=9119", remote_access)


if __name__ == "__main__":
    unittest.main()
