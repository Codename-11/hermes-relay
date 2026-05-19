<#
.SYNOPSIS
Run the standalone Hermes voice lab CLI with test-friendly defaults.

.EXAMPLE
.\scripts\voice-lab.ps1 -Mode doctor

.EXAMPLE
.\scripts\voice-lab.ps1 -Provider stub

.EXAMPLE
.\scripts\voice-lab.ps1 -Provider openai -OpenAIKey "sk-..." -Text "Hermes voice lab online."

.EXAMPLE
.\scripts\voice-lab.ps1 -Mode auth -Provider grok

.EXAMPLE
.\scripts\voice-lab.ps1 -Mode tui -Provider grok

.EXAMPLE
.\scripts\voice-lab.ps1 -Provider grok -XAIKey "xai-..." -Text "Hermes voice lab online."

.EXAMPLE
.\scripts\voice-lab.ps1 -Mode eval -Providers "xai_realtime,openai_realtime,elevenlabs_tts,stub"
#>
[CmdletBinding()]
param(
    [ValidateSet("run", "test", "bench", "providers", "doctor", "auth", "tui", "stt", "s2s", "speech-to-speech", "eval", "eval-approach")]
    [string] $Mode = "run",

    [ValidateSet("auto", "stub", "openai", "openai_realtime", "grok", "xai", "xai_realtime", "elevenlabs", "elevenlabs_tts")]
    [string] $Provider = "auto",

    [string] $Providers = "xai_realtime,openai_realtime,elevenlabs_tts,stub",

    [string] $Text = "Hermes voice lab online. Testing realtime speech output.",

    [Alias("Script")]
    [string] $ScriptFile,

    [string] $InputAudio,

    [string] $ExpectedText,

    [string] $OutputDir = "voice-lab-runs",

    [string] $EventDir,

    [string] $OpenAIKey,

    [Alias("GrokKey")]
    [string] $XAIKey,

    [string] $ElevenLabsKey,

    [string] $Model = "gpt-realtime-2",

    [string] $Voice = "marin",

    [string] $XAIModel = "grok-voice-latest",

    [string] $XAIVoice = "eve",

    [string] $ElevenLabsModel = "eleven_flash_v2_5",

    [string] $ElevenLabsVoice = "JBFqnCBsd6RMkjVDRZzb",

    [int] $ToolDelayMs = 750,

    [string] $Tone = "warm",

    [double] $Intensity = 0.45,

    [string] $Pace = "normal",

    [string] $Style = "natural",

    [ValidateSet("auto", "on", "off")]
    [string] $Visual = "on",

    [switch] $Json,

    [switch] $Play,

    [switch] $NoEventLog,

    [string[]] $ProviderOption = @(),

    [string] $Python = "python"
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$script:VoiceLabExitCode = 0

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string] $Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return (Join-Path $RepoRoot $Path)
}

function Test-OpenAIKeyAvailable {
    if (-not [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
        return $true
    }
    if (-not [string]::IsNullOrWhiteSpace($env:VOICE_TOOLS_OPENAI_KEY)) {
        return $true
    }

    $labHome = if ($env:VOICE_LAB_HOME) { $env:VOICE_LAB_HOME } else { Join-Path $RepoRoot "voice-lab-runs" }
    $envPath = Join-Path $labHome ".env"
    if (-not (Test-Path -LiteralPath $envPath)) {
        return $false
    }

    foreach ($line in Get-Content -LiteralPath $envPath) {
        if ($line -match '^\s*(OPENAI_API_KEY|VOICE_TOOLS_OPENAI_KEY)\s*=\s*(.+?)\s*$') {
            $value = $Matches[2].Trim().Trim('"').Trim("'")
            if (-not [string]::IsNullOrWhiteSpace($value) -and -not $value.StartsWith("#")) {
                return $true
            }
        }
    }
    return $false
}

function Test-XAIKeyAvailable {
    foreach ($name in @(
        "VOICE_TOOLS_XAI_KEY",
        "XAI_API_KEY",
        "GROK_API_KEY",
        "XAI_REALTIME_CLIENT_SECRET",
        "XAI_EPHEMERAL_TOKEN",
        "VOICE_LAB_XAI_OAUTH_ACCESS_TOKEN"
    )) {
        if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            return $true
        }
    }

    $labHome = if ($env:VOICE_LAB_HOME) { $env:VOICE_LAB_HOME } else { Join-Path $RepoRoot "voice-lab-runs" }
    $envPath = Join-Path $labHome ".env"
    if (Test-Path -LiteralPath $envPath) {
        foreach ($line in Get-Content -LiteralPath $envPath) {
            if ($line -match '^\s*(VOICE_TOOLS_XAI_KEY|XAI_API_KEY|GROK_API_KEY|XAI_REALTIME_CLIENT_SECRET|XAI_EPHEMERAL_TOKEN|VOICE_LAB_XAI_OAUTH_ACCESS_TOKEN)\s*=\s*(.+?)\s*$') {
                $value = $Matches[2].Trim().Trim('"').Trim("'")
                if (-not [string]::IsNullOrWhiteSpace($value) -and -not $value.StartsWith("#")) {
                    return $true
                }
            }
        }
    }
    if (Test-VoiceLabXAIOAuthAvailable) {
        return $true
    }
    return $false
}

