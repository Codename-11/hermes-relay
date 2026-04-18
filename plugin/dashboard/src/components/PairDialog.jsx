const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useRef, useCallback, useMemo } = SDK.hooks;

import QRCode from "qrcode";
import { mintPairing } from "../lib/api.js";

const { Card, CardHeader, CardTitle, CardContent, Button, Badge, Input, Label } = SDK.components;

// localStorage keys — per-browser, not per-user. Sensible defaults on first
// open; stick with whatever the operator last used.
const LS_HOST = "hermes-relay-pair-host";
const LS_PORT = "hermes-relay-pair-port";
const LS_TLS  = "hermes-relay-pair-tls";

function readString(key, fallback) {
  try { return window.localStorage.getItem(key) ?? fallback; }
  catch (_e) { return fallback; }
}
function writeString(key, value) {
  try { window.localStorage.setItem(key, value); } catch (_e) { /* best-effort */ }
}

/**
 * Infer the most likely defaults from the dashboard's own URL.
 *
 * - host: the dashboard hostname (the operator's "currently visible"
 *   address). Works for plain LAN (172.16.x.y) and Traefik-fronted
 *   subdomains (hermes.axiom-labs.dev). Operator can override if the
 *   relay lives at a different subdomain or IP than the dashboard.
 * - port: 443 if the dashboard is on https (assume Traefik-terminated
 *   TLS → relay also on 443), else the relay's default 8767.
 * - tls: true when the dashboard is https.
 */
function inferDefaults() {
  if (typeof window === "undefined") return { host: "", port: 8767, tls: false };
  const tls = window.location.protocol === "https:";
  return {
    host: window.location.hostname || "",
    port: tls ? 443 : 8767,
    tls,
  };
}

