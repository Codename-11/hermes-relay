const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const { useState, useEffect, useMemo, useRef, useCallback } = SDK.hooks;

import QRCode from "qrcode";
import { Button, Badge } from "../lib/ui-shims.jsx";
import {
  buildDashboardSetupPayload,
  canonicalDashboardOrigin,
} from "../lib/mobile-setup.mjs";
import { activateModal } from "../lib/modal-focus.mjs";

export default function MobileConnectDialog({ open, onClose }) {
  const dialogRef = useRef(null);
  const canvasRef = useRef(null);
  const [copyStatus, setCopyStatus] = useState("");
  const origin = useMemo(
    () => canonicalDashboardOrigin(window.location),
    [open],
  );
  const payload = useMemo(() => {
    if (!origin) return null;
    return buildDashboardSetupPayload(origin);
  }, [origin]);

  useEffect(() => {
    if (!open || !payload || !canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, payload, {
      width: 280,
      margin: 2,
      errorCorrectionLevel: "M",
    }).catch(() => { /* canvas failure is shown by the address fallback */ });
  }, [open, payload]);

  useEffect(() => {
    if (open) setCopyStatus("");
  }, [open]);

  useEffect(() => {
    if (!open || !dialogRef.current) return undefined;
    return activateModal({
      dialog: dialogRef.current,
      documentRef: window.document,
      onClose,
    });
  }, [open, onClose]);

  const copyAddress = useCallback(async () => {
    if (!origin) return;
    try {
      await navigator.clipboard.writeText(origin);
      setCopyStatus("Copied dashboard address");
    } catch (_err) {
      setCopyStatus("Copy failed; select the address below");
    }
  }, [origin]);

  if (!open) return null;

  return (
    <div
      className="hermes-relay-plugin hr-modal-backdrop"
    >
      <div
        ref={dialogRef}
        className="hr-modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="hr-mobile-connect-title"
        aria-describedby="hr-mobile-connect-description"
        tabIndex={-1}
      >
        <div className="hr-modal-header">
          <div>
            <h2 id="hr-mobile-connect-title" className="hr-modal-title">
              Connect mobile app
            </h2>
            <p id="hr-mobile-connect-description" className="text-sm text-muted-foreground mt-1">
              Scan with Hermes-Relay Android to add this Dashboard.
            </p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="hr-modal-close"
            onClick={onClose}
            data-modal-initial-focus
          >
            Close
          </Button>
        </div>

        <div className="hr-modal-body space-y-3">
          {payload ? (
            <>
              <div className="hr-qr-frame hr-mobile-setup-qr">
                <canvas ref={canvasRef} className="block" aria-label="Dashboard setup QR code" />
              </div>

              <div className="rounded-md border border-border bg-muted/20 px-3 py-2 space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs text-muted-foreground">Dashboard address</span>
                  <Badge variant="outline">No credentials included</Badge>
                </div>
                <div className="font-mono text-xs break-all hr-selectable">{origin}</div>
                <div className="flex flex-wrap items-center gap-2">
                  <Button size="sm" variant="outline" onClick={copyAddress}>
                    Copy address
                  </Button>
                  {copyStatus ? (
                    <span className="text-xs text-muted-foreground" role="status">
                      {copyStatus}
                    </span>
                  ) : null}
                </div>
              </div>

              <div className="rounded-md border border-border bg-muted/20 p-3 text-sm space-y-2">
                <div className="font-medium">Standard Hermes connection</div>
                <p className="text-xs text-muted-foreground">
                  The app will verify this Dashboard, then use its advertised sign-in flow for
                  Chat, Manage, sessions, and standard voice.
                </p>
                <p className="text-xs text-muted-foreground">
                  This QR does not pair Hermes-Relay or enable Terminal, Bridge, device tools, or
                  Hermes-Relay sessions. Use <strong>Pair new device</strong> separately when a
                  reachable Hermes-Relay service is available.
                </p>
              </div>

              <p className="text-xs text-muted-foreground">
                The Dashboard address must be reachable from the phone. Sign-in still happens in
                the app; this QR contains no token, cookie, API key, or pairing code.
              </p>

              <div className="flex justify-end pt-1">
                <Button size="sm" onClick={onClose}>Done</Button>
              </div>
            </>
          ) : (
            <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              This page does not have a valid HTTP(S) Dashboard origin. Enter the Dashboard
              address manually in the mobile app.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
