const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import { getOverview, getSessions, revokeSession } from "../lib/api.js";
import { relativeTime, uptime, shortToken } from "../lib/formatters.js";
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
                  <TableHead>Label</TableHead>
                  <TableHead>Last seen</TableHead>
                  <TableHead>Grants</TableHead>
                  <TableHead>Expires</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.map((s, idx) => {
                  const token = s.token || s.token_prefix || s.prefix || "";
                  const label = s.label || s.device_label || shortToken(token);
                  const lastSeen = s.last_seen || s.last_activity || s.last_seen_at;
                  const grants = Array.isArray(s.grants) ? s.grants : s.grants ? [s.grants] : [];
                  return (
                    <TableRow key={token || idx}>
                      <TableCell className="font-medium">{label}</TableCell>
                      <TableCell>{relativeTime(lastSeen)}</TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {grants.length === 0 ? (
                            <span className="text-xs text-muted-foreground">—</span>
                          ) : (
                            grants.map((g) => (
                              <Badge key={g} variant="secondary" className="text-xs">
                                {g}
                              </Badge>
                            ))
                          )}
                        </div>
                      </TableCell>
                      <TableCell>{s.expires_at ? relativeTime(s.expires_at) : "never"}</TableCell>
                      <TableCell className="text-right">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={revoking === token || !token}
                          onClick={() => onRevoke(token, label)}
                        >
                          {revoking === token ? "Revoking…" : "Revoke"}
                        </Button>
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
