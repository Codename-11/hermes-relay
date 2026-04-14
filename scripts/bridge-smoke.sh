#!/usr/bin/env bash
# scripts/bridge-smoke.sh — end-to-end smoke test for the hermes-relay bridge channel.
#
# Curls every documented bridge HTTP route via the unified relay (default
# localhost:8767) to verify the round-trip relay → WSS → phone → handler →
# response works after a code change or relay restart. Catches the silent-drop
# class of regression where Python registers a route but the Kotlin dispatcher's
# `when (path)` block has no branch for it (the v0.3.0 → v0.4.0 /open_app gap
# was the motivating case).
#
# Run from hermes-host (the box where ~/.hermes/.env lives and the relay binds
# localhost:8767). Reads the bearer token from .env by default — same ergonomic
# as the Python tools.
#
# Usage:
#   scripts/bridge-smoke.sh                   # full suite (destructive ON)
#   scripts/bridge-smoke.sh --no-destructive  # read-only paths only
#   scripts/bridge-smoke.sh --pair ABCDEF     # register pairing code first
#   scripts/bridge-smoke.sh --token <token>   # override .env token
#   scripts/bridge-smoke.sh --relay http://localhost:8767  # override URL
#   scripts/bridge-smoke.sh --filter open_app # only run matching tests
#   scripts/bridge-smoke.sh --quiet           # one line per result
#
# Exit codes:
#   0 = all tests passed
#   1 = one or more tests failed
#   2 = preflight failure (relay down, no token, phone not connected)

set -uo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
RELAY_URL="${ANDROID_BRIDGE_URL:-http://localhost:8767}"
TOKEN=""
DESTRUCTIVE=1
PAIR_CODE=""
QUIET=0
FILTER=""
BODY_FILE="/tmp/bridge-smoke-body.$$"
trap 'rm -f "$BODY_FILE"' EXIT

# ── Color codes (only emit if stdout is a tty) ────────────────────────────────
if [[ -t 1 ]]; then
    GREEN=$'\033[0;32m'
    RED=$'\033[0;31m'
    YELLOW=$'\033[0;33m'
    DIM=$'\033[2m'
    BOLD=$'\033[1m'
    NC=$'\033[0m'
else
    GREEN='' RED='' YELLOW='' DIM='' BOLD='' NC=''
fi

# ── Argument parsing ──────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-destructive) DESTRUCTIVE=0; shift ;;
        --destructive)    DESTRUCTIVE=1; shift ;;
        --pair)           PAIR_CODE="${2:?--pair needs a code}"; shift 2 ;;
        --token)          TOKEN="${2:?--token needs a value}"; shift 2 ;;
        --relay)          RELAY_URL="${2:?--relay needs a URL}"; shift 2 ;;
        --filter)         FILTER="${2:?--filter needs a pattern}"; shift 2 ;;
        --quiet|-q)       QUIET=1; shift ;;
        -h|--help)
            sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            printf "%sunknown arg:%s %s\n" "$RED" "$NC" "$1" >&2
            printf "  try: %s --help\n" "$0" >&2
            exit 2 ;;
    esac
done

# ── Load token from ~/.hermes/.env if not provided ────────────────────────────
if [[ -z "$TOKEN" && -f "$HOME/.hermes/.env" ]]; then
    TOKEN=$(grep -E '^ANDROID_BRIDGE_TOKEN=' "$HOME/.hermes/.env" \
        | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'")
fi

# ── Counters ──────────────────────────────────────────────────────────────────
PASS=0
FAIL=0
SKIP=0
FAILED_NAMES=()

# ── Helpers ───────────────────────────────────────────────────────────────────
hr() { printf '%s\n' "────────────────────────────────────────────────────────"; }

now_ms() {
    # GNU date supports %3N; fall back to python3 for portability.
    date +%s%3N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1000))'
}

