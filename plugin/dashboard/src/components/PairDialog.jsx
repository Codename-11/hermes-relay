const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useRef, useCallback } = SDK.hooks;

import QRCode from "qrcode";
import { mintPairing } from "../lib/api.js";

const { Card, CardHeader, CardTitle, CardContent, Button, Badge } = SDK.components;

function useCountdown(expiresAt) {
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  useEffect(() => {
    if (!expiresAt) return undefined;
    const id = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 1000);
    return () => clearInterval(id);
  }, [expiresAt]);
  if (!expiresAt) return null;
  const remaining = Math.max(0, expiresAt - now);
  if (remaining <= 0) return "expired";
  const m = Math.floor(remaining / 60);
  const s = remaining % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

export default function PairDialog({ open, onClose, host, port, tls }) {
  const [state, setState] = useState({ status: "idle" });
  const canvasRef = useRef(null);
  const countdown = useCountdown(state.data ? state.data.expires_at : null);

  const mint = useCallback(async () => {
    setState({ status: "loading" });
    try {
      const data = await mintPairing({ host, port, tls, transport_hint: tls ? "wss" : "ws" });
      setState({ status: "ok", data });
    } catch (err) {
      setState({ status: "error", error: err.message || String(err) });
    }
  }, [host, port, tls]);

  useEffect(() => {
    if (open && state.status === "idle") mint();
  }, [open, state.status, mint]);

  useEffect(() => {
    if (state.status !== "ok" || !canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, state.data.qr_payload, {
      width: 280,
      margin: 2,
      errorCorrectionLevel: "M",
    }).catch(() => {
      /* canvas draw failure is non-fatal — user can still read the code */
    });
  }, [state.status, state.data]);

  const reset = useCallback(() => setState({ status: "idle" }), []);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-sm p-4">
      <Card className="w-full max-w-md">
        <CardHeader className="flex flex-row items-start justify-between gap-2 space-y-0">
          <div>
            <CardTitle>Pair new device</CardTitle>
            <p className="text-sm text-muted-foreground mt-1">
              Scan from the Hermes-Relay Android app to pair.
            </p>
          </div>
          <Button variant="ghost" size="sm" onClick={onClose}>
            Close
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {state.status === "loading" && (
            <div className="text-sm text-muted-foreground">Minting code…</div>
          )}
          {state.status === "error" && (
            <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              <div className="font-medium mb-1">Minting failed</div>
              <div className="break-words">{state.error}</div>
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={mint}>
                  Retry
                </Button>
              </div>
            </div>
          )}
          {state.status === "ok" && (
            <>
              <div className="flex justify-center rounded-md border border-border bg-white p-3">
                <canvas ref={canvasRef} className="block" />
              </div>
              <div className="flex items-center justify-between gap-2">
                <div>
                  <div className="text-xs uppercase tracking-wider text-muted-foreground">Code</div>
                  <div className="font-mono text-2xl tracking-widest">{state.data.code}</div>
                </div>
                <div className="text-right">
                  <div className="text-xs uppercase tracking-wider text-muted-foreground">Expires in</div>
                  <Badge variant={countdown === "expired" ? "destructive" : "outline"}>
                    {countdown || "—"}
                  </Badge>
                </div>
              </div>
              <div className="text-xs text-muted-foreground">
                Host <span className="font-mono">{state.data.host}:{state.data.port}</span>
                {" "} · TLS <span className="font-mono">{String(state.data.tls)}</span>
              </div>
              <div className="flex gap-2 pt-1">
                <Button size="sm" variant="outline" onClick={reset}>
                  New code
                </Button>
                <Button size="sm" onClick={onClose}>
                  Done
                </Button>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
