const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useRef, useCallback, useMemo } = SDK.hooks;

import QRCode from "qrcode";
import { mintPairingWithMode, probeEndpoints } from "../lib/api.js";
import { canonicalDashboardOrigin } from "../lib/mobile-setup.mjs";
import {
  pairingEndpointReceipt,
  pairingProbeKey,
  pairingSurfaceProbes,
} from "../lib/pairing-receipt.mjs";
import { Button, Badge } from "../lib/ui-shims.jsx";

const {
  Input,
  Label,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} = SDK.components;

// localStorage keys — per-browser, not per-user. Sensible defaults on first
// open; stick with whatever the operator last used.
const LS_MODE   = "hermes-relay-pair-mode";
const LS_PREFER = "hermes-relay-pair-prefer";
const LS_HOST   = "hermes-relay-pair-host";
const LS_PORT   = "hermes-relay-pair-port";
const LS_TLS    = "hermes-relay-pair-tls";

const MODES = [
  { value: "auto",      label: "Auto (all reachable candidates)" },
  { value: "lan",       label: "LAN only" },
  { value: "tailscale", label: "Tailscale only" },
  { value: "public",    label: "Public Dashboard only" },
];

const PREFER_ROLES = [
  { value: "",          label: "Natural order (Tailscale → Public → LAN)" },
  { value: "lan",       label: "LAN → priority 0" },
  { value: "tailscale", label: "Tailscale → priority 0" },
  { value: "public",    label: "Public → priority 0" },
];

function readString(key, fallback) {
  try { return window.localStorage.getItem(key) ?? fallback; }
  catch (_e) { return fallback; }
}
function writeString(key, value) {
  try { window.localStorage.setItem(key, value); } catch (_e) { /* best-effort */ }
}

function loadSettings() {
  const rawPort = parseInt(readString(LS_PORT, ""), 10);
  return {
    mode:   readString(LS_MODE, "auto") || "auto",
    prefer: readString(LS_PREFER, "") || "",
    // Advanced overrides — usually unused since `mode=auto` derives
    // everything from the server's own config (Tailscale + pinned public
    // URL). Kept for operators who specifically want to pin the API-server
    // target from the dashboard.
    host:   readString(LS_HOST, "") || "",
    port:   Number.isFinite(rawPort) && rawPort > 0 && rawPort <= 65535 ? rawPort : 8642,
    tls:    readString(LS_TLS, "") === "true",
  };
}
function saveSettings(s) {
  writeString(LS_MODE, s.mode || "auto");
  writeString(LS_PREFER, s.prefer || "");
  writeString(LS_HOST, s.host || "");
  writeString(LS_PORT, String(s.port || ""));
  writeString(LS_TLS, s.tls ? "true" : "false");
}

function useCountdown(expiresAt) {
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  useEffect(() => {
    if (!expiresAt) return undefined;
    const id = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 1000);
    return () => clearInterval(id);
  }, [expiresAt]);
  if (!expiresAt) return null;
  const remaining = Math.max(0, expiresAt - now);
  if (remaining <= 0) return "expired";
  const m = Math.floor(remaining / 60);
  const s = remaining % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

// Heuristic: the hostname "looks" like it's fronted by a reverse proxy /
// auth-forward gateway (Traefik + Authelia, Cloudflare Access, etc.) rather
// than a direct relay host. Used to warn operators that pinning an API
// override to that hostname will fail with 401/403 unless the phone can
// present the expected auth material — which it can't.
function looksProxyFronted(host) {
  if (!host) return false;
  const h = host.toLowerCase().trim();
  // Raw IP + .local / .ts.net / .lan / loopback → almost certainly not
  // behind a forward-auth gateway.
  if (/^\d+\.\d+\.\d+\.\d+$/.test(h)) return false;
  if (h === "localhost" || h.endsWith(".local") || h.endsWith(".ts.net")) return false;
  // Anything else FQDN-shaped with a public TLD is a strong hint the
  // operator has it behind a proxy. Not perfect, but good enough for a
  // soft warning.
  return h.includes(".") && !h.endsWith(".lan") && !h.endsWith(".home.arpa");
}