# curl_path METHOD PATH [BODY_JSON]
# Echoes the HTTP status code; writes the response body to $BODY_FILE.
# curl -w '%{http_code}' always emits a 3-char code (000 on connect failure)
# so we don't need a `|| echo 000` fallback — that doubles the output.
curl_path() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-sS -o "$BODY_FILE" -w '%{http_code}' --max-time 10)
    [[ -n "$TOKEN" ]] && args+=(-H "Authorization: Bearer $TOKEN")
    if [[ "$method" == "POST" ]]; then
        # Body default — DO NOT use `${body:-{}}` here. Bash parses that as
        # `${body:-{}` (closing the parameter expansion at the first `}`)
        # plus a literal trailing `}`, so a non-empty body like
        # `{"key":"home"}` expands to `{"key":"home"}}` (broken JSON, two
        # closing braces). The relay's request.json() falls back to `{}` on
        # parse failure, the phone receives an empty body, and every POST
        # path returns `400 missing 'X' in body`. Silent and infuriating.
        # Discovered live during the v0.4 smoke test on 2026-04-14.
        local effective_body="$body"
        if [[ -z "$effective_body" ]]; then
            effective_body='{}'
        fi
        args+=(-X POST -H 'Content-Type: application/json' -d "$effective_body")
    fi
    # Suppress stderr; ignore curl's non-zero exit on connect failures —
    # the body file may be empty and the caller will see status 000.
    curl "${args[@]}" "${RELAY_URL}${path}" 2>/dev/null || true
}

# run NAME METHOD PATH [BODY_JSON] [EXPECTED_CODE]
run() {
    local name="$1" method="$2" path="$3" body="${4:-}" expected="${5:-200}"

    if [[ -n "$FILTER" && ! "$name" =~ $FILTER ]]; then
        SKIP=$((SKIP+1))
        return
    fi

    local t0=$(now_ms)
    local code; code=$(curl_path "$method" "$path" "$body")
    local t1=$(now_ms)
    local elapsed=$((t1-t0))
    local snippet=""
    if [[ -f "$BODY_FILE" ]]; then
        snippet=$(head -c 100 "$BODY_FILE" | tr -d '\n' | tr -s ' ')
    fi

    if [[ "$code" == "$expected" ]]; then
        PASS=$((PASS+1))
        if [[ $QUIET -eq 0 ]]; then
            printf "  %s✓%s %-26s %s %4sms  %s%s%s\n" \
                "$GREEN" "$NC" "$name" "$code" "$elapsed" "$DIM" "$snippet" "$NC"
        else
            printf "  %s✓%s %s\n" "$GREEN" "$NC" "$name"
        fi
    else
        FAIL=$((FAIL+1))
        FAILED_NAMES+=("$name")
        printf "  %s✗%s %-26s %s %4sms  %s\n" \
            "$RED" "$NC" "$name" "$code" "$elapsed" "$snippet"
    fi
}

# ── Optional: pre-register a pairing code ─────────────────────────────────────
if [[ -n "$PAIR_CODE" ]]; then
    printf "\n%sRegistering pairing code%s %s\n" "$BOLD" "$NC" "$PAIR_CODE"
    hr
    pair_resp=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' --max-time 5 \
        -X POST -H 'Content-Type: application/json' \
        -d "{\"code\":\"$PAIR_CODE\",\"device_name\":\"smoke-test\"}" \
        "${RELAY_URL}/pairing/register" 2>/dev/null || true)
    if [[ "$pair_resp" == "200" ]]; then
        printf "  %s✓%s code registered (rate-limit blocks cleared)\n" "$GREEN" "$NC"
        printf "  %sNow scan or enter the code on the phone, then continue.%s\n" "$DIM" "$NC"
    else
        printf "  %s✗%s /pairing/register returned %s: %s\n" "$RED" "$NC" \
            "$pair_resp" "$(head -c 200 "$BODY_FILE")"
        exit 2
    fi
fi

# ── Preflight ─────────────────────────────────────────────────────────────────
printf "\n%sPreflight%s  relay=%s  destructive=%s\n" \
    "$BOLD" "$NC" "$RELAY_URL" "$DESTRUCTIVE"
hr

health_code=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' --max-time 5 \
    "${RELAY_URL}/health" 2>/dev/null || true)
if [[ "$health_code" != "200" ]]; then
    printf "  %s✗%s relay /health returned %s — is hermes-relay.service running?\n" \
        "$RED" "$NC" "$health_code"
    printf "    %ssystemctl --user status hermes-relay%s\n" "$DIM" "$NC"
    exit 2
fi
printf "  %s✓%s relay /health 200\n" "$GREEN" "$NC"

if [[ -z "$TOKEN" ]]; then
    printf "  %s✗%s no ANDROID_BRIDGE_TOKEN in ~/.hermes/.env and no --token given\n" \
        "$RED" "$NC"
    printf "    Pair the phone first via /hermes-relay-pair or hermes-pair.\n"
    exit 2
fi
printf "  %s✓%s token loaded (%s...)\n" "$GREEN" "$NC" "${TOKEN:0:8}"

