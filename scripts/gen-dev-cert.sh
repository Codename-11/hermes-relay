#!/usr/bin/env bash
# Generate a self-signed TLS certificate for dev use with the companion relay.
# Outputs: certs/dev.crt and certs/dev.key
# Usage: ./scripts/gen-dev-cert.sh [hostname]

set -euo pipefail

HOSTNAME="${1:-localhost}"
CERT_DIR="$(dirname "$0")/../certs"
DAYS=365

mkdir -p "$CERT_DIR"

echo "Generating self-signed cert for: $HOSTNAME"

openssl req -x509 -newkey rsa:2048 \
    -keyout "$CERT_DIR/dev.key" \
    -out "$CERT_DIR/dev.crt" \
    -days "$DAYS" \
    -nodes \
    -subj "/CN=$HOSTNAME" \
    -addext "subjectAltName=DNS:$HOSTNAME,DNS:localhost,IP:127.0.0.1,IP:10.0.2.2"

echo ""
echo "Certificate generated:"
echo "  Cert: $CERT_DIR/dev.crt"
echo "  Key:  $CERT_DIR/dev.key"
echo "  Valid for: $DAYS days"
echo "  SANs: $HOSTNAME, localhost, 127.0.0.1, 10.0.2.2 (Android emulator host)"
echo ""
echo "Start relay with TLS:"
echo "  python -m companion_relay --ssl-cert certs/dev.crt --ssl-key certs/dev.key"
echo ""
echo "To trust on Android emulator:"
echo "  adb push certs/dev.crt /sdcard/dev.crt"
echo "  Then: Settings > Security > Install from storage"
