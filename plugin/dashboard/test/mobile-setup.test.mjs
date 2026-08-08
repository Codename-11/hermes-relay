import test from "node:test";
import assert from "node:assert/strict";

import {
  buildDashboardSetupPayload,
  canonicalDashboardOrigin,
} from "../src/lib/mobile-setup.mjs";

test("canonicalDashboardOrigin keeps the HTTP(S) dashboard base", () => {
    assert.equal(
    canonicalDashboardOrigin("https://user:pass@Agent.Example:9443/hermes?token=secret#part"),
    "https://agent.example:9443/hermes",
  );
  assert.equal(
    canonicalDashboardOrigin({ origin: "http://192.168.1.50:9119" }),
    "http://192.168.1.50:9119",
  );
});

test("active Relay tab is removed while a proxy prefix is preserved", () => {
  assert.equal(
    canonicalDashboardOrigin({
      href: "https://agent.example/hermes/relay?token=secret",
      hash: "",
    }),
    "https://agent.example/hermes",
  );
  assert.equal(
    canonicalDashboardOrigin({
      href: "https://agent.example/hermes/#/relay",
      hash: "#/relay",
    }),
    "https://agent.example/hermes",
  );
});

test("buildDashboardSetupPayload carries only dashboard_url", () => {
  const parsed = JSON.parse(
    buildDashboardSetupPayload("https://cloud-agent.example/hermes?token=secret"),
  );

  assert.deepEqual(parsed, { dashboard_url: "https://cloud-agent.example/hermes" });
  assert.deepEqual(Object.keys(parsed), ["dashboard_url"]);
});

test("invalid and non-HTTP origins are rejected", () => {
  assert.equal(canonicalDashboardOrigin("file:///tmp/dashboard.html"), null);
  assert.equal(canonicalDashboardOrigin("not a URL"), null);
  assert.throws(
    () => buildDashboardSetupPayload("file:///tmp/dashboard.html"),
    /valid HTTP\(S\) origin/,
  );
});
