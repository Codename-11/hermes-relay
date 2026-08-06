#!/usr/bin/env bash
# Hermes-Relay — dev helper scripts
# Usage: ./scripts/dev.sh <command>

set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-help}" in
  build)
    echo "Building sideload debug APK..."
    ./gradlew :app:assembleSideloadDebug --console=plain
    echo "APK: app/build/outputs/apk/sideload/debug/"
    ;;
  install)
    echo "Building and installing sideload debug to connected device..."
    ./gradlew :app:installSideloadDebug --console=plain
    echo "Launching app..."
    # Explicit FQCN: the sideload applicationId includes the flavor suffix but the
    # namespace (and thus the real class FQCN) is still com.hermesandroid.relay,
    # so the `.MainActivity` shorthand no longer resolves correctly.
    adb shell am start -n com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity
    ;;
  run)
    echo "Building, installing, and launching sideload debug..."
    ./gradlew :app:installSideloadDebug --console=plain
    # Explicit FQCN: the sideload applicationId includes the flavor suffix but the
    # namespace (and thus the real class FQCN) is still com.hermesandroid.relay,
    # so the `.MainActivity` shorthand no longer resolves correctly.
    adb shell am start -n com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity
    adb logcat -s HermesRelay:* --format=brief
    ;;
  compile)
    echo "Compiling sideload debug Kotlin..."
    ./gradlew :app:compileSideloadDebugKotlin --console=plain
    ;;
  test-one)
    if [ -z "${2:-}" ]; then
      echo "Usage: ./scripts/dev.sh test-one <fully-qualified-test-class-or-pattern>"
      exit 2
    fi
    echo "Running focused sideload test: $2"
    ./gradlew :app:testSideloadDebugUnitTest --tests "$2" --console=plain
    ;;
  install-fast)
    echo "Building arm64 sideload debug and installing to connected phone..."
    ./gradlew :app:installSideloadDebug -Phermes.devAbi=arm64-v8a --console=plain
    echo "Launching app..."
    adb shell am start -n com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity
    ;;
  test)
    echo "Running sideload debug unit tests..."
    ./gradlew :app:testSideloadDebugUnitTest --console=plain
    ;;
  lint)
    echo "Running sideload debug lint..."
    ./gradlew :app:lintSideloadDebug --console=plain
    ;;
  prepush)
    echo "Running Android pre-push checks..."
    python3 scripts/android-prepush.py
    ;;
  clean)
    echo "Cleaning build..."
    ./gradlew clean
    ;;
  devices)
    adb devices -l
    ;;
  wireless)
    if [ -z "${2:-}" ] || [ -z "${3:-}" ]; then
      echo "Usage: ./scripts/dev.sh wireless <ip:port> <pairing-code>"
      echo "  Get these from: Settings > Developer Options > Wireless debugging > Pair device"
      exit 1
    fi
    adb pair "$2" "$3"
    echo "Paired. Now connect with: adb connect <ip:port>"
    echo "  (Use the port from the main Wireless debugging screen, not the pairing port)"
    ;;
  relay)
    echo "Starting relay server..."
    python -m relay_server --no-ssl --log-level DEBUG
    ;;
  certs)
    echo "Generating dev TLS certificates..."
    "$(dirname "$0")/gen-dev-cert.sh" "${2:-localhost}"
    ;;
  relay-tls)
    echo "Starting relay server with dev TLS..."
    if [ ! -f certs/dev.crt ]; then
      echo "No dev certs found. Generating..."
      ./scripts/gen-dev-cert.sh localhost
    fi
    python -m relay_server --ssl-cert certs/dev.crt --ssl-key certs/dev.key --log-level DEBUG
    ;;
  help|*)
    echo "Hermes-Relay Dev Scripts"
    echo ""
    echo "  build      Build sideload debug APK"
    echo "  install    Build universal sideload + install"
    echo "  run        Build + install + launch + logcat"
    echo "  compile    Compile sideload debug Kotlin only"
    echo "  test-one   Run one test class or wildcard pattern"
    echo "  install-fast  Build arm64 only + install + launch"
    echo "  test       Run sideload debug unit tests"
    echo "  lint       Run lint checks"
    echo "  prepush    Run Android repository checks, lint, and focused CI tests"
    echo "  clean      Clean build outputs"
    echo "  devices    List connected devices"
    echo "  wireless   Pair for wireless debugging"
    echo "  relay      Start relay server (dev mode, no TLS)"
    echo "  certs      Generate dev TLS certificates"
    echo "  relay-tls  Start relay server with dev TLS"
    ;;
esac
