const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import { getOverview, getSessions, revokeSession } from "../lib/api.js";
import { relativeTime, ttlCountdown, uptime, shortToken } from "../lib/formatters.js";
import PairDialog from "../components/PairDialog.jsx";
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
} = SDK.components;

function valueText(value) {
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) return value.join(" ");
  if (typeof value === "object") return Object.keys(value).join(" ");
  return String(value);
}

function extractGrants(session) {
  const raw = session && session.grants;
  if (Array.isArray(raw)) {
    return raw
      .map((entry) => {
        if (typeof entry === "string") return { name: entry, detail: "" };
        if (!entry || typeof entry !== "object") return null;
        const name = entry.name || entry.channel || entry.grant || entry.scope;
        if (!name) return null;
        return {
          name: String(name),
          detail: entry.expires_at
            ? ttlCountdown(entry.expires_at)
            : formatGrantValue(entry.ttl_seconds ?? entry.ttl ?? entry.seconds),
        };
      })
      .filter(Boolean);
  }
  if (raw && typeof raw === "object") {
    return Object.entries(raw).map(([name, value]) => ({
      name,
      detail:
        value && typeof value === "object"
          ? value.expires_at || value.expiresAt || value.until
            ? ttlCountdown(value.expires_at || value.expiresAt || value.until)
            : formatGrantValue(value.ttl_seconds ?? value.ttl ?? value.seconds)
          : formatGrantValue(value),
    }));
  }
  return [];
}

function formatGrantValue(value) {
  if (value === null || value === undefined || value === "" || value === true) return "";
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) return "";
  return seconds > 1e9 ? ttlCountdown(seconds) : formatDuration(seconds);
}

function formatDuration(value) {
  if (value === null || value === undefined || value === "" || value === true) return "";
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) return "";
  if (seconds < 60) return `${Math.floor(seconds)}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remMinutes = minutes % 60;
  if (hours < 24) return remMinutes ? `${hours}h ${remMinutes}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return remHours ? `${days}d ${remHours}h` : `${days}d`;
}

function classifySession(session, grants) {
  const haystack = [
    session.device_type,
    session.client_type,
    session.platform,
    session.device_name,
    session.device_label,
    session.client_name,
    session.label,
    session.transport,
    session.transport_hint,
    session.channel,
    valueText(session.capabilities),
    grants.map((g) => g.name).join(" "),
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();

  if (/\bandroid\b|\bmobile\b|\bphone\b|hermes-relay-android/.test(haystack)) {
    return "Android";
  }
  if (/\btui\b|terminal-ui|textual/.test(haystack)) {
    return "Desktop TUI";
  }
  if (/\bcli\b|terminal|shell|desktop|tool|powershell|bash|cmd\.exe/.test(haystack)) {
    return "Desktop CLI";
  }
  if (/\bweb\b|\bbrowser\b|\bdashboard\b/.test(haystack)) {
    return "Dashboard";
  }
  return "Client";
}

function sessionTransport(session) {
  return (
    session.transport_hint ||
    session.transport ||
    session.channel ||
    session.connection ||
    session.protocol ||
    ""
  );
}

function sessionTokenPrefix(session) {
  const raw = session.token || session.session_token || "";
  return (
    session.token_prefix ||
    session.prefix ||
    session.tokenPrefix ||
    session.session_prefix ||
    (raw ? String(raw).slice(0, 12) : "")
  );
}

function StatCard({ label, value, hint }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl">{value}</CardTitle>
      </CardHeader>
      {hint ? (
        <CardContent className="pt-0 text-xs text-muted-foreground">{hint}</CardContent>
      ) : null}
    </Card>
  );
}

