const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import { getOverview, getSessions } from "../lib/api.js";
import { relativeTime, uptime, shortToken } from "../lib/formatters.js";

const {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  Button,
  Badge,
  Table,
  TableHeader,
  TableRow,
  TableHead,
  TableBody,
  TableCell,
  Alert,
  AlertTitle,
  AlertDescription,
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

  const onRevoke = (prefix) => {
    // D2 Option A: placeholder per plan. D1 doesn't proxy DELETE; real revoke
    // requires a future /sessions/{prefix}/revoke proxy route or a re-pair
    // from the phone. Keep the button visible so operators see the action
    // exists, but explain why it's not wired.
    window.alert(
      "Session revocation from the dashboard requires re-pairing from the phone.\n\n" +
        `Session: ${prefix}\n\n` +
        "TODO: add POST /api/plugins/hermes-relay/sessions/{prefix}/revoke to the plugin backend."
    );
  };

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
        <CardHeader>
          <CardTitle>Paired sessions</CardTitle>
          <CardDescription>
            Devices currently authorized against the relay. Revocation requires re-pair from the phone.
          </CardDescription>
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
                          onClick={() => {
                            if (
                              window.confirm(
                                `Revoke session "${label}"? This will force the device to re-pair.`
                              )
                            ) {
                              onRevoke(token);
                            }
                          }}
                        >
                          Revoke
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
    </div>
  );
}
