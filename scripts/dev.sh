#!/usr/bin/env bash
# Hermes Companion — dev helper scripts
# Usage: ./scripts/dev.sh <command>

set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-help}" in
  build)
    echo "Building debug APK..."
    ./gradlew assembleDebug
    echo "APK: app/build/outputs/apk/debug/app-debug.apk"
    ;;
  install)
    echo "Building and installing to connected device..."
    ./gradlew installDebug
    echo "Launching app..."
    adb shell am start -n com.hermesandroid.companion/.CompanionActivity
    ;;
  run)
    echo "Building, installing, and launching..."
    ./gradlew installDebug
    adb shell am start -n com.hermesandroid.companion/.CompanionActivity
    adb logcat -s HermesCompanion:* --format=brief
    ;;
  test)
    echo "Running unit tests..."
    ./gradlew test
    ;;
  lint)
    echo "Running lint..."
    ./gradlew lint
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
    echo "Starting companion relay..."
    python -m companion_relay --no-ssl --log-level DEBUG
    ;;
  help|*)
    echo "Hermes Companion Dev Scripts"
    echo ""
    echo "  build      Build debug APK"
    echo "  install    Build + install to connected device"
    echo "  run        Build + install + launch + logcat"
    echo "  test       Run unit tests"
    echo "  lint       Run lint checks"
    echo "  clean      Clean build outputs"
    echo "  devices    List connected devices"
    echo "  wireless   Pair for wireless debugging"
    echo "  relay      Start companion relay (dev mode)"
    ;;
esac
