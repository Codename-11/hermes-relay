/**
 * Pairing payloads are dense. Keep modules on exact device-pixel boundaries and
 * preserve the QR-standard four-module quiet zone; CSS must not resize this canvas.
 */
export function pairingQrRenderOptions({ modules, availableWidth } = {}) {
  const scale = modules && Number.isFinite(availableWidth)
    ? Math.min(4, Math.floor(availableWidth / (modules + 8))) : 4;
  if (scale < 2) throw new Error("QR needs a wider window. Widen the Dashboard and create a new invite, or copy the full invite instead.");
  return {
    scale,
    margin: 4,
    // Match the CLI: certificate-bearing Secure Link invites can exceed the
    // largest medium-correction QR even though they fit at low correction.
    errorCorrectionLevel: "L",
  };
}

export function pairingQrErrorMessage(error) {
  if (error && /QR needs a wider window/.test(error.message)) return error.message;
  return /too big/i.test(error && error.message ? error.message : String(error))
    ? "This invite is too large for a QR code. Copy the full invite instead."
    : "The QR code could not be drawn. Copy the full invite instead.";
}