function loadSettings() {
  const d = inferDefaults();
  const saved = readString(LS_HOST, null);
  if (!saved) return d;  // first open — use inferred
  const rawPort = parseInt(readString(LS_PORT, ""), 10);
  return {
    host: saved || d.host,
    port: Number.isFinite(rawPort) && rawPort > 0 && rawPort <= 65535 ? rawPort : d.port,
    tls: readString(LS_TLS, "") === "true",
  };
}
function saveSettings({ host, port, tls }) {
  writeString(LS_HOST, host || "");
  writeString(LS_PORT, String(port));
  writeString(LS_TLS, tls ? "true" : "false");
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

export default function PairDialog({ open, onClose }) {
  const [settings, setSettings] = useState(loadSettings);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(settings);
  const [state, setState] = useState({ status: "idle" });
  const canvasRef = useRef(null);
  const countdown = useCountdown(state.data ? state.data.expires_at : null);

  const scheme = settings.tls ? "wss" : "ws";
  const portStr = (settings.tls && settings.port === 443) || (!settings.tls && settings.port === 80)
    ? ""
    : `:${settings.port}`;
  const previewUrl = `${scheme}://${settings.host || "…"}${portStr}`;

  const mint = useCallback(async (cfg) => {
    const use = cfg || settings;
    setState({ status: "loading" });
    try {
      const data = await mintPairing({
        host: use.host,
        port: use.port,
        tls: use.tls,
        transport_hint: use.tls ? "wss" : "ws",
      });
      setState({ status: "ok", data });
    } catch (err) {
      setState({ status: "error", error: err && err.message ? err.message : String(err) });
    }
  }, [settings]);

  useEffect(() => {
    if (open && state.status === "idle" && settings.host) mint();
    // If host is empty on open, force edit mode so the operator fills it in.
    if (open && !settings.host && !editing) setEditing(true);
  }, [open, state.status, settings.host, editing, mint]);

  useEffect(() => {
    if (state.status !== "ok" || !canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, state.data.qr_payload, {
      width: 280, margin: 2, errorCorrectionLevel: "M",
    }).catch(() => { /* canvas failure non-fatal */ });
  }, [state.status, state.data]);

  const applyDraft = useCallback(() => {
    const host = (draft.host || "").trim();
    const port = parseInt(draft.port, 10);
    if (!host) { window.alert("Host is required."); return; }
    if (!Number.isFinite(port) || port <= 0 || port > 65535) { window.alert("Port must be 1-65535."); return; }
    const next = { host, port, tls: !!draft.tls };
    setSettings(next);
    saveSettings(next);
    setEditing(false);
    setState({ status: "idle" });
    mint(next);
  }, [draft, mint]);

  const toggleTls = useCallback((checked) => {
    setDraft((prev) => {
      const next = { ...prev, tls: !!checked };
      // Flip port to the matching default if the operator hasn't customized.
      if (checked && (prev.port === 8767 || prev.port === 80)) next.port = 443;
      if (!checked && (prev.port === 443)) next.port = 8767;
      return next;
    });
  }, []);

  const resetToInferred = useCallback(() => {
    const d = inferDefaults();
    setDraft(d);
  }, []);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-sm p-4">
      <Card className="w-full max-w-md">
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
          {state.status === "loading" && !editing && (
            <div className="text-sm text-muted-foreground">Minting code…</div>
          )}
          {state.status === "error" && !editing && (
            <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              <div className="font-medium mb-1">Minting failed</div>
              <div className="break-words">{state.error}</div>
              <div className="mt-2 flex gap-2">
                <Button size="sm" variant="outline" onClick={() => mint()}>Retry</Button>
                <Button size="sm" variant="ghost" onClick={() => { setDraft(settings); setEditing(true); }}>
                  Edit pair URL
                </Button>
              </div>
            </div>
          )}
          {state.status === "ok" && !editing && (
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
              <div className="flex items-center justify-between rounded-md border border-border bg-muted/20 px-3 py-2 text-xs">
                <div className="flex flex-col">
                  <span className="uppercase tracking-wider text-muted-foreground">Pair URL</span>
                  <span className="font-mono">{previewUrl}</span>
                </div>
                <Button size="sm" variant="ghost" onClick={() => { setDraft(settings); setEditing(true); }}>
                  Edit
                </Button>
              </div>
              <div className="flex gap-2 pt-1">
                <Button size="sm" variant="outline" onClick={() => { setState({ status: "idle" }); mint(); }}>
                  New code
                </Button>
                <Button size="sm" onClick={onClose}>Done</Button>
              </div>
            </>
          )}
          {editing && (
            <div className="space-y-3">
              <p className="text-xs text-muted-foreground">
                Override what the phone will connect to — use your relay's LAN IP, a reverse-proxy
                hostname (e.g. <code className="font-mono">relay.example.com</code> via Traefik),
                or any other reachable endpoint. Saved per-browser.
              </p>
              <div className="space-y-1">
                <Label htmlFor="pair-host">Host</Label>
                <Input
                  id="pair-host"
                  value={draft.host}
                  placeholder="172.16.24.250 or relay.example.com"
                  onChange={(e) => setDraft((d) => ({ ...d, host: e.target.value }))}
                />
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1">
                  <Label htmlFor="pair-port">Port</Label>
                  <Input
                    id="pair-port"
                    type="number"
                    min="1"
                    max="65535"
                    value={draft.port}
                    onChange={(e) => setDraft((d) => ({ ...d, port: e.target.value }))}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="pair-tls">Scheme</Label>
                  <div className="flex items-center gap-2 pt-1">
                    <input
                      id="pair-tls"
                      type="checkbox"
                      className="h-4 w-4"
                      checked={!!draft.tls}
                      onChange={(e) => toggleTls(e.target.checked)}
                    />
                    <Label htmlFor="pair-tls" className="text-sm font-normal">
                      Use wss:// (TLS)
                    </Label>
                  </div>
                </div>
              </div>
              <div className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs font-mono">
                {draft.tls ? "wss" : "ws"}://{(draft.host || "…")}
                {((draft.tls && draft.port === 443) || (!draft.tls && draft.port === 80))
                  ? "" : `:${draft.port}`}
              </div>
              <div className="flex flex-wrap gap-2 pt-1">
                <Button size="sm" onClick={applyDraft}>Save &amp; regenerate</Button>
                <Button size="sm" variant="outline" onClick={() => { setEditing(false); setDraft(settings); }}>
                  Cancel
                </Button>
                <Button size="sm" variant="ghost" onClick={resetToInferred}>
                  Reset to dashboard URL
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
