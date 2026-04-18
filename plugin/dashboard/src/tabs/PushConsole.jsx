const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect } = SDK.hooks;

import { getPush } from "../lib/api.js";

const {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  Alert,
  AlertTitle,
  AlertDescription,
  Button,
} = SDK.components;

export default function PushConsole() {
  const [info, setInfo] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await getPush();
        if (!cancelled) setInfo(data || null);
      } catch (err) {
        if (!cancelled) setError(err && err.message ? err.message : String(err));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Push console</CardTitle>
        <CardDescription>Send an ad-hoc notification to paired devices.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <Alert>
          <AlertTitle>Push delivery not configured</AlertTitle>
          <AlertDescription>
            FCM integration is tracked as a deferred item. Once wired, this tab will let operators
            compose and send notifications directly from the dashboard.{" "}
            <a
              className="underline"
              href="https://github.com/Codename-11/hermes-relay/blob/main/docs/plans/"
              target="_blank"
              rel="noreferrer"
            >
              See deferred items
            </a>
            .
          </AlertDescription>
        </Alert>

        {loading ? (
          <div className="text-sm text-muted-foreground">Probing backend…</div>
        ) : error ? (
          <Alert variant="destructive">
            <AlertTitle>Relay unreachable</AlertTitle>
            <AlertDescription>
              <pre className="whitespace-pre-wrap text-xs">{error}</pre>
            </AlertDescription>
          </Alert>
        ) : info ? (
          <div className="rounded border border-border bg-muted/30 p-3 text-xs">
            <div>
              <span className="font-semibold">configured:</span> {String(info.configured ?? false)}
            </div>
            {info.reason ? (
              <div className="mt-1">
                <span className="font-semibold">reason:</span> {info.reason}
              </div>
            ) : null}
          </div>
        ) : null}

        <div className="flex gap-2 opacity-50">
          <Button size="sm" variant="outline" disabled>
            Compose (disabled)
          </Button>
          <Button size="sm" variant="outline" disabled>
            Send test (disabled)
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
