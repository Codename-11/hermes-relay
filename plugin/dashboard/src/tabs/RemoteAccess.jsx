const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback, useMemo, useRef } = SDK.hooks;

import QRCode from "qrcode";

import {
  getRemoteAccessStatus,
  enableTailscale,
  disableTailscale,
  putPublicUrl,
  probeEndpoints,
  mintPairingWithMode,
} from "../lib/api.js";
import { relativeTime } from "../lib/formatters.js";
import {
  Alert,
  AlertTitle,
  AlertDescription,
  CardDescription,
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "../lib/ui-shims.jsx";

const {
  Card,
  CardHeader,
  CardTitle,
  CardContent,
  Button,
  Badge,
  Input,
  Label,
} = SDK.components;

// Dots are rendered with Tailwind utility classes — the dashboard ships
// a shadcn/Tailwind build so these resolve at runtime. Fallback tones
// track the Alert variants in ui-shims.jsx.
function Dot({ tone = "muted", title }) {
  const map = {
    ok: "bg-emerald-500",
    warn: "bg-amber-500",
    bad: "bg-destructive",
    muted: "bg-muted-foreground/40",
  };
  const cls = map[tone] || map.muted;
  return (
    <span
      title={title || ""}
      className={`inline-block h-2.5 w-2.5 rounded-full ${cls}`}
    />
  );
}

function toneForReachable(reachable) {
  if (reachable === true) return "ok";
  if (reachable === false) return "bad";
  return "muted";
}

function candidateUrlsFrom(endpoints) {
  // Build probe URLs from a ``build_endpoint_candidates`` list. We
  // prefer the relay URL (with the /health suffix elided — the backend
  // adds it) since that's what the phone actually opens over WSS.
  if (!Array.isArray(endpoints)) return [];
  const seen = new Set();
  const out = [];
  for (const ep of endpoints) {
    const api = (ep && ep.api) || {};
    if (!api.host) continue;
    const scheme = api.tls ? "https" : "http";
    const host = api.host;
    const port = Number(api.port);
    const portPart =
      (api.tls && port === 443) || (!api.tls && port === 80) || !port
        ? ""
        : `:${port}`;
    const url = `${scheme}://${host}${portPart}`;
    if (!seen.has(url)) {
      seen.add(url);
      out.push({ role: ep.role || "?", url, priority: ep.priority });
    }
  }
  return out;
}

function TailscaleCard({ status, onEnable, onDisable, port, busy, resultMessage }) {
  const available = !!(status && status.available);
  const servePorts = (status && status.serve_ports) || [];
  const serving = servePorts.includes(port);
  const hostname = (status && status.hostname) || null;
  const ip = (status && status.tailscale_ip) || null;
  const reason = status && status.reason;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Tailscale</CardTitle>
        <CardDescription>
          Front the relay's loopback port with a Tailscale-managed hostname
          (ACL-gated, TLS-terminated). Requires the <code className="font-mono">tailscale</code> CLI.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <div className="flex items-center gap-2">
            <Dot tone={available ? "ok" : "muted"} title={available ? "CLI present" : "CLI absent"} />
            <span>CLI: {available ? "installed" : "not installed"}</span>
          </div>
          <div className="flex items-center gap-2">
            <Dot tone={serving ? "ok" : available ? "warn" : "muted"} />
            <span>serve :{port}: {serving ? "active" : "off"}</span>
          </div>
        </div>

        {hostname ? (
          <div className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs space-y-1">
            <div>
              <span className="uppercase tracking-wider text-muted-foreground">Hostname</span>{" "}
              <span className="font-mono">{hostname}</span>
            </div>
            {ip ? (
              <div>
                <span className="uppercase tracking-wider text-muted-foreground">Tailscale IP</span>{" "}
                <span className="font-mono">{ip}</span>
              </div>
            ) : null}
            {servePorts.length > 0 ? (
              <div className="flex flex-wrap gap-1 items-center">
                <span className="uppercase tracking-wider text-muted-foreground">Serving</span>
                {servePorts.map((p) => (
                  <Badge key={p} variant="outline" className="text-xs">{p}</Badge>
                ))}
              </div>
            ) : null}
          </div>
        ) : reason ? (
          <div className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs text-muted-foreground">
            {reason}
          </div>
        ) : null}

        <div className="flex gap-2">
          <Button size="sm" disabled={busy || !available || serving} onClick={onEnable}>
            {busy === "enable" ? "Enabling…" : "Enable"}
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={busy || !available || !serving}
            onClick={onDisable}
          >
            {busy === "disable" ? "Disabling…" : "Disable"}
          </Button>
        </div>

        {resultMessage ? (
          <div className="text-xs text-muted-foreground whitespace-pre-wrap">
            {resultMessage}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function PublicUrlCard({ initialUrl, onSaved }) {
  const [draft, setDraft] = useState(initialUrl || "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [lastSavedAt, setLastSavedAt] = useState(null);
  const [probeState, setProbeState] = useState({ reachable: null, status: null, latency_ms: null, error: null, at: null });
  const [probing, setProbing] = useState(false);

  useEffect(() => {
    setDraft(initialUrl || "");
  }, [initialUrl]);

  const save = useCallback(async () => {
    setError(null);
    setSaving(true);
    try {
      const trimmed = draft.trim();
      const body = trimmed === "" ? null : trimmed;
      const data = await putPublicUrl(body);
      setLastSavedAt(Date.now());
      if (onSaved) onSaved(data && data.url ? data.url : null);
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }, [draft, onSaved]);

  const probe = useCallback(async () => {
    const trimmed = draft.trim();
    if (!trimmed) return;
    setProbing(true);
    try {
      const data = await probeEndpoints([trimmed]);
      const r = (data && Array.isArray(data.results) && data.results[0]) || {};
      setProbeState({
        reachable: r.reachable == null ? null : !!r.reachable,
        status: r.status ?? null,
        latency_ms: r.latency_ms ?? null,
        error: r.error || null,
        at: Date.now(),
      });
    } catch (err) {
      setProbeState({
        reachable: false,
        status: null,
        latency_ms: null,
        error: err && err.message ? err.message : String(err),
        at: Date.now(),
      });
    } finally {
      setProbing(false);
    }
  }, [draft]);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Public URL</CardTitle>
        <CardDescription>
          Reverse-proxy or tunnel hostname (e.g. Cloudflare Tunnel). Embedded into
          the next pairing QR as a <code className="font-mono">role=public</code> endpoint.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1">
          <Label htmlFor="public-url">URL</Label>
          <Input
            id="public-url"
            value={draft}
            placeholder="https://relay.example.com"
            onChange={(e) => setDraft(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            Leave empty to clear. Must start with <code className="font-mono">http://</code> or{" "}
            <code className="font-mono">https://</code>.
          </p>
        </div>

        {error ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-xs text-destructive">
            {error}
          </div>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={save} disabled={saving}>
            {saving ? "Saving…" : "Save"}
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={probe}
            disabled={probing || !draft.trim()}
          >
            {probing ? "Probing…" : "Probe /health"}
          </Button>
          {lastSavedAt ? (
            <span className="text-xs text-muted-foreground">
              Saved {relativeTime(lastSavedAt)}
            </span>
          ) : null}
        </div>

        {probeState.at != null ? (
          <div className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/20 px-3 py-2 text-xs">
            <Dot tone={toneForReachable(probeState.reachable)} />
            <span>
              {probeState.reachable === true
                ? "reachable"
                : probeState.reachable === false
                ? "unreachable"
                : "unknown"}
            </span>
            {probeState.status != null ? (
              <Badge variant="outline" className="text-xs">HTTP {probeState.status}</Badge>
            ) : null}
            {probeState.latency_ms != null ? (
              <span className="text-muted-foreground">{probeState.latency_ms}ms</span>
            ) : null}
            <span className="text-muted-foreground ml-auto">
              checked {relativeTime(probeState.at)}
            </span>
            {probeState.error ? (
              <div className="basis-full text-destructive">{probeState.error}</div>
            ) : null}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function EndpointPreviewCard({ endpoints, reachability, onProbe, onRegenerate, busy, qrPayload }) {
  const canvasRef = useRef(null);

  useEffect(() => {
    if (!qrPayload || !canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, qrPayload, {
      width: 260,
      margin: 2,
      errorCorrectionLevel: "M",
    }).catch(() => { /* non-fatal */ });
  }, [qrPayload]);

  const reachabilityByUrl = useMemo(() => {
    const m = new Map();
    (reachability || []).forEach((r) => { m.set(r.url, r); });
    return m;
  }, [reachability]);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Endpoint preview</CardTitle>
        <CardDescription>
          Candidates the next <code className="font-mono">mode=auto</code> QR would
          embed. Lower priority = higher preference on the phone.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {endpoints.length === 0 ? (
          <div className="text-sm text-muted-foreground">
            No candidates detected. Enable Tailscale and/or pin a public URL above.
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Role</TableHead>
                <TableHead>URL</TableHead>
                <TableHead>Priority</TableHead>
                <TableHead>Reachable</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {endpoints.map((ep) => {
                const r = reachabilityByUrl.get(ep.url);
                return (
                  <TableRow key={ep.url}>
                    <TableCell>
                      <Badge variant="outline" className="text-xs capitalize">
                        {ep.role}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{ep.url}</TableCell>
                    <TableCell className="text-xs">{ep.priority ?? "—"}</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        <Dot tone={toneForReachable(r ? r.reachable : null)} />
                        <span className="text-xs">
                          {r && r.reachable === true
                            ? `${r.status} · ${r.latency_ms}ms`
                            : r && r.reachable === false
                            ? r.error || `HTTP ${r.status ?? "?"}`
                            : "—"}
                        </span>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}

        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" onClick={onProbe} disabled={busy || endpoints.length === 0}>
            {busy === "probe" ? "Probing…" : "Probe all"}
          </Button>
          <Button size="sm" onClick={onRegenerate} disabled={busy}>
            {busy === "mint" ? "Regenerating…" : "Regenerate QR"}
          </Button>
        </div>

        {qrPayload ? (
          <div className="flex flex-col items-center gap-2 rounded-md border border-border bg-white p-3">
            <canvas ref={canvasRef} className="block" />
            <p className="text-xs text-muted-foreground">
              Scan from the Hermes-Relay Android app. Fresh payload, signed with the
              host's QR secret — embedding one-shot pairing code.
            </p>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

export default function RemoteAccess({ autoRefresh }) {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(null);
  const [helperMessage, setHelperMessage] = useState(null);
  const [mintResult, setMintResult] = useState(null);
  const [reachability, setReachability] = useState([]);
  const [publicUrl, setPublicUrl] = useState(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const data = await getRemoteAccessStatus();
      setStatus(data || {});
      const pub = (data && data.public && data.public.url) || null;
      setPublicUrl(pub);
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!autoRefresh) return undefined;
    const id = setInterval(load, 15000);
    return () => clearInterval(id);
  }, [autoRefresh, load]);

  const onEnable = useCallback(async () => {
    setBusy("enable");
    setHelperMessage(null);
    try {
      const res = await enableTailscale();
      setHelperMessage(
        `${res && res.ok ? "Enabled" : "Failed"}: ${res && res.message ? res.message : "(no message)"}`
      );
      await load();
    } catch (err) {
      setHelperMessage(`Error: ${err && err.message ? err.message : err}`);
    } finally {
      setBusy(null);
    }
  }, [load]);

  const onDisable = useCallback(async () => {
    setBusy("disable");
    setHelperMessage(null);
    try {
      const res = await disableTailscale();
      setHelperMessage(
        `${res && res.ok ? "Disabled" : "Failed"}: ${res && res.message ? res.message : "(no message)"}`
      );
      await load();
    } catch (err) {
      setHelperMessage(`Error: ${err && err.message ? err.message : err}`);
    } finally {
      setBusy(null);
    }
  }, [load]);

  // Regenerate QR — ask the backend for a mode=auto payload. We also
  // surface the preview by inspecting the endpoints echoed in the
  // response shape (``qr_payload`` is the string to scan; we parse it
  // to render the preview table).
  const onRegenerate = useCallback(async () => {
    setBusy("mint");
    setMintResult(null);
    try {
      const data = await mintPairingWithMode({ mode: "auto" });
      setMintResult(data || null);
    } catch (err) {
      setMintResult({ error: err && err.message ? err.message : String(err) });
    } finally {
      setBusy(null);
    }
  }, []);

  const previewEndpoints = useMemo(() => {
    if (!mintResult || !mintResult.qr_payload) return [];
    try {
      const parsed = JSON.parse(mintResult.qr_payload);
      return candidateUrlsFrom(parsed.endpoints);
    } catch (_err) {
      return [];
    }
  }, [mintResult]);

  const onProbeAll = useCallback(async () => {
    if (previewEndpoints.length === 0) return;
    setBusy("probe");
    try {
      const data = await probeEndpoints(previewEndpoints.map((e) => e.url));
      setReachability((data && data.results) || []);
    } catch (err) {
      setReachability(previewEndpoints.map((e) => ({
        url: e.url, reachable: false, status: null, latency_ms: null,
        error: err && err.message ? err.message : String(err),
      })));
    } finally {
      setBusy(null);
    }
  }, [previewEndpoints]);

  if (loading) {
    return <div className="text-sm text-muted-foreground">Loading remote access…</div>;
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Dashboard backend unreachable</AlertTitle>
        <AlertDescription>
          <pre className="whitespace-pre-wrap text-xs">{error}</pre>
          {!autoRefresh ? (
            <Button className="mt-2" size="sm" variant="outline" onClick={load}>
              Retry
            </Button>
          ) : null}
        </AlertDescription>
      </Alert>
    );
  }

  const ts = (status && status.tailscale) || {};
  const upstream = !!(status && status.upstream_canonical);

  return (
    <div className="space-y-4">
      {upstream ? (
        <Alert>
          <AlertTitle>Upstream helper detected</AlertTitle>
          <AlertDescription>
            Upstream hermes-agent now ships <code className="font-mono">hermes gateway run --tailscale</code>.
            The helper in this plugin is now redundant and will be removed in a future release.
          </AlertDescription>
        </Alert>
      ) : null}

      <TailscaleCard
        status={ts}
        port={8767}
        onEnable={onEnable}
        onDisable={onDisable}
        busy={busy === "enable" || busy === "disable" ? busy : null}
        resultMessage={helperMessage}
      />

      <PublicUrlCard
        initialUrl={publicUrl}
        onSaved={(url) => {
          setPublicUrl(url);
          load();
        }}
      />

      <EndpointPreviewCard
        endpoints={previewEndpoints}
        reachability={reachability}
        onProbe={onProbeAll}
        onRegenerate={onRegenerate}
        busy={busy === "mint" || busy === "probe" ? busy : null}
        qrPayload={mintResult && mintResult.qr_payload ? mintResult.qr_payload : null}
      />

      {mintResult && mintResult.error ? (
        <Alert variant="destructive">
          <AlertTitle>QR regeneration failed</AlertTitle>
          <AlertDescription>
            <pre className="whitespace-pre-wrap text-xs">{mintResult.error}</pre>
          </AlertDescription>
        </Alert>
      ) : null}

      {mintResult && mintResult.code ? (
        <Card>
          <CardHeader>
            <CardTitle>Pairing code</CardTitle>
            <CardDescription>
              One-shot code — expires in 10 minutes (or sooner, per session TTL).
            </CardDescription>
          </CardHeader>
          <CardContent className="flex items-center justify-between gap-2">
            <div className="font-mono text-2xl tracking-widest">{mintResult.code}</div>
            {mintResult.expires_at ? (
              <Badge variant="outline">
                expires {relativeTime(mintResult.expires_at)}
              </Badge>
            ) : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}
