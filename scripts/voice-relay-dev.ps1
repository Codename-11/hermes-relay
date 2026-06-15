param(
    [string]$Provider = "xai_tts",
    [string]$Model = "",
    [string]$Voice = "",
    [int]$Port = 8767,
    [switch]$Stub,
    [switch]$NoCopyLabAuth
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if ($Stub) {
    $Provider = "stub"
}
if ($Provider -eq "grok" -or $Provider -eq "xai") {
    $Provider = "xai_tts"
}

$env:RELAY_VOICE_OUTPUT_ENABLED = "1"
$env:RELAY_VOICE_OUTPUT_PROVIDER = $Provider
$env:RELAY_PORT = "$Port"

if ($Model) {
    $env:RELAY_VOICE_OUTPUT_MODEL = $Model
} elseif ($Provider -eq "stub") {
    $env:RELAY_VOICE_OUTPUT_MODEL = "local-tone"
} elseif ($Provider -eq "xai_tts") {
    $env:RELAY_VOICE_OUTPUT_MODEL = "xai-tts"
} elseif ($Provider -eq "openai_tts") {
    $env:RELAY_VOICE_OUTPUT_MODEL = "gpt-4o-mini-tts"
}

if ($Voice) {
    $env:RELAY_VOICE_OUTPUT_VOICE = $Voice
} elseif ($Provider -eq "stub") {
    $env:RELAY_VOICE_OUTPUT_VOICE = "sine"
} elseif ($Provider -eq "xai_tts") {
    $env:RELAY_VOICE_OUTPUT_VOICE = "eve"
} elseif ($Provider -eq "openai_tts") {
    $env:RELAY_VOICE_OUTPUT_VOICE = "coral"
}
$env:RELAY_VOICE_OUTPUT_SAMPLE_RATE = "24000"
$env:RELAY_VOICE_OUTPUT_CODEC = "pcm"
$env:RELAY_VOICE_OUTPUT_OPTIMIZE_LATENCY = "1"
$env:RELAY_VOICE_OUTPUT_FALLBACK_ENABLED = "1"

$env:RELAY_REALTIME_VOICE_ENABLED = "1"
$env:RELAY_REALTIME_VOICE_PROVIDER = "xai_realtime"
$env:RELAY_REALTIME_VOICE_MODEL = "grok-voice-latest"
$env:RELAY_REALTIME_VOICE_VOICE = "eve"

$relayAuth = Join-Path $env:USERPROFILE ".hermes-relay\auth\xai-oauth.json"
$labAuth = Join-Path $repoRoot "voice-lab-runs\auth\xai-oauth.json"

if (($Provider -eq "xai_tts" -or $Provider -eq "xai_realtime") -and -not $NoCopyLabAuth) {
    if ((Test-Path $labAuth) -and -not (Test-Path $relayAuth)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $relayAuth) | Out-Null
        Copy-Item -LiteralPath $labAuth -Destination $relayAuth
        Write-Host "Copied lab xAI OAuth token to relay runtime auth: $relayAuth"
    }
    $env:RELAY_REALTIME_VOICE_XAI_OAUTH_PATH = $relayAuth
}

Write-Host "Voice output relay dev mode"
Write-Host "  output provider: $Provider"
Write-Host "  output model:    $env:RELAY_VOICE_OUTPUT_MODEL"
Write-Host "  output voice:    $env:RELAY_VOICE_OUTPUT_VOICE"
Write-Host "  realtime lab:    $env:RELAY_REALTIME_VOICE_PROVIDER / $env:RELAY_REALTIME_VOICE_MODEL / $env:RELAY_REALTIME_VOICE_VOICE"
Write-Host "  url:      ws://<this-machine>:$Port"
Write-Host ""
Write-Host "Pair the Android dev build to this relay, then use the main chat voice overlay."
Write-Host "Voice Settings controls /voice/output/*; Developer options > Realtime voice lab remains available for isolated provider-agent smoke tests."

python -m plugin.relay --no-ssl --port $Port
