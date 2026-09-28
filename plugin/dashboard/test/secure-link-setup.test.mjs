import test from "node:test";
import assert from "node:assert/strict";

const calls = [];
globalThis.window = { __HERMES_PLUGIN_SDK__: {
  fetchJSON: async (path, opts) => {
    calls.push({ path, opts });
    return path.includes("preflight") ? { schema_version: 1 } : { results: [{ surface: "api", reachable: true }] };
  },
} };
const { getSecureLinkPreflight, probeEndpoints } = await import("../src/lib/api.js");

test("setup input stays inside a read-only preflight query", async () => {
  calls.length = 0;
  assert.deepEqual(await getSecureLinkPreflight({ host: "relay.example&port=22", port: "9443" }), { schema_version: 1 });
  assert.equal(calls[0].path, "/api/plugins/hermes-relay/remote-access/secure-link/preflight?host=relay.example%26port%3D22&port=9443");
  assert.equal(calls[0].opts, undefined);
});

test("pinned routes are not sent to an unpaired system-TLS probe", async () => {
  calls.length = 0;
  const pinned = { surface: "dashboard", url: "https://relay.example:9443/dashboard", requires_paired_client: true };
  const result = await probeEndpoints([pinned]);
  assert.equal(calls.length, 0);
  assert.equal(result.results[0].reachable, null);
  assert.equal(result.results[0].requires_paired_client, true);
  const mixed = await probeEndpoints([pinned, { surface: "api", url: "http://192.168.1.20:8642" }]);
  assert.equal(JSON.parse(calls[0].opts.body).candidates.length, 1);
  assert.equal(mixed.results.length, 2);
});