export default function PairDialog({ open, onClose }) {
  const [settings, setSettings] = useState(loadSettings);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [state, setState] = useState({ status: "idle" });
  const [probeState, setProbeState] = useState({ status: "idle", results: [] });
  const [copyStatus, setCopyStatus] = useState("");
  // Operator's explicit "yes, I know it's proxy-fronted, mint anyway"
  // acknowledgement. Resets every time the pinned host changes so a new
  // host triggers a fresh consent step — avoids a situation where the
  // operator tabs through hostnames and silently reuses old consent.
  const [proxyConfirmed, setProxyConfirmed] = useState(false);
  const canvasRef = useRef(null);
  const countdown = useCountdown(state.data ? state.data.expires_at : null);

  const receipt = useMemo(
    () => pairingEndpointReceipt(state.data ? state.data.qr_payload : null),
    [state.data],
  );
  const probes = useMemo(() => pairingSurfaceProbes(receipt), [receipt]);
  const probeByKey = useMemo(() => {
    const byKey = new Map();
    (probeState.results || []).forEach((result) => byKey.set(pairingProbeKey(result), result));
    return byKey;
  }, [probeState.results]);

  // Derived — the pinned host in Advanced looks like a forward-auth-
  // gated FQDN. Gates the auto-mint: we don't want the dashboard to
  // silently produce a QR the phone will fail to use.
  const hostLooksProxyFronted = settings.host && looksProxyFronted(settings.host);

  const mint = useCallback(async (s) => {
    const use = s || settings;
    setState({ status: "loading" });
    try {
      const dashboardUrl = canonicalDashboardOrigin(window.location);
      if (!dashboardUrl) {
        throw new Error("This Dashboard does not have a valid HTTP(S) origin for pairing.");
      }
      // Only forward host/port/tls/api_key when the operator has actually
      // pinned an API override — empty host means "use server config".
      const overrides = {};
      if (use.host && use.host.trim()) {
        overrides.host = use.host.trim();
        overrides.port = Number(use.port) || 8642;
        overrides.tls = !!use.tls;
      }
      const data = await mintPairingWithMode({
        mode: use.mode || "auto",
        prefer: use.prefer || undefined,
        dashboard_url: dashboardUrl,
        ...overrides,
      });
      setCopyStatus("");
      setState({ status: "ok", data });
    } catch (err) {
      setState({ status: "error", error: err && err.message ? err.message : String(err) });
    }
  }, [settings]);

  useEffect(() => {
    if (state.status !== "ok" || receipt.blockingIssues.length > 0 || probes.length === 0) {
      setProbeState({ status: "idle", results: [] });
      return undefined;
    }
    let cancelled = false;
    setProbeState({ status: "loading", results: [] });
    probeEndpoints(probes)
      .then((data) => {
        if (cancelled) return;
        setProbeState({
          status: "done",
          results: data && Array.isArray(data.results) ? data.results : [],
        });
      })
      .catch((err) => {
        if (cancelled) return;
        setProbeState({
          status: "error",
          results: [],
          error: err && err.message ? err.message : String(err),
        });
      });
    return () => { cancelled = true; };
  }, [state.status, receipt, probes]);

  useEffect(() => {
    // Gate the auto-mint when the pinned host trips the proxy heuristic
    // and the operator hasn't explicitly confirmed. "Mint anyway" sets
    // proxyConfirmed=true and kicks the mint via the regenerate path.
    if (!open || state.status !== "idle") return;
    if (hostLooksProxyFronted && !proxyConfirmed) return;
    mint();
  }, [open, state.status, mint, hostLooksProxyFronted, proxyConfirmed]);

  useEffect(() => {
    if (state.status !== "ok" || !canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, state.data.qr_payload, {
      width: 280, margin: 2, errorCorrectionLevel: "M",
    }).catch(() => { /* canvas failure non-fatal */ });
  }, [state.status, state.data]);

  useEffect(() => {
    if (open) return;
    setState({ status: "idle" });
    setCopyStatus("");
    setProbeState({ status: "idle", results: [] });
    setAdvancedOpen(false);
    setProxyConfirmed(false);
  }, [open]);

  const updateSetting = useCallback((patch, remint = true) => {
    setSettings((prev) => {
      const next = { ...prev, ...patch };
      saveSettings(next);
      return next;
    });
    // Changing the pinned host means the previous consent doesn't
    // apply — force a fresh confirm step.
    if (Object.prototype.hasOwnProperty.call(patch, "host")) {
      setProxyConfirmed(false);
    }
    // Re-mint with the new settings. Debouncing isn't worth it — the
    // dropdowns only fire on user action, not typing.
    if (remint) setState({ status: "idle" });
  }, []);

  const regenerate = useCallback(() => {
    setState({ status: "idle" });
    // Re-enter the auto-mint path; if the host is proxy-fronted, the
    // confirm block will render instead of an actual mint.
  }, []);

  const confirmProxyAndMint = useCallback(() => {
    setProxyConfirmed(true);
    setState({ status: "idle" });
    // The useEffect watching [state, proxyConfirmed] will fire the
    // mint as soon as both gates are clear.
  }, []);

  const copyInvite = useCallback(async () => {
    const invite = state.data && (state.data.pairing_url || state.data.qr_payload);
    if (!invite) return;
    try {
      await navigator.clipboard.writeText(invite);
      setCopyStatus("Copied invite URL");
    } catch (_err) {
      setCopyStatus("Copy failed; select the URL manually");
    }
  }, [state.data]);

  if (!open) return null;

  const proxyWarning = advancedOpen && hostLooksProxyFronted;
  // Pause the body UI and show the confirm-first block whenever the
  // override is proxy-fronted and hasn't been acknowledged.
  const blockForProxyConsent = hostLooksProxyFronted && !proxyConfirmed;
  const blockForInvalidReceipt = state.status === "ok" && receipt.blockingIssues.length > 0;

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) onClose(); }}>
      <DialogContent className="hermes-relay-plugin hr-pair-dialog">
        <DialogHeader>
          <div className="flex flex-wrap items-center gap-2">
            <DialogTitle>Pair new device</DialogTitle>
            <Badge variant="outline" className="text-xs">Hermes-Relay Plugin</Badge>
          </div>
          <DialogDescription>
            Scan with Hermes-Relay Android or copy the invite for Desktop CLI.
          </DialogDescription>
        </DialogHeader>

        <div className="hr-pair-body">
          <section className="hr-pair-qr-column" aria-label="Hermes-Relay pairing code">
            {blockForProxyConsent ? (
              <div className="rounded-md border border-amber-500/60 bg-amber-500/15 p-3 text-sm space-y-2">
                <div className="font-medium">Proxy-fronted host detected</div>
                <p className="text-xs">
                  <span className="font-mono">{settings.host}</span> appears to require browser
                  authentication that Hermes-Relay Android cannot present to the API route.
                </p>
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" onClick={confirmProxyAndMint}>Mint anyway</Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => updateSetting({ host: "", port: 8642, tls: false })}
                  >
                    Clear override
                  </Button>
                </div>
              </div>
            ) : blockForInvalidReceipt ? (
              <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive space-y-2">
                <div className="font-medium">Unsafe pairing route blocked</div>
                <p className="text-xs">
                  The invite was not shown or copied because its public route is incomplete or insecure.
                </p>
                <ul className="list-disc pl-4 text-xs space-y-1">
                  {receipt.blockingIssues.map((issue) => <li key={issue}>{issue}</li>)}
                </ul>
                <Button size="sm" variant="outline" onClick={regenerate}>Mint a corrected route</Button>
              </div>
            ) : state.status === "loading" ? (
              <div className="hr-pair-loading text-sm text-muted-foreground">Minting a secure code…</div>
            ) : state.status === "error" ? (
              <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                <div className="font-medium mb-1">Minting failed</div>
                <div className="break-words">{state.error}</div>
                <Button className="mt-2" size="sm" variant="outline" onClick={regenerate}>Retry</Button>
              </div>
            ) : state.status === "ok" ? (
              <>
                <div className="hr-qr-frame">
                  <canvas ref={canvasRef} className="block" aria-label="Hermes-Relay pairing QR code" />
                </div>
                <div className="hr-pair-code-row">
                  <div>
                    <div className="text-xs uppercase tracking-wider text-muted-foreground">Pairing code</div>
                    <div className="font-mono text-2xl tracking-widest">{state.data.code}</div>
                  </div>
                  <div className="text-right">
                    <div className="text-xs uppercase tracking-wider text-muted-foreground">Expires in</div>
                    <Badge variant={countdown === "expired" ? "destructive" : "outline"}>
                      {countdown || "—"}
                    </Badge>
                  </div>
                </div>
                {state.data.pairing_url ? (
                  <div className="space-y-2">
                    <Button className="w-full" size="sm" variant="outline" onClick={copyInvite}>
                      Copy invite
                    </Button>
                    {copyStatus ? <div className="text-center text-xs text-muted-foreground">{copyStatus}</div> : null}
                  </div>
                ) : null}
              </>
            ) : null}
          </section>

          <section className="hr-pair-options-column">
            <div className="hr-pair-panel">
              <div className="hr-pair-panel-title">What this adds</div>
              <div className="hr-grant-list">
                {['Terminal', 'Bridge', 'Media', 'Voice'].map((label) => (
                  <Badge key={label} variant="outline" className="text-xs">{label}</Badge>
                ))}
              </div>
              <p className="text-xs text-muted-foreground">
                Extends an existing Hermes Dashboard connection with Hermes-Relay capabilities.
              </p>
            </div>

            <div className="hr-pair-panel">
              <div className="hr-pair-connection-header">
                <div>
                  <div className="hr-pair-panel-title">Connection</div>
                  <div className="text-xs text-muted-foreground">Best available route</div>
                </div>
                <select
                  id="pair-mode"
                  aria-label="Connection mode"
                  className="h-9 rounded-md border border-border bg-background px-2 text-sm"
                  value={settings.mode}
                  onChange={(event) => updateSetting({ mode: event.target.value })}
                >
                  {MODES.map((mode) => (
                    <option key={mode.value} value={mode.value}>{mode.label}</option>
                  ))}
                </select>
              </div>
              {receipt.routes.length > 0 ? (
                <div className="hr-endpoint-list">
                  {receipt.routes.map((route) => (
                    <div key={`${route.role}-${route.priority}`} className="hr-endpoint-route">
                      <div className="hr-endpoint-row">
                        <Badge variant="outline" className="text-xs capitalize">{route.role}</Badge>
                        <span className="text-xs text-muted-foreground hr-endpoint-address">
                          {route.protection}
                        </span>
                        <span className="text-xs text-muted-foreground">p{route.priority}</span>
                      </div>
                      <div className="hr-endpoint-surfaces">
                        {route.surfaces.map((surface) => {
                          const probe = probeByKey.get(pairingProbeKey({
                            role: route.role,
                            priority: route.priority,
                            surface: surface.surface,
                            url: surface.url,
                          }));
                          const probeText = probe
                            ? probe.reachable
                              ? `Ready${probe.latency_ms != null ? ` · ${probe.latency_ms}ms` : ""}`
                              : probe.status != null
                                ? `HTTP ${probe.status}`
                                : "Unreachable"
                            : probeState.status === "loading"
                              ? "Checking…"
                              : "Not checked";
                          return (
                            <div key={`${surface.surface}-${surface.url}`} className="hr-endpoint-surface">
                              <span className="text-xs font-medium">{surface.label}</span>
                              <span className="font-mono text-xs hr-endpoint-address" title={surface.url}>
                                {surface.url}
                              </span>
                              <Badge variant="outline" className="text-xs">{probeText}</Badge>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-xs text-muted-foreground">Automatic route selection will use server configuration.</div>
              )}
              <p className="text-xs text-muted-foreground">
                Tailscale is preferred because it keeps the route private and ACL-controlled. A raw
                HTTP/WS tailnet URL has no application TLS, but Tailscale still encrypts device-to-device
                traffic. Public routes must use HTTPS/WSS.
              </p>
              {probeState.status === "error" ? (
                <div className="text-xs text-destructive">Surface probes failed: {probeState.error}</div>
              ) : null}
            </div>

            <details className="hr-pair-advanced" open={advancedOpen}>
              <summary onClick={(event) => { event.preventDefault(); setAdvancedOpen((value) => !value); }}>
                Advanced connection options
              </summary>
              {advancedOpen ? (
                <div className="hr-pair-advanced-content space-y-3">
                  <div className="space-y-1">
                    <Label htmlFor="pair-prefer">Prefer role</Label>
                    <select
                      id="pair-prefer"
                      className="h-9 w-full rounded-md border border-border bg-background px-2 text-sm"
                      value={settings.prefer}
                      onChange={(event) => updateSetting({ prefer: event.target.value })}
                    >
                      {PREFER_ROLES.map((role) => (
                        <option key={role.value} value={role.value}>{role.label}</option>
                      ))}
                    </select>
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="pair-host">API host override</Label>
                    <Input
                      id="pair-host"
                      value={settings.host}
                      placeholder="Use server configuration"
                      onChange={(event) => updateSetting({ host: event.target.value }, false)}
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <div className="space-y-1">
                      <Label htmlFor="pair-port">API port</Label>
                      <Input
                        id="pair-port"
                        type="number"
                        min="1"
                        max="65535"
                        value={settings.port}
                        onChange={(event) => updateSetting({ port: parseInt(event.target.value, 10) || 8642 }, false)}
                      />
                    </div>
                    <div className="space-y-1">
                      <Label htmlFor="pair-tls">Scheme</Label>
                      <label className="hr-pair-checkbox text-sm" htmlFor="pair-tls">
                        <input
                          id="pair-tls"
                          type="checkbox"
                          className="h-4 w-4"
                          checked={!!settings.tls}
                          onChange={(event) => updateSetting({ tls: event.target.checked }, false)}
                        />
                        https://
                      </label>
                    </div>
                  </div>
                  {proxyWarning ? (
                    <div className="rounded-md border border-amber-500/50 bg-amber-500/10 p-2 text-xs">
                      This host appears proxy-fronted and may reject API requests from Hermes-Relay Android.
                    </div>
                  ) : null}
                  <div className="flex flex-wrap gap-2">
                    <Button size="sm" onClick={regenerate}>Apply and mint</Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => updateSetting({ host: "", port: 8642, tls: false })}
                    >
                      Clear override
                    </Button>
                  </div>
                </div>
              ) : null}
            </details>
          </section>
        </div>

        <DialogFooter>
          <Button size="sm" variant="outline" onClick={regenerate} disabled={state.status === "loading"}>
            New code
          </Button>
          <Button size="sm" onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
