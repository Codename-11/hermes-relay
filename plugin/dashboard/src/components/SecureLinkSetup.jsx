const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useRef, useEffect } = SDK.hooks;
const { Input, Label } = SDK.components;

import { getSecureLinkPreflight } from "../lib/api.js";
import { Button, Badge, Alert, AlertTitle, AlertDescription } from "../lib/ui-shims.jsx";

/** Readiness and instructions only: the browser never guesses a service owner. */
export default function SecureLinkSetup({ status, onPair, onInvalidateInvite, pairingBusy = false }) {
  const initial = (() => { try { return new URL(status.url); } catch { return null; } })();
  const [open, setOpen] = useState(false);
  const [host, setHost] = useState(initial?.hostname || "");
  const [port, setPort] = useState(initial?.port || "9443");
  const [report, setReport] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    sequence.current += 1;
    setReport(null);
    setBusy(false);
    onInvalidateInvite?.();
  }, [status.url]);
  const change = (setter, value) => {
    sequence.current += 1;
    setter(value);
    setReport(null);
    setError("");
    setCopied(false);
    setBusy(false);
    onInvalidateInvite?.();
  };
  const check = async () => {
    const request = ++sequence.current;
    setOpen(true);
    setBusy(true);
    setError("");
    setCopied(false);
    onInvalidateInvite?.();
    try {
      const next = await getSecureLinkPreflight({ host: host.trim() || undefined, port });
      if (request !== sequence.current) return;
      if (next?.schema_version !== 1 || !Array.isArray(next.checks)) throw new Error("Relay returned an unsupported setup report.");
      setReport(next);
      if (!host.trim() && next.host) setHost(next.host);
    } catch (err) {
      if (request !== sequence.current) return;
      setReport(null);
      setError(`Setup checks unavailable. Confirm Relay is running and supports Secure Link setup checks. ${err.message || ""}`);
    } finally {
      if (request === sequence.current) setBusy(false);
    }
  };
  const environment = Object.entries(report?.environment || {}).map(([key, value]) => `${key}=${value}`).join("\n");
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(environment);
      setCopied(true);
    } catch { setError("Clipboard access is unavailable. Select and copy the settings below."); }
  };

  return (
    <div className="space-y-3">
      {!open ? <Button variant="outline" onClick={check}>{status.enabled ? "Check Secure Link" : "Set up Secure Link"}</Button> : (
        <div className="space-y-4 border-t border-border pt-3">
          <div className="space-y-2">
            <h4 className="text-sm font-semibold">1. Check this server</h4>
            <p className="text-xs text-muted-foreground">Checks are read-only. They do not change settings, create keys, or restart services.</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <Label htmlFor="secure-link-host">Address the phone will use</Label>
                <Input id="secure-link-host" value={host} placeholder="192.168.1.20 or relay.example"
                  onChange={(event) => change(setHost, event.target.value)} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="secure-link-port">HTTPS port</Label>
                <Input id="secure-link-port" value={port} inputMode="numeric"
                  onChange={(event) => change(setPort, event.target.value)} />
              </div>
            </div>
            <Button size="sm" variant="outline" disabled={busy} onClick={check}>{busy ? "Checking…" : "Check again"}</Button>
          </div>
          {error ? <Alert variant="destructive"><AlertTitle>Check needed</AlertTitle><AlertDescription>{error}</AlertDescription></Alert> : null}
          {report ? (
            <>
              <div role="list" className="space-y-2" aria-live="polite">
                {report.checks.map((item) => (
                  <div role="listitem" key={item.id} className="rounded-md border border-border p-3 space-y-1">
                    <div className="flex items-center justify-between gap-2 text-sm">
                      <span>{item.label}</span><Badge variant={item.status === "blocked" ? "destructive" : "outline"}>
                        {item.status === "ok" ? "Checked" : item.status === "warning" ? "Optional / unavailable" : "Needs attention"}
                      </Badge>
                    </div>
                    <p className="text-xs text-muted-foreground">{item.detail}</p>
                  </div>
                ))}
              </div>
              {report.ready_to_enable && !report.pairing_ready ? (
                <div className="space-y-2">
                  <h4 className="text-sm font-semibold">2. Review and enable</h4>
                  <p className="font-mono text-xs break-all">{report.url}</p>
                  {report.requires_repair ? <p className="text-xs text-muted-foreground">This changes the paired address or port. Existing clients must re-pair after activation; no certificate is replaced by this check.</p> : null}
                  <p className="text-xs text-muted-foreground">Add these settings to the existing Relay environment. Restart its owner, then choose Check again. Do not start a second Relay.</p>
                  <p className="text-xs text-muted-foreground">{report.connected_clients} Relay client(s) connected. {report.restart_notice}</p>
                  <p className="text-xs text-muted-foreground">{report.configuration_note}</p>
                  <pre className="rounded-md bg-muted/20 p-3 text-xs whitespace-pre-wrap break-all select-text">{environment}</pre>
                  <Button size="sm" variant="outline" onClick={copy}>{copied ? "Copied" : "Copy settings"}</Button>
                  <details className="text-xs text-muted-foreground">
                    <summary className="cursor-pointer">Foreground / CLI startup</summary>
                    <p className="mt-2">Add to your existing <code>hermes relay start</code> command, preserving its other arguments:</p>
                    <pre className="mt-2 whitespace-pre-wrap break-all select-text">{report.start_arguments.join(" ")}</pre>
                  </details>
                </div>
              ) : null}
              {report.pairing_ready ? (
                <div className="space-y-2">
                  <h4 className="text-sm font-semibold">3. Pair a device</h4>
                  <p className="font-mono text-xs break-all">{report.current_url}</p>
                  <p className="text-xs text-muted-foreground">Secure Link is listening. Create a fresh signed QR below, scan it in Android, then sign into Dashboard. Chat becomes ready only after Gateway connects. Existing devices must re-pair to import this certificate and pin.</p>
                  <Button disabled={pairingBusy || busy} onClick={() => onPair(report.current_url)}>{pairingBusy ? "Creating invite…" : "Create pairing QR"}</Button>
                </div>
              ) : null}
              <details className="text-xs text-muted-foreground">
                <summary className="cursor-pointer">Disable or recover</summary>
                <p className="mt-2">Set <code>RELAY_SECURE_LINK_ENABLED=0</code> (or replace the startup flag with <code>--no-secure-link</code>), then restart Relay using its existing manager. Keep the certificate and key if you intend to re-enable the same route. Address or certificate changes require explicit re-pairing.</p>
                <p className="mt-2">This flow does not restart services or open firewall ports. If activation fails, restore the previous Relay settings and check its health before retrying.</p>
              </details>
            </>
          ) : null}
        </div>
      )}
    </div>
  );
}