export default function RelayManagement({ autoRefresh }) {
  const [overview, setOverview] = useState(null);
  const [sessions, setSessions] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pairOpen, setPairOpen] = useState(false);
  const [revoking, setRevoking] = useState(null);
  const [copied, setCopied] = useState(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [ov, se] = await Promise.all([getOverview(), getSessions()]);
      setOverview(ov || null);
      // Relay /sessions returns either {sessions:[...]} or [...] — handle both.
      const list = Array.isArray(se) ? se : (se && se.sessions) || [];
      setSessions(list);
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
    const id = setInterval(load, 10000);
    return () => clearInterval(id);
  }, [autoRefresh, load]);

  const onRevoke = useCallback(async (prefix, label) => {
    if (!window.confirm(
      `Revoke paired device${label ? ` "${label}"` : ""}?\n\n` +
      `Token prefix: ${prefix}\n\n` +
      "The phone will need to re-pair. This cannot be undone."
    )) return;
    setRevoking(prefix);
    try {
      await revokeSession(prefix);
      await load();
    } catch (err) {
      window.alert(`Revoke failed: ${err && err.message ? err.message : err}`);
    } finally {
      setRevoking(null);
    }
  }, [load]);

  const onCopyPrefix = useCallback(async (prefix) => {
    if (!prefix) return;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(prefix);
      } else {
        window.prompt("Copy token prefix", prefix);
      }
      setCopied(prefix);
      window.setTimeout(() => setCopied(null), 1500);
    } catch (_err) {
      window.prompt("Copy token prefix", prefix);
    }
  }, []);

  if (loading) {
    return <div className="text-sm text-muted-foreground">Loading overview…</div>;
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Relay unreachable</AlertTitle>
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

  const ov = overview || {};
  const list = sessions || [];

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Version" value={ov.version || "—"} hint={ov.health ? `health: ${ov.health}` : null} />
        <StatCard label="Uptime" value={uptime(ov.uptime_seconds)} />
        <StatCard
          label="Paired devices"
          value={ov.paired_device_count ?? ov.session_count ?? 0}
          hint={ov.session_count != null ? `${ov.session_count} session(s)` : null}
        />
        <StatCard
          label="Pending / media"
          value={`${ov.pending_commands ?? 0} / ${ov.media_entry_count ?? 0}`}
          hint="pending commands / media tokens"
        />
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <div>
            <CardTitle>Paired sessions</CardTitle>
            <CardDescription>
              Devices currently authorized against the relay.
            </CardDescription>
          </div>
          <Button size="sm" onClick={() => setPairOpen(true)}>
            Pair new device
          </Button>
        </CardHeader>
        <CardContent>
          {!autoRefresh ? (
            <div className="mb-3">
              <Button size="sm" variant="outline" onClick={load}>
                Refresh
              </Button>
            </div>
          ) : null}
          {list.length === 0 ? (
            <div className="text-sm text-muted-foreground">No paired sessions.</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Device</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Last seen</TableHead>
                  <TableHead>TTL</TableHead>
                  <TableHead>Grants</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.map((s, idx) => {
                  const tokenPrefix = sessionTokenPrefix(s);
                  const label =
                    s.device_name ||
                    s.device_label ||
                    s.client_name ||
                    s.label ||
                    shortToken(tokenPrefix);
                  const lastSeen =
                    s.last_seen ||
                    s.last_activity ||
                    s.last_seen_at ||
                    s.last_activity_at ||
                    s.updated_at ||
                    s.paired_at;
                  const expiresAt = s.expires_at || s.expiresAt || s.expires;
                  const ttl = expiresAt ? ttlCountdown(expiresAt) : "never";
                  const grants = extractGrants(s);
                  const type = classifySession(s, grants);
                  const transport = sessionTransport(s);
                  return (
                    <TableRow key={tokenPrefix || idx}>
                      <TableCell className="font-medium">
                        <div>{label}</div>
                        <div className="font-mono text-xs font-normal text-muted-foreground">
                          {tokenPrefix ? shortToken(tokenPrefix, 12) : "no token prefix"}
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-col gap-1">
                          <Badge variant="outline" className="w-fit text-xs">
                            {type}
                          </Badge>
                          {transport ? (
                            <span className="text-xs text-muted-foreground">{transport}</span>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell>{relativeTime(lastSeen)}</TableCell>
                      <TableCell>
                        <Badge
                          variant={ttl === "expired" ? "destructive" : "secondary"}
                          className="text-xs"
                        >
                          {ttl}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {grants.length === 0 ? (
                            <span className="text-xs text-muted-foreground">—</span>
                          ) : (
                            grants.map((g) => (
                              <Badge key={`${g.name}:${g.detail}`} variant="secondary" className="text-xs">
                                {g.detail ? `${g.name} ${g.detail}` : g.name}
                              </Badge>
                            ))
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={!tokenPrefix}
                            onClick={() => onCopyPrefix(tokenPrefix)}
                          >
                            {copied === tokenPrefix ? "Copied" : "Copy prefix"}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={revoking === tokenPrefix || !tokenPrefix}
                            onClick={() => onRevoke(tokenPrefix, label)}
                          >
                            {revoking === tokenPrefix ? "Revoking…" : "Revoke"}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
      <PairDialog
        open={pairOpen}
        onClose={() => { setPairOpen(false); load(); }}
      />
    </div>
  );
}
