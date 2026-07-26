[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$')]
    [string]$Version,

    [switch]$DeployPhone,

    [string]$DeviceSerial,

    [switch]$DryRun,

    [string]$Repository = 'Codename-11/hermes-relay'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $RepoRoot

function Invoke-Captured {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = & $Command @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Command $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine).Trim()
}

function Invoke-Streaming {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Start-GitHubWorkflow {
    param(
        [Parameter(Mandatory = $true)][string]$Workflow,
        [Parameter(Mandatory = $true)][string]$Ref,
        [Parameter(Mandatory = $true)][hashtable]$Inputs
    )

    $arguments = @('workflow', 'run', $Workflow, '--repo', $Repository, '--ref', $Ref)
    foreach ($entry in $Inputs.GetEnumerator() | Sort-Object Key) {
        $arguments += @('-f', "$($entry.Key)=$($entry.Value)")
    }
    $output = Invoke-Captured -Command 'gh' -Arguments $arguments
    if ($output -notmatch '/actions/runs/(?<id>\d+)') {
        throw "GitHub did not return a workflow run URL:`n$output"
    }
    return $Matches.id
}

function Wait-GitHubRun {
    param([Parameter(Mandatory = $true)][string]$RunId)

    Invoke-Streaming -Command 'gh' -Arguments @(
        'run', 'watch', $RunId,
        '--repo', $Repository,
        '--exit-status',
        '--interval', '15'
    )
}

function Require-CleanPreparedDev {
    $branch = Invoke-Captured -Command 'git' -Arguments @('branch', '--show-current')
    if ($branch -ne 'dev') {
        throw "Android publication must start from dev; current branch is $branch"
    }
    $status = Invoke-Captured -Command 'git' -Arguments @('status', '--porcelain')
    if ($status) {
        throw "Working tree is not clean:`n$status"
    }

    Invoke-Streaming -Command 'git' -Arguments @('fetch', 'origin', 'dev', 'main', '--tags')
    $head = Invoke-Captured -Command 'git' -Arguments @('rev-parse', 'HEAD')
    $originDev = Invoke-Captured -Command 'git' -Arguments @('rev-parse', 'origin/dev')
    if ($head -ne $originDev) {
        throw "Local dev ($head) does not match origin/dev ($originDev)"
    }

    $existingTag = & git ls-remote --tags origin "refs/tags/android-v$Version"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to query existing Android tags'
    }
    if ($existingTag) {
        throw "android-v$Version already exists"
    }
}

function Require-ReleaseMetadata {
    $versionsFile = Get-Content 'gradle/libs.versions.toml' -Raw
    if ($versionsFile -notmatch 'appVersionName\s*=\s*"(?<version>[^"]+)"') {
        throw 'Unable to read appVersionName from gradle/libs.versions.toml'
    }
    if ($Matches.version -ne $Version) {
        throw "Requested version $Version does not match appVersionName $($Matches.version)"
    }
    if ($versionsFile -notmatch 'appVersionCode\s*=\s*"(?<code>\d+)"') {
        throw 'Unable to read appVersionCode from gradle/libs.versions.toml'
    }
    $versionCode = $Matches.code
    if (-not (Select-String -Path 'CHANGELOG.md' -Pattern "^## \[(Android )?$([regex]::Escape($Version))\]" -Quiet)) {
        throw "CHANGELOG.md has no Android release heading for $Version"
    }
    return $versionCode
}

function Deploy-CompatiblePhoneBuild {
    param([Parameter(Mandatory = $true)][string]$VersionCode)

    $sdk = if ($env:ANDROID_HOME) {
        $env:ANDROID_HOME
    } else {
        Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $adb)) {
        throw "adb was not found at $adb"
    }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk

    Invoke-Streaming -Command '.\gradlew.bat' -Arguments @(
        ':app:assembleSideloadDebug',
        '--no-daemon',
        '--console=plain'
    )
    $apk = Get-ChildItem 'app\build\outputs\apk\sideload\debug' -Filter "*-$Version-sideload-debug.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $apk) {
        throw "No compatible sideload debug APK was produced for $Version"
    }

    $adbArguments = @()
    if ($DeviceSerial) {
        $adbArguments += @('-s', $DeviceSerial)
    }
    Invoke-Streaming -Command $adb -Arguments ($adbArguments + @('install', '-r', $apk.FullName))
    Invoke-Streaming -Command $adb -Arguments ($adbArguments + @(
        'shell', 'am', 'force-stop', 'com.axiomlabs.hermesrelay.sideload'
    ))
    Invoke-Streaming -Command $adb -Arguments ($adbArguments + @(
        'shell', 'am', 'start', '-n',
        'com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity'
    ))
    $package = Invoke-Captured -Command $adb -Arguments ($adbArguments + @(
        'shell', 'dumpsys', 'package', 'com.axiomlabs.hermesrelay.sideload'
    ))
    if ($package -notmatch "versionCode=$([regex]::Escape($VersionCode))\b" -or
        $package -notmatch "versionName=$([regex]::Escape($Version))-sideload\b") {
        throw "Phone package verification failed for version $Version / code $VersionCode"
    }
    Write-Host "Phone verified at $Version-sideload (versionCode $VersionCode)."
}

