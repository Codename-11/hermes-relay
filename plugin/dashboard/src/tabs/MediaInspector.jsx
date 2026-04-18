const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback } = SDK.hooks;

import { getMedia } from "../lib/api.js";
import { relativeTime, bytes, ttlCountdown, shortToken } from "../lib/formatters.js";
import {
  Alert,
  AlertTitle,
  AlertDescription,
  CardDescription,
  Switch,
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
  Label,
} = SDK.components;

export default function MediaInspector({ autoRefresh }) {
  const [entries, setEntries] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [includeExpired, setIncludeExpired] = useState(false);
  const [now, setNow] = useState(Date.now());

  const load = useCallback(async () => {
    setError(null);
    try {
      const data = await getMedia({ includeExpired });
      const list = Array.isArray(data) ? data : (data && data.media) || [];
      setEntries(list);
    } catch (err) {
      setError(err && err.message ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [includeExpired]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!autoRefresh) return undefined;
    const id = setInterval(load, 15000);
    return () => clearInterval(id);
  }, [autoRefresh, load]);

  // TTL countdown tick — runs independently of fetches so the countdown
  // stays live between polls. 1s resolution, cleaned up on unmount.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (loading && entries === null) {
    return <div className="text-sm text-muted-foreground">Loading media registry…</div>;
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

  const list = entries || [];

  return (
    <Card>
      <CardHeader>
        <CardTitle>Media inspector</CardTitle>
        <CardDescription>
          Active MediaRegistry tokens. Expired entries are hidden by default.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="mb-3 flex items-center gap-3">
          <div className="flex items-center gap-2">
            <Switch
              id="include-expired"
              checked={includeExpired}
              onCheckedChange={setIncludeExpired}
            />
            <Label htmlFor="include-expired">Include expired</Label>
          </div>
          {!autoRefresh ? (
            <Button size="sm" variant="ghost" className="ml-auto" onClick={load}>
              Refresh
            </Button>
          ) : null}
        </div>

        {list.length === 0 ? (
          <div className="text-sm text-muted-foreground">
            {includeExpired
              ? "No media tokens registered."
              : "No active media tokens. Toggle 'Include expired' to see recently expired entries."}
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>File</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Size</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>TTL</TableHead>
                <TableHead>Token</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((m) => {
                const expired = !!m.is_expired;
                const ttl = expired ? "expired" : ttlCountdown(m.expires_at, now);
                return (
                  <TableRow key={m.token || `${m.file_name}-${m.created_at}`}>
                    <TableCell className="max-w-xs truncate font-mono text-xs">
                      {m.file_name || "—"}
                    </TableCell>
                    <TableCell className="text-xs">{m.content_type || "—"}</TableCell>
                    <TableCell className="text-xs">{bytes(m.size)}</TableCell>
                    <TableCell className="text-xs">{relativeTime(m.created_at)}</TableCell>
                    <TableCell>
                      <Badge variant={expired ? "secondary" : "outline"} className="text-xs">
                        {ttl}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {shortToken(m.token, 8)}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}
