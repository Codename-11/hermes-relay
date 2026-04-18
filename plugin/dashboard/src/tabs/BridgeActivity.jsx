const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback, useMemo } = SDK.hooks;

import { getBridgeActivity } from "../lib/api.js";
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
} = SDK.components;

const FILTERS = ["All", "Executed", "Blocked", "Confirmed", "Timeout", "Error"];

const BADGE_VARIANT = {
  executed: "default",
  blocked: "destructive",
  confirmed: "default",
  timeout: "secondary",
  error: "destructive",
  pending: "outline",
};

function DecisionBadge({ decision }) {
  const d = (decision || "pending").toLowerCase();
  const variant = BADGE_VARIANT[d] || "outline";
  return (
    <Badge variant={variant} className="text-xs capitalize">
      {d}
    </Badge>
  );
}

function paramsPreview(params) {
  if (!params || typeof params !== "object") return "—";
  try {
    return JSON.stringify(params, null, 2);
  } catch (_err) {
    return String(params);
  }
}

export default function BridgeActivity({ autoRefresh }) {
  const [activity, setActivity] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState("All");
  const [expanded, setExpanded] = useState({});

  const load = useCallback(async () => {
    setError(null);
    try {
      const data = await getBridgeActivity(100);
      const rows = Array.isArray(data) ? data : (data && data.activity) || [];
      setActivity(rows);
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
    const id = setInterval(load, 5000);
    return () => clearInterval(id);
  }, [autoRefresh, load]);

  const filtered = useMemo(() => {
    if (!activity) return [];
    if (filter === "All") return activity;
    const want = filter.toLowerCase();
    return activity.filter((r) => (r.decision || "").toLowerCase() === want);
  }, [activity, filter]);

  if (loading) {
    return <div className="text-sm text-muted-foreground">Loading bridge activity…</div>;
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

  return (
    <Card>
      <CardHeader>
        <CardTitle>Bridge activity</CardTitle>
        <CardDescription>
          Most recent bridge commands routed through the relay. Newest first; capped at 100.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="mb-3 flex flex-wrap items-center gap-2">
          {FILTERS.map((f) => (
            <Button
              key={f}
              size="sm"
              variant={filter === f ? "default" : "outline"}
              onClick={() => setFilter(f)}
            >
              {f}
            </Button>
          ))}
          {!autoRefresh ? (
            <Button size="sm" variant="ghost" className="ml-auto" onClick={load}>
              Refresh
            </Button>
          ) : null}
        </div>

        {filtered.length === 0 ? (
          <div className="text-sm text-muted-foreground">
            {activity && activity.length === 0
              ? "No bridge commands recorded yet."
              : `No entries match "${filter}".`}
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Sent</TableHead>
                <TableHead>Method</TableHead>
                <TableHead>Path</TableHead>
                <TableHead>Decision</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Error</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((row, idx) => {
                const key = row.request_id || `${row.sent_at}-${idx}`;
                const isOpen = !!expanded[key];
                return (
                  <React.Fragment key={key}>
                    <TableRow
                      className="cursor-pointer"
                      onClick={() => setExpanded((prev) => ({ ...prev, [key]: !prev[key] }))}
                    >
                      <TableCell>{relativeTime(row.sent_at)}</TableCell>
                      <TableCell className="font-mono text-xs">{row.method || "—"}</TableCell>
                      <TableCell className="font-mono text-xs">{row.path || "—"}</TableCell>
                      <TableCell>
                        <DecisionBadge decision={row.decision} />
                      </TableCell>
                      <TableCell>{row.response_status ?? "—"}</TableCell>
                      <TableCell className="max-w-xs truncate text-xs text-destructive">
                        {row.error || ""}
                      </TableCell>
                    </TableRow>
                    {isOpen ? (
                      <TableRow>
                        <TableCell colSpan={6} className="bg-muted/30">
                          <div className="space-y-2 py-2 text-xs">
                            <div>
                              <span className="font-semibold">request_id:</span>{" "}
                              <span className="font-mono">{row.request_id || "—"}</span>
                            </div>
                            <div>
                              <span className="font-semibold">params (redacted):</span>
                              <pre className="mt-1 whitespace-pre-wrap rounded bg-background p-2 font-mono">
                                {paramsPreview(row.params)}
                              </pre>
                            </div>
                            {row.result_summary ? (
                              <div>
                                <span className="font-semibold">result:</span>{" "}
                                <span>{row.result_summary}</span>
                              </div>
                            ) : null}
                          </div>
                        </TableCell>
                      </TableRow>
                    ) : null}
                  </React.Fragment>
                );
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}