$versionCode = Require-ReleaseMetadata

if ($DryRun) {
    Write-Host "Dry run: Android $Version (versionCode $versionCode)"
    Write-Host '1. Require clean origin/dev and an unused Android tag.'
    Write-Host '2. Run and await Play Preflight from dev.'
    Write-Host '3. Open or reuse the dev-to-main release PR, await checks, and merge.'
    Write-Host '4. Verify the main tree equals the preflighted dev tree.'
    Write-Host '5. Run and await Approve Android Release from main.'
    Write-Host '6. Verify the immutable tag and GitHub release.'
    if ($DeployPhone) {
        Write-Host '7. Build/install the compatible sideload-debug APK without erasing phone data.'
    }
    exit 0
}

Invoke-Streaming -Command 'gh' -Arguments @('auth', 'status')
Require-CleanPreparedDev

$devCommit = Invoke-Captured -Command 'git' -Arguments @('rev-parse', 'HEAD')
$devTree = Invoke-Captured -Command 'git' -Arguments @('rev-parse', 'HEAD^{tree}')
Write-Host "Starting Android $Version from dev $devCommit (tree $devTree)."

$preflightRun = Start-GitHubWorkflow -Workflow 'play-preflight-android.yml' -Ref 'dev' -Inputs @{
    version = $Version
}
Write-Host "Play preflight run: https://github.com/$Repository/actions/runs/$preflightRun"
Wait-GitHubRun -RunId $preflightRun

$pullRequests = Invoke-Captured -Command 'gh' -Arguments @(
    'pr', 'list',
    '--repo', $Repository,
    '--base', 'main',
    '--head', 'dev',
    '--state', 'open',
    '--limit', '1',
    '--json', 'number,url,headRefOid'
) | ConvertFrom-Json
if (-not $pullRequests) {
    $body = @"
Promote the exact Play-preflighted Android $Version release tree from ``dev`` to ``main``.

- dev SHA: ``$devCommit``
- release tree: ``$devTree``
- Play preflight: https://github.com/$Repository/actions/runs/$preflightRun

Approval will verify this unchanged tree before creating ``android-v$Version``.
"@
    $prUrl = Invoke-Captured -Command 'gh' -Arguments @(
        'pr', 'create',
        '--repo', $Repository,
        '--base', 'main',
        '--head', 'dev',
        '--title', "release(android): promote android-v$Version",
        '--body', $body
    )
    $prNumber = [regex]::Match($prUrl, '/pull/(?<number>\d+)').Groups['number'].Value
} else {
    if ([string]$pullRequests[0].headRefOid -ne $devCommit) {
        throw "Existing release PR head $($pullRequests[0].headRefOid) does not match preflighted dev $devCommit"
    }
    $prNumber = [string]$pullRequests[0].number
    $prUrl = [string]$pullRequests[0].url
}
if (-not $prNumber) {
    throw "Unable to determine release PR number from $prUrl"
}
Write-Host "Release PR: $prUrl"
Invoke-Streaming -Command 'gh' -Arguments @(
    'pr', 'checks', $prNumber,
    '--repo', $Repository,
    '--watch',
    '--fail-fast'
)
$checkedHead = Invoke-Captured -Command 'gh' -Arguments @(
    'pr', 'view', $prNumber,
    '--repo', $Repository,
    '--json', 'headRefOid',
    '--jq', '.headRefOid'
)
if ($checkedHead -ne $devCommit) {
    throw "Release PR advanced to $checkedHead after preflight; rerun from the new dev tip"
}
Invoke-Streaming -Command 'gh' -Arguments @(
    'pr', 'merge', $prNumber,
    '--repo', $Repository,
    '--merge'
)

Invoke-Streaming -Command 'git' -Arguments @('fetch', 'origin', 'main', '--tags')
$mainCommit = Invoke-Captured -Command 'git' -Arguments @('rev-parse', 'origin/main')
$mainTree = Invoke-Captured -Command 'git' -Arguments @('rev-parse', 'origin/main^{tree}')
if ($mainTree -ne $devTree) {
    throw "Merged main tree $mainTree does not match preflighted dev tree $devTree"
}

$approvalRun = Start-GitHubWorkflow -Workflow 'approve-release-android.yml' -Ref 'main' -Inputs @{
    version = $Version
}
Write-Host "Approval run: https://github.com/$Repository/actions/runs/$approvalRun"
Wait-GitHubRun -RunId $approvalRun

Invoke-Streaming -Command 'git' -Arguments @('fetch', 'origin', '--tags')
$tagCommit = Invoke-Captured -Command 'git' -Arguments @('rev-parse', "android-v$Version")
if ($tagCommit -ne $mainCommit) {
    throw "android-v$Version points to $tagCommit instead of main $mainCommit"
}
$release = Invoke-Captured -Command 'gh' -Arguments @(
    'release', 'view', "android-v$Version",
    '--repo', $Repository,
    '--json', 'url,tagName,publishedAt'
) | ConvertFrom-Json
Write-Host "Published release: $($release.url)"

if ($DeployPhone) {
    Deploy-CompatiblePhoneBuild -VersionCode $versionCode
}