ping_code=$(curl_path GET /ping)
if [[ "$ping_code" != "200" ]]; then
    printf "  %s✗%s /ping returned %s — phone not connected over WSS?\n" \
        "$RED" "$NC" "$ping_code"
    printf "    %s\n" "$(head -c 200 "$BODY_FILE")"
    exit 2
fi
printf "  %s✓%s /ping (phone connected over WSS)\n" "$GREEN" "$NC"

# ── Read-only suite ───────────────────────────────────────────────────────────
# These are safe to run on every relay restart. None of them have visible
# side effects on the phone — at worst they read state.
printf "\n%sRead-only paths%s\n" "$BOLD" "$NC"
hr

run "POST /setup"           POST /setup           '{}'
run "GET  /current_app"     GET  /current_app
run "GET  /get_apps"        GET  /get_apps
run "GET  /apps (legacy)"   GET  /apps
run "GET  /screen"          GET  /screen
run "GET  /screen_hash"     GET  /screen_hash
run "POST /find_nodes"      POST /find_nodes      '{"clickable":true,"limit":5}'
run "GET  /clipboard"       GET  /clipboard
# /events is GET with query params (limit + since), not POST with body —
# matches the Python android_events tool's `_get("/events?limit=N&since=T")`
# call. Sending POST would get a 405 from the relay's HTTP router.
run "GET  /events"          GET  "/events?limit=10"
run "GET  /screenshot"      GET  /screenshot

# ── Destructive suite ─────────────────────────────────────────────────────────
# These have visible side effects on the phone but are still mostly harmless:
# launching Chrome, going home, going back, writing a known string to the
# clipboard. The clipboard round-trip doubles as an end-to-end correctness
# check (write a sentinel, read it back, compare).
if [[ $DESTRUCTIVE -eq 1 ]]; then
    printf "\n%sDestructive paths%s %s(visible side effects on phone)%s\n" \
        "$BOLD" "$NC" "$DIM" "$NC"
    hr

    run "POST /press_key home"  POST /press_key  '{"key":"home"}'
    sleep 0.5
    run "POST /open_app chrome" POST /open_app   '{"package":"com.android.chrome"}'
    sleep 1
    run "POST /press_key back"  POST /press_key  '{"key":"back"}'
    sleep 0.3
    run "POST /press_key home"  POST /press_key  '{"key":"home"}'

    # Round-trip clipboard test — write then read back.
    sentinel="hermes-smoke-$(date +%s)"
    if [[ -z "$FILTER" || "POST /clipboard write" =~ $FILTER ]]; then
        printf "  %s•%s clipboard round-trip with sentinel '%s'\n" "$DIM" "$NC" "$sentinel"
        run "POST /clipboard write" POST /clipboard "{\"text\":\"$sentinel\"}"
        sleep 0.3
        code=$(curl_path GET /clipboard)
        if [[ "$code" == "200" ]] && grep -q "$sentinel" "$BODY_FILE"; then
            printf "  %s✓%s %-26s round-trip text matches\n" \
                "$GREEN" "$NC" "GET  /clipboard echo"
            PASS=$((PASS+1))
        else
            printf "  %s✗%s %-26s expected '%s', got: %s\n" \
                "$RED" "$NC" "GET  /clipboard echo" "$sentinel" \
                "$(head -c 100 "$BODY_FILE")"
            FAIL=$((FAIL+1))
            FAILED_NAMES+=("GET  /clipboard echo")
        fi
    fi
fi

# ── Summary ───────────────────────────────────────────────────────────────────
printf "\n"
hr
total=$((PASS+FAIL))
if [[ $FAIL -eq 0 ]]; then
    printf "%s%sPASS%s  %d/%d  %s(skipped %d)%s\n" \
        "$GREEN" "$BOLD" "$NC" "$PASS" "$total" "$DIM" "$SKIP" "$NC"
    exit 0
else
    printf "%s%sFAIL%s  %d/%d failed  %s(skipped %d)%s\n" \
        "$RED" "$BOLD" "$NC" "$FAIL" "$total" "$DIM" "$SKIP" "$NC"
    printf "%sFailed:%s\n" "$BOLD" "$NC"
    for name in "${FAILED_NAMES[@]}"; do
        printf "  - %s\n" "$name"
    done
    printf "\n%sRe-run a single test:%s %s --filter '%s'\n" \
        "$DIM" "$NC" "$0" "${FAILED_NAMES[0]}"
    exit 1
fi
