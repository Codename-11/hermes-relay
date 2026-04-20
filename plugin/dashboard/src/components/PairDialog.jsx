const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useRef, useCallback, useMemo } = SDK.hooks;

import QRCode from "qrcode";
import { mintPairingWithMode } from "../lib/api.js";

const { Card, CardHeader, CardTitle, CardContent, Button, Badge, Input, Label } = SDK.components;

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
  { value: "public",    label: "Public URL only" },
];

const PREFER_ROLES = [
  { value: "",          label: "Natural order (LAN → Tailscale → Public)" },
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

// Derive candidate URLs for the small preview list under the QR, from the
// `endpoints` array on the minted payload. The actual rich preview lives
// on the Remote Access tab; we just show "3 endpoints: LAN, Tailscale,
// Public" here as a compact receipt.
function summarizeEndpoints(qrPayload) {
  if (!qrPayload) return null;
  try {
    const parsed = JSON.parse(qrPayload);
    const eps = parsed.endpoints;
    if (!Array.isArray(eps) || eps.length === 0) return null;
    return eps.map((e, i) => ({
      role: e.role || "?",
      priority: e.priority != null ? e.priority : i,
      api: e.api || {},
    }));
  } catch (_e) {
    return null;
  }
}

export default function PairDialog({ open, onClose }) {
  const [settings, setSettings] = useState(loadSettings);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [state, setState] = useState({ status: "idle" });
  const canvasRef = useRef(null);
  const countdown = useCountdown(state.data ? state.data.expires_at : null);

  const endpoints = useMemo(
    () => summarizeEndpoints(state.data ? state.data.qr_payload : null),
    [state.data],
  );

  const mint = useCallback(async (s) => {
    const use = s || settings;
    setState({ status: "loading" });
    try {
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
        ...overrides,
      });
      setState({ status: "ok", data });
    } catch (err) {
      setState({ status: "error", error: err && err.message ? err.message : String(err) });
    }
  }, [settings]);

  useEffect(() => {
    if (open && state.status === "idle") mint();
  }, [open, state.status, mint]);

  useEffect(() => {
    if (state.status !== "ok" || !canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, state.data.qr_payload, {
      width: 280, margin: 2, errorCorrectionLevel: "M",
    }).catch(() => { /* canvas failure non-fatal */ });
  }, [state.status, state.data]);

  const updateSetting = useCallback((patch) => {
    setSettings((prev) => {
      const next = { ...prev, ...patch };
      saveSettings(next);
      return next;
    });
    // Re-mint with the new settings. Debouncing isn't worth it — the
    // dropdowns only fire on user action, not typing.
    setState({ status: "idle" });
  }, []);

  const regenerate = useCallback(() => {
    setState({ status: "idle" });
    mint();
  }, [mint]);

  if (!open) return null;

  const proxyWarning = advancedOpen && settings.host && looksProxyFronted(settings.host);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-sm p-4">
      <Card className="w-full max-w-md max-h-[90vh] overflow-y-auto">
        <CardHeader className="flex flex-row items-start justify-between gap-2 space-y-0">
          <div>
            <CardTitle>Pair new device</CardTitle>
            <p className="text-sm text-muted-foreground mt-1">
              Scan from the Hermes-Relay Android app to pair.
            </p>
          </div>
          <Button variant="ghost" size="sm" onClick={onClose}>Close</Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {/* Mode + prefer controls — always visible, these are the
              primary inputs now. Multi-endpoint candidates get derived
              server-side from Tailscale + pinned Public URL. */}
          <div className="space-y-2">
            <div className="space-y-1">
              <Label htmlFor="pair-mode">Mode</Label>
              <select
                id="pair-mode"
                className="h-9 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={settings.mode}
                onChange={(e) => updateSetting({ mode: e.target.value })}
              >
                {MODES.map((m) => (
                  <option key={m.value} value={m.value}>{m.label}</option>
                ))}
              </select>
              <p className="text-xs text-muted-foreground">
                <strong>Auto</strong> embeds every reachable endpoint so the phone
                switches as networks change. Configure Tailscale + Public URL on
                the <em>Remote Access</em> tab.
              </p>
            </div>
            <div className="space-y-1">
              <Label htmlFor="pair-prefer">Prefer role</Label>
              <select
                id="pair-prefer"
                className="h-9 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={settings.prefer}
                onChange={(e) => updateSetting({ prefer: e.target.value })}
              >
                {PREFER_ROLES.map((p) => (
                  <option key={p.value} value={p.value}>{p.label}</option>
                ))}
              </select>
            </div>
          </div>

          {state.status === "loading" && (
            <div className="text-sm text-muted-foreground">Minting code…</div>
          )}
          {state.status === "error" && (
            <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              <div className="font-medium mb-1">Minting failed</div>
              <div className="break-words">{state.error}</div>
              <div className="mt-2 flex gap-2">
                <Button size="sm" variant="outline" onClick={regenerate}>Retry</Button>
              </div>
            </div>
          )}
          {state.status === "ok" && (
            <>
              <div className="flex justify-center rounded-md border border-border bg-white p-3">
                <canvas ref={canvasRef} className="block" />
              </div>
              <div className="flex items-center justify-between gap-2">
                <div>
                  <div className="text-xs uppercase tracking-wider text-muted-foreground">Code</div>
                  <div className="font-mono text-2xl tracking-widest">{state.data.code}</div>
                </div>
                <div className="text-right">
                  <div className="text-xs uppercase tracking-wider text-muted-foreground">Expires in</div>
                  <Badge variant={countdown === "expired" ? "destructive" : "outline"}>
                    {countdown || "—"}
                  </Badge>
                </div>
              </div>
              {/* Compact endpoint receipt — full preview + probes live on
                  the Remote Access tab. */}
              {endpoints && endpoints.length > 0 ? (
                <div className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs space-y-1">
                  <div className="uppercase tracking-wider text-muted-foreground">
                    Endpoints in this QR ({endpoints.length})
                  </div>
                  {endpoints.map((ep) => (
                    <div key={`${ep.role}-${ep.priority}`} className="flex items-center gap-2">
                      <Badge variant="outline" className="text-xs capitalize">{ep.role}</Badge>
                      <span className="font-mono">
                        {ep.api.host}{ep.api.port ? `:${ep.api.port}` : ""}
                      </span>
                      <span className="text-muted-foreground ml-auto">p{ep.priority}</span>
                    </div>
                  ))}
                </div>
              ) : null}
              <div className="flex gap-2 pt-1">
                <Button size="sm" variant="outline" onClick={regenerate}>
                  New code
                </Button>
                <Button size="sm" onClick={onClose}>Done</Button>
              </div>
            </>
          )}

          {/* Advanced — API server override. Most operators never need
              this; it's kept for edge cases. Warn when the host looks
              proxy-fronted (Authelia etc.) because the phone has no way
              to present that auth material. */}
          <div className="border-t border-border pt-3">
            <button
              type="button"
              onClick={() => setAdvancedOpen((v) => !v)}
              className="text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              {advancedOpen ? "▾ Hide advanced" : "▸ Advanced · API-server override"}
            </button>
            {advancedOpen && (
              <div className="mt-3 space-y-3">
                <p className="text-xs text-muted-foreground">
                  Override the API-server host embedded in the QR (defaults to the
                  relay's configured API host). Relay URL is auto-derived server-side —
                  edit Tailscale / Public URL on the <em>Remote Access</em> tab instead.
                </p>
                {proxyWarning ? (
                  <div className="rounded-md border border-amber-500/50 bg-amber-500/10 p-2 text-xs">
                    <strong>Heads-up:</strong> <span className="font-mono">{settings.host}</span> looks
                    like a reverse-proxy / forward-auth host. If it's fronted by
                    Authelia, Cloudflare Access, or similar, the phone will fail
                    to authenticate against the API even though the relay WSS
                    pairs fine. Leave this blank and let <code className="font-mono">mode=auto</code> pick.
                  </div>
                ) : null}
                <div className="space-y-1">
                  <Label htmlFor="pair-host">API host (optional)</Label>
                  <Input
                    id="pair-host"
                    value={settings.host}
                    placeholder="leave blank to use server config"
                    onChange={(e) => updateSetting({ host: e.target.value })}
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
                      onChange={(e) => updateSetting({ port: parseInt(e.target.value, 10) || 8642 })}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="pair-tls">Scheme</Label>
                    <div className="flex items-center gap-2 pt-1">
                      <input
                        id="pair-tls"
                        type="checkbox"
                        className="h-4 w-4"
                        checked={!!settings.tls}
                        onChange={(e) => updateSetting({ tls: e.target.checked })}
                      />
                      <Label htmlFor="pair-tls" className="text-sm font-normal">
                        https://
                      </Label>
                    </div>
                  </div>
                </div>
                <Button size="sm" variant="ghost" onClick={() => updateSetting({ host: "", port: 8642, tls: false })}>
                  Clear override
                </Button>
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