function Test-ElevenLabsKeyAvailable {
    if (-not [string]::IsNullOrWhiteSpace($env:ELEVENLABS_API_KEY)) {
        return $true
    }
    if (-not [string]::IsNullOrWhiteSpace($env:VOICE_TOOLS_ELEVENLABS_KEY)) {
        return $true
    }

    $labHome = if ($env:VOICE_LAB_HOME) { $env:VOICE_LAB_HOME } else { Join-Path $RepoRoot "voice-lab-runs" }
    $envPath = Join-Path $labHome ".env"
    if (-not (Test-Path -LiteralPath $envPath)) {
        return $false
    }

    foreach ($line in Get-Content -LiteralPath $envPath) {
        if ($line -match '^\s*(ELEVENLABS_API_KEY|VOICE_TOOLS_ELEVENLABS_KEY)\s*=\s*(.+?)\s*$') {
            $value = $Matches[2].Trim().Trim('"').Trim("'")
            if (-not [string]::IsNullOrWhiteSpace($value) -and -not $value.StartsWith("#")) {
                return $true
            }
        }
    }
    return $false
}

function Test-VoiceLabXAIOAuthAvailable {
    $labHome = if ($env:VOICE_LAB_HOME) { $env:VOICE_LAB_HOME } else { Join-Path $RepoRoot "voice-lab-runs" }
    $authPath = Join-Path (Join-Path $labHome "auth") "xai-oauth.json"
    if (-not (Test-Path -LiteralPath $authPath)) {
        return $false
    }

    try {
        $auth = Get-Content -Raw -LiteralPath $authPath | ConvertFrom-Json
    }
    catch {
        return $false
    }

    if ($auth.tokens -and -not [string]::IsNullOrWhiteSpace($auth.tokens.access_token)) {
        return $true
    }
    if (-not [string]::IsNullOrWhiteSpace($auth.access_token)) {
        return $true
    }
    return $false
}

function Resolve-ProviderId {
    if ($Provider -eq "openai") {
        return "openai_realtime"
    }
    if ($Provider -eq "grok" -or $Provider -eq "xai") {
        return "xai_realtime"
    }
    if ($Provider -eq "elevenlabs") {
        return "elevenlabs_tts"
    }
    if ($Provider -eq "auto") {
        if (Test-XAIKeyAvailable) {
            return "xai_realtime"
        }
        if (Test-OpenAIKeyAvailable) {
            return "openai_realtime"
        }
        if (Test-ElevenLabsKeyAvailable) {
            return "elevenlabs_tts"
        }
        return "stub"
    }
    return $Provider
}

function Add-ProviderOptions {
    param(
        [Parameter(Mandatory = $true)][string] $ProviderId,
        [Parameter(Mandatory = $true)][string[]] $BaseArgs
    )

    $args = @($BaseArgs)
    if ($ProviderId -eq "openai_realtime") {
        $args += @("--provider-option", "model=$Model")
        $args += @("--provider-option", "voice=$Voice")
    }
    if ($ProviderId -eq "xai_realtime") {
        $args += @("--provider-option", "model=$XAIModel")
        $args += @("--provider-option", "voice=$XAIVoice")
    }
    if ($ProviderId -eq "elevenlabs_tts") {
        $args += @("--provider-option", "model=$ElevenLabsModel")
        $args += @("--provider-option", "voice=$ElevenLabsVoice")
    }
    foreach ($option in $ProviderOption) {
        $args += @("--provider-option", $option)
    }
    return $args
}

function Invoke-VoiceLab {
    param([Parameter(Mandatory = $true)][string[]] $CliArgs)

    Push-Location $RepoRoot
    try {
        & $Python -m plugin.voice_lab @CliArgs
        if ($null -ne $LASTEXITCODE) {
            $script:VoiceLabExitCode = $LASTEXITCODE
            return
        }
        $script:VoiceLabExitCode = 0
    }
    finally {
        Pop-Location
    }
}

