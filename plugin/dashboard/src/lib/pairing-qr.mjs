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
