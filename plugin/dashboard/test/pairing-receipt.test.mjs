import test from "node:test";
import assert from "node:assert/strict";
import QRCode from "qrcode";

import { pairingQrRenderOptions } from "../src/lib/pairing-qr.mjs";

import {
  classifyPublicRouteInput,
  dashboardServeState,
  pairingEndpointReceipt,
  pairingProbeStatus,
  pairingSurfaceProbes,
} from "../src/lib/pairing-receipt.mjs";

test("pairing QR keeps integer modules and a four-module quiet zone", () => {
  assert.deepEqual(pairingQrRenderOptions(), {
    scale: 4,
    margin: 4,
    errorCorrectionLevel: "L",
  });
  assert.equal(Object.hasOwn(pairingQrRenderOptions(), "width"), false);
});

test("certificate-bearing pairing invite fits the Dashboard QR renderer", () => {
  const payload = JSON.stringify({
    hermes: 2,
    endpoints: [{ role: "plugin_proxy", proxy: {
      url: "https://relay.example:9443",
      cert_der: "a".repeat(2500),
      pin_sha256: "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    } }],
  });
  assert.throws(() => QRCode.create(payload, { errorCorrectionLevel: "M" }), /too big/i);
  const qr = QRCode.create(payload, pairingQrRenderOptions());
  assert.ok(qr.version <= 40);
});

test("Secure Link receipt derives only its advertised namespaces without exposing trust material", () => {
  const payload = { endpoints: [{ role: "plugin_proxy", proxy: {
    url: "https://relay.example:9443",
    pin_sha256: "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    cert_der: "public-certificate",
    surfaces: ["relay", "dashboard"],
  } }] };
  const receipt = pairingEndpointReceipt(payload);
  assert.deepEqual(receipt.blockingIssues, []);
  assert.equal(receipt.routes[0].protection, "Secure Link · paired TLS");
  assert.deepEqual(receipt.routes[0].surfaces.map(s => s.url), [
    "https://relay.example:9443/dashboard", "wss://relay.example:9443/relay/ws",
  ]);
  const probes = pairingSurfaceProbes(receipt);
  assert.ok(probes.every(probe => probe.requires_paired_client));
  assert.deepEqual(pairingProbeStatus(probes[0]), { healthy: null, label: "Import QR to verify" });
  assert.equal(JSON.stringify(receipt).includes("public-certificate"), false);
  assert.equal(JSON.stringify(receipt).includes("sha256/"), false);
  payload.endpoints[0].proxy.pin_sha256 = "wrong";
  assert.ok(pairingEndpointReceipt(payload).blockingIssues.length);
});

test("Secure Link receipt rejects path traversal rather than normalizing it into an origin", () => {
  const receipt = pairingEndpointReceipt({ endpoints: [{ role: "plugin_proxy", proxy: {
    url: "https://relay.example/a/..", pin_sha256: "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    cert_der: "public-certificate", surfaces: ["relay"],
  } }] });
  assert.ok(receipt.blockingIssues.length);
});

test("only Dashboard ingress Relay auth challenges are healthy", () => {
  assert.deepEqual(
    pairingProbeStatus({
      surface: "relay",
      url: "wss://dashboard.example/api/plugins/hermes-relay/transport",
      status: 401,
      reachable: false,
    }),
    { healthy: true, label: "Auth required" },
  );
  assert.deepEqual(
    pairingProbeStatus({
      surface: "relay",
      url: "https://dashboard.example/base/api/plugins/hermes-relay/transport/health",
      status: 403,
      reachable: false,
    }),
    { healthy: true, label: "Auth required" },
  );
  assert.deepEqual(
    pairingProbeStatus({
      surface: "relay",
      url: "ws://relay.example:8767",
      status: 401,
      reachable: false,
    }),
    { healthy: false, label: "HTTP 401" },
  );
  assert.deepEqual(
    pairingProbeStatus({
      surface: "relay",
      url: "ws://relay.example:8767/ws",
      status: 403,
      reachable: false,
    }),
    { healthy: false, label: "HTTP 403" },
  );
  assert.deepEqual(
    pairingProbeStatus({
      surface: "relay",
      url: "wss://relay.example:8767/health",
      status: 404,
      reachable: false,
    }),
    { healthy: false, label: "HTTP 404" },
  );
  assert.deepEqual(
    pairingProbeStatus({
      surface: "relay",
      url: "wss://dashboard.example/api/plugins/hermes-relay/transportish",
      status: 401,
      reachable: false,
    }),
    { healthy: false, label: "HTTP 401" },
  );
  assert.deepEqual(
    pairingProbeStatus({
      surface: "api",
      url: "https://api.example:8642",
      status: 401,
      reachable: false,
    }),
    { healthy: false, label: "HTTP 401" },
  );
});

test("Dashboard Serve migration requires the dedicated recommended listener", () => {
  assert.deepEqual(
    dashboardServeState({ active: true, listen_ports: [9119] }),
    {
      active: true,
      listenPorts: [9119],
      recommendedPort: 10443,
      recommendedActive: false,
      migration443: false,
      migration9119: true,
    },
  );
  assert.deepEqual(
    dashboardServeState({ active: true, listen_ports: [9119, 443, 443] }),
    {
      active: true,
      listenPorts: [443, 9119],
      recommendedPort: 10443,
      recommendedActive: false,
      migration443: true,
      migration9119: true,
    },
  );
  assert.deepEqual(
    dashboardServeState({ active: true, listen_ports: [10443, 443, 9119] }),
    {
      active: true,
      listenPorts: [443, 9119, 10443],
      recommendedPort: 10443,
      recommendedActive: true,
      migration443: true,
      migration9119: true,
    },
  );
  assert.equal(
    dashboardServeState({ active: false, listen_ports: [10443] }).recommendedActive,
    false,
  );
  assert.equal(
    dashboardServeState({ active: true, listen_ports: [11443] }, 11443).recommendedActive,
    true,
  );
});

test("pairing receipt exposes every route surface without credentials", () => {
  const receipt = pairingEndpointReceipt(JSON.stringify({
    dashboard_url: "https://dashboard.example",
    key: "do-not-render",
    relay: { code: "ABC123" },
    endpoints: [
      {
        role: "tailscale",
        priority: 0,
        dashboard: { url: "http://100.64.0.5:9119" },
        relay: { url: "ws://100.64.0.5:9119/api/plugins/hermes-relay/transport" },
        api: { host: "100.64.0.5", port: 8642, tls: false },
      },
      {
        role: "public",
        priority: 1,
        dashboard: { url: "https://agent.example" },
        relay: { url: "wss://agent.example/api/plugins/hermes-relay/transport" },
        api: { host: "agent.example", port: 443, tls: true },
      },
    ],
  }));

  assert.deepEqual(receipt.blockingIssues, []);
  assert.equal(receipt.routes[0].protection, "Tailnet encrypted · no application TLS");
  assert.deepEqual(
    receipt.routes[0].surfaces.map(({ surface, url }) => ({ surface, url })),
    [
      { surface: "dashboard", url: "http://100.64.0.5:9119" },
      { surface: "relay", url: "ws://100.64.0.5:9119/api/plugins/hermes-relay/transport" },
      { surface: "api", url: "http://100.64.0.5:8642" },
    ],
  );
  assert.equal(
    classifyPublicRouteInput("https://agent.example:8767").kind,
    "legacy-relay-path",
  );
  assert.equal(JSON.stringify(receipt).includes("do-not-render"), false);
  assert.equal(JSON.stringify(receipt).includes("ABC123"), false);

  const probes = pairingSurfaceProbes(receipt);
  assert.equal(probes.length, 6);
  assert.deepEqual(probes[3], {
    role: "public",
    priority: 1,
    surface: "dashboard",
    url: "https://agent.example",
  });
});

test("malformed or plaintext public candidates block the invite receipt", () => {
  const receipt = pairingEndpointReceipt({
    endpoints: [{
      role: "public",
      priority: 0,
      dashboard: { url: "http://agent.example" },
      relay: { url: "ws://agent.example:8767" },
      api: { host: "agent.example", port: 8642, tls: false },
    }],
  });

  assert.deepEqual(receipt.blockingIssues, [
    "public: Dashboard must use HTTPS",
    "public: Relay must use WSS",
    "public: Direct API must use HTTPS",
  ]);

  assert.match(
    pairingEndpointReceipt("not JSON").blockingIssues[0],
    /not valid JSON/,
  );

  const legacy = pairingEndpointReceipt({
    endpoints: [{
      role: "public_legacy",
      priority: 0,
      relay: { url: "ws://agent.example:8767" },
    }],
  });
  assert.deepEqual(legacy.blockingIssues, ["public_legacy: Relay must use WSS"]);
});

test("public route input distinguishes dashboard origins from legacy Relay paths", () => {
  assert.deepEqual(classifyPublicRouteInput("https://agent.example"), {
    kind: "dashboard-origin",
    url: "https://agent.example",
  });
  assert.deepEqual(
    classifyPublicRouteInput("https://agent.example/api/plugins/hermes-relay/transport"),
    {
      kind: "legacy-relay-path",
      url: "https://agent.example/api/plugins/hermes-relay/transport",
    },
  );
  assert.equal(classifyPublicRouteInput("https://user:secret@agent.example").kind, "invalid");
  assert.equal(classifyPublicRouteInput("https://agent.example?token=secret").kind, "invalid");
});