if (-not [string]::IsNullOrWhiteSpace($OpenAIKey)) {
    $env:VOICE_TOOLS_OPENAI_KEY = $OpenAIKey
}
if (-not [string]::IsNullOrWhiteSpace($XAIKey)) {
    $env:VOICE_TOOLS_XAI_KEY = $XAIKey
}
if (-not [string]::IsNullOrWhiteSpace($ElevenLabsKey)) {
    $env:VOICE_TOOLS_ELEVENLABS_KEY = $ElevenLabsKey
}

$outputRoot = Resolve-RepoPath $OutputDir
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
if ([string]::IsNullOrWhiteSpace($env:VOICE_LAB_HOME)) {
    $env:VOICE_LAB_HOME = Resolve-RepoPath "voice-lab-runs"
}
New-Item -ItemType Directory -Force -Path $env:VOICE_LAB_HOME | Out-Null

if ($Mode -eq "doctor") {
    $doctorArgs = @("doctor", "--output-dir", $outputRoot)
    if ($Json) {
        $doctorArgs += "--json"
    }
    Invoke-VoiceLab $doctorArgs
    exit $script:VoiceLabExitCode
}

if ($Mode -eq "providers") {
    $providerArgs = @("providers")
    if ($Json) {
        $providerArgs += "--json"
    }
    Invoke-VoiceLab $providerArgs
    exit $script:VoiceLabExitCode
}

if ($Mode -eq "auth") {
    if ($Provider -ne "auto" -and $Provider -ne "grok" -and $Provider -ne "xai" -and $Provider -ne "xai_realtime") {
        [Console]::Error.WriteLine("Auth mode currently supports -Provider grok only.")
        exit 1
    }
    $authProvider = "grok"
    $authArgs = @("auth", "--provider", $authProvider)
    if ($Json) {
        $authArgs += "--json"
    }
    Invoke-VoiceLab $authArgs
    exit $script:VoiceLabExitCode
}

if ($Mode -eq "eval" -or $Mode -eq "eval-approach") {
    $evalEventDir = if ($EventDir) { Resolve-RepoPath $EventDir } else { Join-Path $outputRoot "events" }
    $evalArgs = @(
        "eval-approach",
        "--providers", $Providers,
        "--output-dir", $outputRoot,
        "--event-dir", $evalEventDir,
        "--tool-delay-ms", ([string] $ToolDelayMs),
        "--visual", $Visual
    )
    foreach ($option in $ProviderOption) {
        $evalArgs += @("--provider-option", $option)
    }
    if ($Json) {
        $evalArgs += "--json"
    }
    Invoke-VoiceLab $evalArgs
    exit $script:VoiceLabExitCode
}

$providerId = Resolve-ProviderId
Write-Host "voice-lab provider: $providerId"

