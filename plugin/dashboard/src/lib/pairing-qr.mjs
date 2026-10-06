/**
 * Pairing payloads are dense. Keep modules on exact device-pixel boundaries and
 * preserve the QR-standard four-module quiet zone; CSS must not resize this canvas.
 */
export function pairingQrRenderOptions() {
  return {
    scale: 4,
    margin: 4,
    // Match the CLI: certificate-bearing Secure Link invites can exceed the
    // largest medium-correction QR even though they fit at low correction.
    errorCorrectionLevel: "L",
  };
}

export function pairingQrErrorMessage(error) {
  return /too big/i.test(error && error.message ? error.message : String(error))
    ? "This invite is too large for a QR code. Copy the full invite instead."
    : "The QR code could not be drawn. Copy the full invite instead.";
}
