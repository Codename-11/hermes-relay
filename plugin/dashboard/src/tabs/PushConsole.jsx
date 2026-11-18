const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useCallback, useRef } = SDK.hooks;

import {
  clearPushServiceAccount,
  getPush,
  installPushServiceAccount,
  setPushEnabled,
} from "../lib/api.js";
import {
  Alert,
  AlertTitle,
  AlertDescription,
  CardDescription,
  Button,
  Badge,
  Switch,
} from "../lib/ui-shims.jsx";

const {
  Card,
  CardHeader,
  CardTitle,
  CardContent,
  Label,
  Toast,
} = SDK.components;

const useToast = SDK.hooks.useToast;

function errorMessage(error) {
  if (!error) return "Unknown error";
  if (typeof error === "string") return error;
  return error.message || error.detail || String(error);
}

export default function PushConsole({ autoRefresh = false } = {}) {
  const [info, setInfo] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(null);
  const fileRef = useRef(null);
  const { toast, showToast } = useToast();

  const load = useCallback(async () => {
    try {
      const data = await getPush();
      setInfo(data || null);
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
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

  const onPickFile = useCallback(async (event) => {
    const file = event.target.files && event.target.files[0];
    event.target.value = "";
    if (!file) return;
    setBusy("install");
    try {
      const text = await file.text();
      const next = await installPushServiceAccount({ json_text: text });
      setInfo(next);
      showToast(
        next.configured
          ? `Service account installed (${next.project_id || "ok"})`
          : "Install finished but not configured",
        next.configured ? "success" : "error",
      );
    } catch (err) {
      showToast(`Install failed: ${errorMessage(err)}`, "error");
    } finally {
      setBusy(null);
    }
  }, [showToast]);

  const onToggle = useCallback(async (checked) => {
    setBusy("enabled");
    try {
      const next = await setPushEnabled(!!checked);
      setInfo(next);
      showToast(checked ? "FCM wake enabled" : "FCM wake disabled", "success");
    } catch (err) {
      showToast(`Update failed: ${errorMessage(err)}`, "error");
    } finally {
      setBusy(null);
    }
  }, [showToast]);

  const onClear = useCallback(async () => {
    if (!window.confirm("Remove the Firebase service account from this host?")) {
      return;
    }
    setBusy("clear");
    try {
      const next = await clearPushServiceAccount();
      setInfo(next);
      showToast("Service account cleared", "success");
    } catch (err) {
      showToast(`Clear failed: ${errorMessage(err)}`, "error");
    } finally {
      setBusy(null);
    }
  }, [showToast]);

  const configured = !!(info && info.configured);
  const enabled = !!(info && info.enabled);

  return (
    <Card>
      <Toast toast={toast} />
      <CardHeader>
        <CardTitle>Push wake (BYO FCM)</CardTitle>
        <CardDescription>
          Install the Firebase <strong>Admin service account</strong> on this Hermes host
          so the relay can wake your phone when the live socket is down. The service
          account never goes on the phone — the app only needs client{" "}
          <code>google-services.json</code>.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {loading ? (
          <div className="text-sm text-muted-foreground">Checking host FCM status…</div>
        ) : error ? (
          <Alert variant="destructive">
            <AlertTitle>Status unavailable</AlertTitle>
            <AlertDescription>
              <pre className="whitespace-pre-wrap text-xs">{error}</pre>
            </AlertDescription>
          </Alert>
        ) : null}

        {info ? (
          <div className="rounded border border-border bg-muted/30 p-3 space-y-2 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold">Host status</span>
              <Badge variant={configured && enabled ? "default" : "outline"}>
                {configured && enabled
                  ? "Ready"
                  : configured
                    ? "Installed · disabled"
                    : "Not configured"}
              </Badge>
            </div>
            <div className="text-xs text-muted-foreground space-y-1">
              {info.project_id ? <div>project: <code>{info.project_id}</code></div> : null}
              {info.client_email ? <div>account: <code>{info.client_email}</code></div> : null}
              {info.path ? <div>file: <code className="break-all">{info.path}</code></div> : (
                <div>default path: <code className="break-all">{info.default_path}</code></div>
              )}
              <div>registered devices: {info.device_tokens ?? 0}</div>
              {info.reason ? <div>{info.reason}</div> : null}
            </div>
          </div>
        ) : null}

        <div className="space-y-2">
          <h4 className="text-sm font-semibold">1. Install service account on this Mac</h4>
          <p className="text-xs text-muted-foreground">
            Firebase Console → Project settings → Service accounts → Generate new private key.
            Choose that JSON here (or CLI:{" "}
            <code>python ~/.hermes/plugins/hermes-relay/relay/fcm_sender.py install ./sa.json</code>).
          </p>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json,text/plain"
            className="hidden"
            onChange={onPickFile}
          />
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={busy === "install"}
              onClick={() => fileRef.current && fileRef.current.click()}
            >
              {busy === "install" ? "Installing…" : configured ? "Replace service account…" : "Choose service-account JSON…"}
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={!configured || busy === "clear"}
              onClick={onClear}
            >
              {busy === "clear" ? "Clearing…" : "Remove"}
            </Button>
            <Button size="sm" variant="outline" onClick={load} disabled={!!busy}>
              Refresh
            </Button>
          </div>
        </div>

        <div className="flex items-center justify-between gap-3 rounded border border-border p-3">
          <div className="space-y-1">
            <Label htmlFor="fcm-enabled">Enable FCM wake</Label>
            <p className="text-xs text-muted-foreground">
              Off keeps the file but stops sending wakes.
            </p>
          </div>
          <Switch
            id="fcm-enabled"
            checked={enabled}
            disabled={!configured || busy === "enabled"}
            onCheckedChange={onToggle}
          />
        </div>

        <Alert>
          <AlertTitle>2. Phone (client config only)</AlertTitle>
          <AlertDescription>
            {info?.phone_setup ||
              "Sideload app → Settings → Power tools → Threads → Push wake (BYO FCM). Load google-services.json (client), enable, Save. Never paste the Admin service account into the app."}
          </AlertDescription>
        </Alert>
      </CardContent>
    </Card>
  );
}
