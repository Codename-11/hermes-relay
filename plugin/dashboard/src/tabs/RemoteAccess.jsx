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
import { canonicalDashboardOrigin } from "../lib/mobile-setup.mjs";
import {
  classifyPublicRouteInput,
  pairingEndpointReceipt,
  pairingProbeKey,
  pairingSurfaceProbes,
} from "../lib/pairing-receipt.mjs";
import {
  Alert,
  AlertTitle,
  AlertDescription,
  CardDescription,
  Button,
  Badge,
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

function TailscaleCard({ status, onEnable, onDisable, busy, resultMessage }) {
  const available = !!(status && status.available);
  const servePorts = (status && status.serve_ports) || [];
  const services = (status && status.serve_services) || {};
  const dashboardService = services.dashboard || {};
  const apiService = services.api || {};
  const legacyRelayService = services.legacy_relay || {};
  const dashboardServing = dashboardService.active === true;
  const apiServing = apiService.active === true;
  const legacyRelayServing = legacyRelayService.active === true;
  const serving = dashboardServing;
  const hostname = (status && status.hostname) || null;
  const ip = (status && status.tailscale_ip) || null;
  const reason = status && status.reason;
  const listenerLabel = (service) => {
    const ports = Array.isArray(service && service.listen_ports)
      ? service.listen_ports.filter((port) => Number.isInteger(port))
      : [];
    return ports.length > 0 ? `tailnet ${ports.map((port) => `:${port}`).join(", ")}` : "tailnet";
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Tailscale Serve</CardTitle>
          <Badge className="text-xs">Recommended</Badge>
          <Badge variant="outline" className="text-xs">Remote access</Badge>
        </div>
        <CardDescription>
          The easiest supported way to reach this self-hosted Hermes installation
          away from home. Tailscale supplies private routing, WireGuard encryption,
          and ACLs. Raw tailnet HTTP/WS has no application TLS, but traffic remains
          encrypted between tailnet devices; Hermes authentication still applies.
          Recommended setup maps tailnet HTTPS :443 to local Dashboard :9119.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <div className="flex items-center gap-2">
            <Dot tone={available ? "ok" : "muted"} title={available ? "CLI present" : "CLI absent"} />
            <span>CLI: {available ? "installed" : "not installed"}</span>
          </div>
          <div className="flex items-center gap-2">
            <Dot tone={dashboardServing ? "ok" : available ? "warn" : "muted"} />
            <span>
              Dashboard → host :9119: {dashboardServing
                ? `active on ${listenerLabel(dashboardService)} · recommended`
                : "off"}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Dot tone={apiServing ? "ok" : "muted"} />
            <span>
              API fallback → host :8642: {apiServing
                ? `active on ${listenerLabel(apiService)} · optional`
                : "off"}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Dot tone={legacyRelayServing ? "warn" : "muted"} />
            <span>
              Direct Relay → host :8767: {legacyRelayServing
                ? `active on ${listenerLabel(legacyRelayService)} · legacy compatibility`
                : "off"}
            </span>
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

        <div className="flex flex-wrap gap-2">
          <Button size="sm" disabled={busy || !available || serving} onClick={() => onEnable()}>
            {busy === "enable" ? "Enabling…" : "Enable HTTPS :443 ingress"}
          </Button>
          {dashboardServing && !apiServing ? (
            <Button
              size="sm"
              variant="outline"
              disabled={busy || !available}
              onClick={() => onEnable(8642)}
            >
              Enable optional API :8642
            </Button>
          ) : null}
          <Button
            size="sm"
            variant="outline"
            disabled={busy || !available || (!dashboardServing && !apiServing && !legacyRelayServing)}
            onClick={() => onDisable()}
          >
            {busy === "disable" ? "Disabling…" : "Disable managed routes"}
          </Button>
          {legacyRelayServing ? (
            <Button
              size="sm"
              variant="outline"
              disabled={busy || !available}
              onClick={() => onDisable(8767)}
            >
              Disable legacy :8767 after re-pairing
            </Button>
          ) : null}
        </div>

        <p className="text-xs text-muted-foreground">
          New pairing invites use the classified tailnet listener above — normally
          HTTPS :443 — which proxies local Dashboard :9119 and its same-origin Relay
          path. Keep :8767 active only until older devices have re-paired.
        </p>

        {resultMessage ? (
          <div className="text-xs text-muted-foreground whitespace-pre-wrap">
            {resultMessage}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function SecureLinkCard({ status }) {
  const enabled = !!(status && status.enabled);
  const url = status && status.url;
  const surfaces = Array.isArray(status && status.surfaces) ? status.surfaces : [];
  const reason = status && status.reason;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Hermes Secure Link</CardTitle>
          <Badge variant="outline" className="text-xs">Secure ingress</Badge>
        </div>
        <CardDescription>
          Pairing-pinned TLS for Hermes services. Secure Link authenticates the
          route but does not create internet reachability or perform NAT traversal;
          use LAN, Tailscale, a VPN, or a public route to reach this host.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Dot tone={enabled ? "ok" : "muted"} />
          <span>{enabled ? "Enabled" : "Not enabled"}</span>
          {enabled ? <Badge variant="outline">Pinned TLS</Badge> : null}
        </div>
        {url ? (
          <div className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs space-y-1">
            <div>
              <span className="uppercase tracking-wider text-muted-foreground">Advertised route</span>{" "}
              <span className="font-mono break-all">{url}</span>
            </div>
            {surfaces.length > 0 ? (
              <div className="flex flex-wrap gap-1 items-center">
                <span className="uppercase tracking-wider text-muted-foreground">Services</span>
                {surfaces.map((surface) => (
                  <Badge key={surface} variant="outline" className="text-xs capitalize">{surface}</Badge>
                ))}
              </div>
            ) : null}
          </div>
        ) : reason ? (
          <div className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs text-muted-foreground">
            {reason}
          </div>
        ) : null}
        <p className="text-xs text-muted-foreground">
          When enabled, new pairing invites include Secure Link alongside other
          reachable candidates. Existing devices must re-pair to trust its certificate pin.
        </p>
      </CardContent>
    </Card>
  );
}

function ExperimentalReachCard({ status }) {
  const reach = status && status.reach;
  if (!reach || (!reach.enabled && reach.state === "disabled")) return null;
  return (
    <details className="rounded-lg border border-dashed border-border bg-muted/10 px-4 py-3">
      <summary className="cursor-pointer list-none flex flex-wrap items-center gap-2 text-sm font-medium">
        <span>Hermes Reach</span>
        <Badge variant="outline" className="text-xs">Experimental</Badge>
        <span className="ml-auto text-xs text-muted-foreground">{reach.state || "disabled"}</span>
      </summary>
      <p className="mt-3 text-xs text-muted-foreground">
        Advanced brokered fallback for evaluation. Reach is disabled by default,
        tried after supported routes, and not recommended instead of Tailscale.
      </p>
      {reach.last_error ? <pre className="mt-2 whitespace-pre-wrap text-xs text-destructive">{reach.last_error}</pre> : null}
    </details>
  );
}

function PublicUrlCard({ initialUrl, onSaved }) {
  const [draft, setDraft] = useState(initialUrl || "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [lastSavedAt, setLastSavedAt] = useState(null);
  const [probeState, setProbeState] = useState({ reachable: null, status: null, latency_ms: null, error: null, at: null });
  const [probing, setProbing] = useState(false);
  const classification = useMemo(() => classifyPublicRouteInput(draft), [draft]);
  const inputIssue = classification.kind === "invalid"
    ? "Enter a complete HTTP(S) URL without credentials, query parameters, or a fragment."
    : classification.url && !classification.url.startsWith("https://")
      ? "Public routes must use HTTPS."
      : null;

  useEffect(() => {
    setDraft(initialUrl || "");
  }, [initialUrl]);

  const save = useCallback(async () => {
    setError(null);
    setSaving(true);
    try {
      const trimmed = draft.trim();
      if (trimmed && inputIssue) {
        setError(inputIssue);
        return;
      }
      const body = trimmed === "" ? null : trimmed;
      const data = await putPublicUrl(body, {
        legacyDirectRelay: classification.kind === "legacy-relay-path",
      });
      setLastSavedAt(Date.now());
      if (onSaved) onSaved(data && data.url ? data.url : null);
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }, [classification.kind, draft, inputIssue, onSaved]);

  const probe = useCallback(async () => {
    const trimmed = draft.trim();
    if (!trimmed || inputIssue) return;
    setProbing(true);
    try {
      const data = await probeEndpoints([{
        role: "public",
        priority: 0,
        surface: classification.kind === "legacy-relay-path" ? "relay" : "dashboard",
        url: trimmed,
      }]);
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
  }, [classification.kind, draft, inputIssue]);

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Public Dashboard origin</CardTitle>
          {classification.kind === "legacy-relay-path" ? (
            <Badge variant="outline" className="text-xs">Legacy Relay proxy path</Badge>
          ) : null}
        </div>
        <CardDescription>
          HTTPS origin for the Dashboard, Gateway, and same-origin Hermes-Relay
          transport (for example a reverse proxy or Cloudflare Tunnel). This becomes
          the next pairing QR's <code className="font-mono">role=public</code> route.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1">
          <Label htmlFor="public-url">Dashboard origin</Label>
          <Input
            id="public-url"
            value={draft}
            placeholder="https://hermes.example.com"
            onChange={(e) => setDraft(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            Leave empty to clear. Use an HTTPS Dashboard origin for new setups. An
            explicit path ending in the Relay transport is recognized as legacy
            reverse-proxy compatibility, not as a Dashboard origin. Direct port
            <code className="font-mono"> :8767</code> is not advertised for new pairing.
          </p>
        </div>

        {inputIssue ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-xs text-destructive">
            {inputIssue}
          </div>
        ) : null}

        {error ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-xs text-destructive">
            {error}
          </div>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={save} disabled={saving || !!inputIssue}>
            {saving ? "Saving…" : "Save"}
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={probe}
            disabled={probing || !draft.trim() || !!inputIssue}
          >
            {probing ? "Probing…" : "Probe route"}
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

function EndpointPreviewCard({ endpoints, reachability, onProbe, onRegenerate, busy, qrPayload, pairingUrl, preferRole, onPreferChange, blockingIssues }) {
  const canvasRef = useRef(null);
  const [copyStatus, setCopyStatus] = useState("");

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
    (reachability || []).forEach((r) => { m.set(pairingProbeKey(r), r); });
    return m;
  }, [reachability]);

  const copyInvite = useCallback(async () => {
    const invite = pairingUrl || qrPayload;
    if (!invite) return;
    try {
      await navigator.clipboard.writeText(invite);
      setCopyStatus("Copied invite URL");
    } catch (_err) {
      setCopyStatus("Copy failed; select the URL manually");
    }
  }, [pairingUrl, qrPayload]);

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
        {blockingIssues && blockingIssues.length > 0 ? (
          <Alert variant="destructive">
            <AlertTitle>Unsafe pairing route blocked</AlertTitle>
            <AlertDescription>
              <ul className="list-disc pl-4 text-xs space-y-1">
                {blockingIssues.map((issue) => <li key={issue}>{issue}</li>)}
              </ul>
            </AlertDescription>
          </Alert>
        ) : null}
        {endpoints.length === 0 ? (
          <div className="text-sm text-muted-foreground">
            No candidates detected. Enable Tailscale and/or pin a public URL above.
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Role</TableHead>
                <TableHead>Surface</TableHead>
                <TableHead>Resolved URL</TableHead>
                <TableHead>Priority</TableHead>
                <TableHead>Reachable</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {endpoints.map((ep) => {
                const r = reachabilityByUrl.get(pairingProbeKey(ep));
                return (
                  <TableRow key={pairingProbeKey(ep)}>
                    <TableCell>
                      <Badge variant="outline" className="text-xs capitalize">
                        {ep.role}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs capitalize">{ep.surface}</TableCell>
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
          <Button
            size="sm"
            variant="outline"
            onClick={onProbe}
            disabled={busy || endpoints.length === 0 || (blockingIssues && blockingIssues.length > 0)}
          >
            {busy === "probe" ? "Probing…" : "Probe all"}
          </Button>
          <Button size="sm" onClick={onRegenerate} disabled={busy}>
            {busy === "mint" ? "Regenerating…" : "Regenerate QR"}
          </Button>
        </div>

        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Label htmlFor="prefer-role" className="whitespace-nowrap">Prefer role:</Label>
          <select
            id="prefer-role"
            className="h-8 rounded-md border border-border bg-background px-2 text-xs"
            value={preferRole || ""}
            onChange={(e) => onPreferChange && onPreferChange(e.target.value || null)}
          >
            <option value="">(natural order — Tailscale → Public → LAN)</option>
            <option value="lan">lan → priority 0</option>
            <option value="tailscale">tailscale → priority 0</option>
            <option value="public">public → priority 0</option>
          </select>
          <span className="whitespace-nowrap">Applies on the next "Regenerate QR".</span>
        </div>

        {qrPayload && (!blockingIssues || blockingIssues.length === 0) ? (
          <div className="flex flex-col items-center gap-2 rounded-md border border-border bg-white p-3">
            <canvas ref={canvasRef} className="block" />
            <p className="text-xs text-muted-foreground">
              Scan from the Hermes-Relay Android app. Fresh payload, signed with the
              host's QR secret — embedding one-shot pairing code.
            </p>
          </div>
        ) : null}
        {pairingUrl && (!blockingIssues || blockingIssues.length === 0) ? (
          <div className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs space-y-2">
            <div className="uppercase tracking-wider text-muted-foreground">
              Copy/paste invite
            </div>
            <div className="font-mono break-all">{pairingUrl}</div>
            <div className="flex items-center gap-2">
              <Button size="sm" variant="outline" onClick={copyInvite}>
                Copy invite URL
              </Button>
              {copyStatus ? (
                <span className="text-muted-foreground">{copyStatus}</span>
              ) : null}
            </div>
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
  const [preferRole, setPreferRole] = useState(null);

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

  const onEnable = useCallback(async (port) => {
    setBusy("enable");
    setHelperMessage(null);
    try {
      const res = await enableTailscale(port);
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

  const onDisable = useCallback(async (port) => {
    setBusy("disable");
    setHelperMessage(null);
    try {
      const res = await disableTailscale(port);
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
      const dashboardUrl = canonicalDashboardOrigin(window.location);
      if (!dashboardUrl) {
        throw new Error("This Dashboard does not have a valid HTTP(S) origin for pairing.");
      }
      const data = await mintPairingWithMode({
        mode: "auto",
        prefer: preferRole || undefined,
        dashboard_url: dashboardUrl,
        legacy_direct_relay:
          classifyPublicRouteInput(publicUrl || "").kind === "legacy-relay-path",
      });
      setMintResult(data || null);
    } catch (err) {
      setMintResult({ error: err && err.message ? err.message : String(err) });
    } finally {
      setBusy(null);
    }
  }, [preferRole, publicUrl]);

  const previewReceipt = useMemo(() => {
    if (!mintResult || !mintResult.qr_payload) return { routes: [], blockingIssues: [] };
    try {
      const parsed = JSON.parse(mintResult.qr_payload);
      return pairingEndpointReceipt(parsed);
    } catch (_err) {
      return { routes: [], blockingIssues: ["Pairing payload is not valid JSON"] };
    }
  }, [mintResult]);
  const previewEndpoints = useMemo(
    () => previewReceipt && previewReceipt.routes ? pairingSurfaceProbes(previewReceipt) : [],
    [previewReceipt],
  );

  const onProbeAll = useCallback(async () => {
    if (previewEndpoints.length === 0) return;
    setBusy("probe");
    try {
      const data = await probeEndpoints(previewEndpoints);
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
  const secureLink = (status && status.secure_link) || {};
  const upstream = !!(status && status.upstream_canonical);

  return (
    <div className="space-y-4">
      <Alert>
        <AlertTitle>Recommended remote-access setup</AlertTitle>
        <AlertDescription>
          Start with <strong>Tailscale</strong> for private remote reachability.
          Operators with a domain or reachable WAN address can instead use a public
          TLS reverse proxy or <strong>Hermes Secure Link</strong>. Experimental
          broker routes are kept out of the normal setup path.
        </AlertDescription>
      </Alert>
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
        onEnable={onEnable}
        onDisable={onDisable}
        busy={busy === "enable" || busy === "disable" ? busy : null}
        resultMessage={helperMessage}
      />

      <SecureLinkCard status={secureLink} />

      <ExperimentalReachCard status={secureLink} />

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
        pairingUrl={mintResult && mintResult.pairing_url ? mintResult.pairing_url : null}
        preferRole={preferRole}
        onPreferChange={setPreferRole}
        blockingIssues={previewReceipt && previewReceipt.blockingIssues ? previewReceipt.blockingIssues : []}
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
