import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");

test("Hermes-Relay navigation separates overview, devices, activity, and settings", () => {
  const source = read("../src/index.jsx");

  for (const label of ["Overview", "Devices", "Activity", "Remote Access", "Git", "Settings"]) {
    assert.match(source, new RegExp(`label: "${label}"`));
  }
  assert.doesNotMatch(source, /label: "Management"|label: "Media"/);
  assert.match(source, /<Tabs defaultValue="overview"/);
});

test("overview does not present transient Bridge or raw media registry counters", () => {
  const source = read("../src/tabs/RelayManagement.jsx");

  assert.doesNotMatch(source, /pending_commands|media_entry_count|Pending \/ media/);
  assert.match(source, /label="Last Bridge event"/);
  assert.match(source, /label="Remote access"/);
});

test("pairing uses the host dialog and keeps advanced connection options secondary", () => {
  const source = read("../src/components/PairDialog.jsx");

  assert.match(source, /<Dialog open=\{open\}/);
  assert.match(source, /<DialogContent className="hermes-relay-plugin hr-pair-dialog">/);
  assert.match(source, /Advanced connection options/);
  assert.match(source, /dashboard_url: dashboardUrl/);
  assert.match(source, /Unsafe pairing route blocked/);
  assert.match(source, /route\.protection/);
  assert.match(source, /surface\.label/);
  assert.doesNotMatch(source, /hr-modal-backdrop/);
});

test("remote access presents Dashboard ingress and keeps direct Relay explicitly legacy", () => {
  const source = read("../src/tabs/RemoteAccess.jsx");

  assert.match(source, /Dashboard → host :9119/);
  assert.match(source, /API fallback → host :8642/);
  assert.match(source, /Direct Relay → host :8767/);
  assert.match(source, /serve_services/);
  assert.match(source, /listen_ports/);
  assert.match(source, /recommended_listener_port/);
  assert.match(source, /avoiding conflicts with Traefik, Caddy, nginx/);
  assert.match(source, /Enable HTTPS :\$\{dashboardState\.recommendedPort\} ingress/);
  assert.match(source, /old listeners do not satisfy recommended setup/);
  assert.match(source, /Disable old :443 listener after re-pairing/);
  assert.match(source, /Disable old :9119 listener after re-pairing/);
  assert.match(source, /Enable optional API :8642/);
  assert.match(source, /Disable legacy :8767 after re-pairing/);
  assert.match(source, /Public Dashboard origin/);
  assert.match(source, /Legacy Relay proxy path/);
  assert.match(source, /probeEndpoints\(previewEndpoints\)/);
});

test("media is labeled as a bounded token diagnostic", () => {
  const source = read("../src/tabs/MediaInspector.jsx");

  assert.match(source, /<CardTitle>Media tokens<\/CardTitle>/);
  assert.match(source, /Bare-path deliveries are not included/);
});

test("switch fallback preserves switch semantics when the host omits the primitive", () => {
  const source = read("../src/lib/ui-shims.jsx");

  assert.match(source, /role="switch"/);
  assert.match(source, /aria-checked=\{!!checked\}/);
  assert.doesNotMatch(source, /type="checkbox"[\s\S]*onCheckedChange/);
});