if ($providerId -eq "openai_realtime" -and -not (Test-OpenAIKeyAvailable)) {
    [Console]::Error.WriteLine(
        "OpenAI voice lab runs require OPENAI_API_KEY or VOICE_TOOLS_OPENAI_KEY. " +
        "Set an environment variable, add it to VOICE_LAB_HOME\\.env, or pass -OpenAIKey."
    )
    exit 1
}
if ($providerId -eq "xai_realtime" -and -not (Test-XAIKeyAvailable)) {
    [Console]::Error.WriteLine(
        "Grok voice lab runs require xAI auth: XAI_API_KEY/VOICE_TOOLS_XAI_KEY, " +
        "an ephemeral xAI token, or the lab-owned OAuth store. For SuperGrok/Premium+ " +
        "subscription auth, run '.\\scripts\\voice-lab.ps1 -Mode auth -Provider grok'."
    )
    exit 1
}
if ($providerId -eq "elevenlabs_tts" -and -not (Test-ElevenLabsKeyAvailable)) {
    [Console]::Error.WriteLine(
        "ElevenLabs voice lab runs require ELEVENLABS_API_KEY or VOICE_TOOLS_ELEVENLABS_KEY. " +
        "Set an environment variable, add it to VOICE_LAB_HOME\\.env, or pass -ElevenLabsKey."
    )
    exit 1
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$safeProvider = $providerId -replace '[^A-Za-z0-9_.-]+', '-'
$audioPath = Join-Path $outputRoot "$stamp-$safeProvider.wav"
$eventPath = Join-Path $outputRoot "$stamp-$safeProvider.jsonl"

if ($Mode -eq "tui") {
    $tuiEventDir = if ($EventDir) { Resolve-RepoPath $EventDir } else { Join-Path $outputRoot "events" }
    $args = @(
        "tui",
        "--provider", $providerId,
        "--tone", $Tone,
        "--intensity", ([string] $Intensity),
        "--pace", $Pace,
        "--style", $Style,
        "--visual", $Visual,
        "--output-dir", $outputRoot
    )
    if (-not $NoEventLog) {
        $args += @("--event-dir", $tuiEventDir)
    }
    else {
        $args += "--no-event-log"
    }
    if ($Json) {
        $args += "--json"
    }
    if ($Play) {
        $args += "--play"
    }
    $args = Add-ProviderOptions -ProviderId $providerId -BaseArgs $args
    Invoke-VoiceLab $args
    exit $script:VoiceLabExitCode
}

if ($Mode -eq "test") {
    $args = @(
        "test", $providerId,
        "--text", $Text,
        "--tone", $Tone,
        "--intensity", ([string] $Intensity),
        "--pace", $Pace,
        "--style", $Style,
        "--visual", $Visual,
        "--output", $audioPath
    )
    if (-not $NoEventLog) {
        $args += @("--event-log", $eventPath)
    }
    if ($Json) {
        $args += "--json"
    }
    if ($Play) {
        $args += "--play"
    }
    $args = Add-ProviderOptions -ProviderId $providerId -BaseArgs $args
    Invoke-VoiceLab $args
    exit $script:VoiceLabExitCode
}

if ($Mode -eq "bench") {
    if ([string]::IsNullOrWhiteSpace($ScriptFile)) {
        [Console]::Error.WriteLine("Bench mode requires -ScriptFile, for example -ScriptFile .\scripts\voice-lab-phrases.txt")
        exit 1
    }

    $benchEventDir = if ($EventDir) { Resolve-RepoPath $EventDir } else { Join-Path $outputRoot "events" }
    $args = @(
        "bench",
        "--providers", $providerId,
        "--script", (Resolve-RepoPath $ScriptFile),
        "--output-dir", $outputRoot,
        "--tone", $Tone,
        "--intensity", ([string] $Intensity),
        "--pace", $Pace,
        "--style", $Style,
        "--visual", $Visual
    )
    if (-not $NoEventLog) {
        $args += @("--event-dir", $benchEventDir)
    }
    if ($Json) {
        $args += "--json"
    }
    $args = Add-ProviderOptions -ProviderId $providerId -BaseArgs $args
    Invoke-VoiceLab $args
    exit $script:VoiceLabExitCode
}

if ($Mode -eq "stt") {
    if ([string]::IsNullOrWhiteSpace($InputAudio)) {
        [Console]::Error.WriteLine("STT mode requires -InputAudio, for example -InputAudio .\voice-lab-runs\sample.wav")
        exit 1
    }

    $args = @(
        "stt",
        "--provider", $providerId,
        "--input", (Resolve-RepoPath $InputAudio),
        "--visual", $Visual
    )
    if (-not [string]::IsNullOrWhiteSpace($ExpectedText)) {
        $args += @("--expected-text", $ExpectedText)
    }
    if (-not $NoEventLog) {
        $args += @("--event-log", $eventPath)
    }
    if ($Json) {
        $args += "--json"
    }
    $args = Add-ProviderOptions -ProviderId $providerId -BaseArgs $args
    Invoke-VoiceLab $args
    exit $script:VoiceLabExitCode
}

if ($Mode -eq "s2s" -or $Mode -eq "speech-to-speech") {
    if ([string]::IsNullOrWhiteSpace($InputAudio)) {
        [Console]::Error.WriteLine("Speech-to-speech mode requires -InputAudio, for example -InputAudio .\voice-lab-runs\sample.wav")
        exit 1
    }

    $args = @(
        "speech-to-speech",
        "--provider", $providerId,
        "--input", (Resolve-RepoPath $InputAudio),
        "--text", $Text,
        "--tone", $Tone,
        "--intensity", ([string] $Intensity),
        "--pace", $Pace,
        "--style", $Style,
        "--visual", $Visual,
        "--output", $audioPath
    )
    if (-not $NoEventLog) {
        $args += @("--event-log", $eventPath)
    }
    if ($Json) {
        $args += "--json"
    }
    if ($Play) {
        $args += "--play"
    }
    $args = Add-ProviderOptions -ProviderId $providerId -BaseArgs $args
    Invoke-VoiceLab $args
    exit $script:VoiceLabExitCode
}

$args = @(
    "realtime-text",
    "--provider", $providerId,
    "--text", $Text,
    "--tone", $Tone,
    "--intensity", ([string] $Intensity),
    "--pace", $Pace,
    "--style", $Style,
    "--visual", $Visual,
    "--output", $audioPath
)
if (-not $NoEventLog) {
    $args += @("--event-log", $eventPath)
}
if ($Json) {
    $args += "--json"
}
if ($Play) {
    $args += "--play"
}
$args = Add-ProviderOptions -ProviderId $providerId -BaseArgs $args
Invoke-VoiceLab $args
exit $script:VoiceLabExitCode
