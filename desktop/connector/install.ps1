param(
    [switch]$AcceptTools,
    # A separate profile root permits isolated native verification without using
    # the current user's device identity, credentials, daemon or startup task.
    [string]$HomeDirectory = $env:USERPROFILE
)
$ErrorActionPreference = 'Stop'
$stage = $PSScriptRoot
$setupPath = Join-Path $stage 'setup.json'
try {
    $setup = Get-Content -LiteralPath $setupPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $remote = [string]$setup.relay_url
    $uri = [Uri]$remote
} catch { throw '无法读取安装包的安全连接信息，请重新下载。' }
if ($setup.version -ne 1 -or $setup.invite -notmatch '^[A-Fa-f0-9]{64}$' -or
    $uri.Scheme -ne 'wss' -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
    $remote -match '[\x00-\x20\x7f"\\]') { throw '安装包的安全连接信息无效。' }
$remote = $uri.AbsoluteUri.TrimEnd('/')
$payload = Join-Path $stage 'hermes-relay.exe'
try { $manifest = Get-Content -LiteralPath (Join-Path $stage 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json }
catch { throw '无法读取安装文件校验信息，请重新下载。' }
if ($manifest.sha256 -notmatch '^[A-Fa-f0-9]{64}$' -or
    (Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash -ine $manifest.sha256) {
    throw '安装文件校验失败，请重新下载。'
}
Write-Host "Relay 后台连接器 → $($uri.Host)"
Write-Host '授权此服务器读写文件、执行命令、读取屏幕和控制键鼠、使用 USB、麦克风和摄像头。'
Write-Host '这些能力以后自动批准；Windows 自身的权限限制仍然生效。登录后自动运行，不启动 GUI 或语音会话。'
if (-not $AcceptTools) {
    $canPrompt = $false
    try { $canPrompt = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected } catch {}
    if (-not $canPrompt) { throw '非交互安装需要明确传入 -AcceptTools，以批准上述全部能力。' }
    if ((Read-Host '确认这是你的服务器并批准上述全部能力？输入 yes') -cne 'yes') { throw '已取消安装。' }
}
$profileRoot = [IO.Path]::GetFullPath($HomeDirectory)
if (-not [IO.Path]::IsPathRooted($HomeDirectory) -or $profileRoot -match '[\x00-\x1f"]') { throw '用户目录无效。' }
$homeDir = Join-Path $profileRoot '.hermes'
$binDir = Join-Path $homeDir 'bin'
$cli = Join-Path $binDir 'hermes-relay.exe'
$userIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$user = $userIdentity.Name
$userSid = $userIdentity.User.Value
$hashAlgorithm = [Security.Cryptography.SHA256]::Create()
try { $homeHash = ([BitConverter]::ToString($hashAlgorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($homeDir.ToLowerInvariant())))).Replace('-','').Substring(0,12) }
finally { $hashAlgorithm.Dispose() }
$taskName = 'HermesRelay-Connector-' + $homeHash
$wrapperPath = Join-Path $homeDir 'run-connector.ps1'
$controlPath = Join-Path $homeDir 'connector-control.ps1'
$backup = Join-Path $homeDir ('connector-backup-' + (Get-Date -Format yyyyMMdd-HHmmss) + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $binDir -Force | Out-Null
$lock = [IO.File]::Open((Join-Path $homeDir 'connector-install.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$oldEnvironment = @{}
foreach ($key in @('USERPROFILE','HOME','HERMES_RELAY_HOST_ACCESS_POLICY_PATH','HERMES_RELAY_TOKEN','HERMES_RELAY_URL','HERMES_RELAY_CODE')) {
    $oldEnvironment[$key] = [Environment]::GetEnvironmentVariable($key,'Process')
}
$hadCli = Test-Path -LiteralPath $cli
$replaced = $false
$authorized = $false
$newTaskRegistered = $false
$trayStopped = $false
$runRemoved = $false
$oldDaemon = $null
$taskBackups = @()
$mutatedFiles = @('remote-sessions.json','desktop-host-access.json','desktop-device-id','run-connector.ps1','connector-control.ps1','Stop.cmd','Status.cmd')
$existingFiles = @{}
$tray = Join-Path $binDir 'hermes-relay-tray.exe'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runValue = $null
$utf8Bom = New-Object Text.UTF8Encoding($true)
function Quote-Literal([string]$value) { return "'" + $value.Replace("'","''") + "'" }
function Read-ConnectorStatus {
    if (-not (Test-Path -LiteralPath $cli)) { return $null }
    # No running daemon is a normal status exit code, not an install failure.
    $text = (& $cli daemon status --json 2>$null | Out-String)
    if ($text.Trim().StartsWith('{')) { return ($text | ConvertFrom-Json) }
    return $null
}
function Stop-ConnectorDaemon {
    $status = Read-ConnectorStatus
    if ($status -and $status.alive) {
        $daemonPid = [int]$status.pid
        & $cli daemon stop 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '无法停止现有后台连接，已保留原安装。' }
        $deadline = (Get-Date).AddSeconds(10)
        while ((Get-Process -Id $daemonPid -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
        if (Get-Process -Id $daemonPid -ErrorAction SilentlyContinue) { throw '原后台连接未退出，已保留原安装。' }
    }
}
function Get-ConnectorBinaryProcesses {
    try {
        # Query the image name narrowly, then require the complete executable
        # path. Another installation or isolated profile is never selected.
        $image = [IO.Path]::GetFileName($cli).Replace("'","''")
        return @(Get-CimInstance -ClassName Win32_Process -Filter ("Name='"+$image+"'") -ErrorAction Stop | Where-Object {
            $_.ExecutablePath -and [IO.Path]::GetFullPath([string]$_.ExecutablePath) -ieq $cli
        })
    } catch { throw '无法检查此安装路径的残留进程，已保留原安装。' }
}
function Stop-RemainingConnectorProcesses {
    $remaining = @(Get-ConnectorBinaryProcesses)
    if (-not $remaining.Count) { return }
    # Keep only identifiers and a role label, never credential-bearing argv.
    $records = @($remaining | ForEach-Object { @{pid=[int]$_.ProcessId;role='same-installation-cli'} })
    ConvertTo-Json -InputObject $records -Depth 3 | Set-Content -LiteralPath (Join-Path $backup 'remaining-relay-processes.json') -Encoding UTF8
    foreach ($item in $remaining) {
        $process = $null
        try {
            $process = Get-Process -Id ([int]$item.ProcessId) -ErrorAction SilentlyContinue
            if (-not $process) { continue }
            # Holding this OS handle prevents PID reuse between path validation
            # and termination. Recheck the live image before stopping anything.
            [void]$process.Handle
            if ($process.HasExited) { continue }
            if ([IO.Path]::GetFullPath([string]$process.Path) -ine $cli) { continue }
            $process.Kill()
            if (-not $process.WaitForExit(10000)) { throw 'process did not exit' }
        } catch { throw '无法关闭此安装路径的残留进程，已保留原安装。请先结束该连接器后重试。' }
        finally { if ($process) { $process.Dispose() } }
    }
    if (@(Get-ConnectorBinaryProcesses).Count) { throw '此安装路径仍有进程占用，已保留原安装。请先结束该连接器后重试。' }
}
function Task-IsOwned($task) {
    $owner = [string]$task.Principal.UserId
    if ($owner -ine $user -and $owner -ine $userSid -and $owner -ine $env:USERNAME) { return $false }
    foreach ($action in $task.Actions) {
        $execute = ([string]$action.Execute).Trim('"')
        $arguments = [string]$action.Arguments
        if ($execute -ieq $cli -and $arguments -match '(^|\s)daemon(\s|$)') { continue }
        # Recognize only the precise launch files in this profile, never an
        # unrelated task that happens to use a historical Hermes task name.
        $knownScript = @($wrapperPath,(Join-Path $homeDir 'relay-connector-hidden.vbs'),(Join-Path $homeDir 'relay-keepalive-hidden.vbs'),(Join-Path $homeDir 'relay-autostart.vbs'))
        $matched = $false
        foreach ($script in $knownScript) {
            if ($arguments -imatch ('(?:^|\s)"?' + [regex]::Escape($script) + '"?(?:\s|$)') -and
                [IO.Path]::GetFileName($execute) -imatch '^(wscript|powershell)(\.exe)?$') { $matched = $true }
        }
        if (-not $matched) { return $false }
    }
    return @($task.Actions).Count -gt 0
}
try {
    $env:USERPROFILE = $profileRoot
    $env:HOME = $profileRoot
    $env:HERMES_RELAY_HOST_ACCESS_POLICY_PATH = Join-Path $homeDir 'desktop-host-access.json'
    $env:HERMES_RELAY_TOKEN = $null
    $env:HERMES_RELAY_URL = $null
    $env:HERMES_RELAY_CODE = $null
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    # Backups include bearer credentials: restrict this new backup directory to
    # the current user and Windows SYSTEM, without changing existing data ACLs.
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($sid in @($userSid,'S-1-5-18')) {
        $identity = New-Object Security.Principal.SecurityIdentifier($sid)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $backup -AclObject $acl
    foreach ($name in @($taskName,'HermesRelay-KeepAlive','HermesRelayDaemon')) {
        $task = Get-ScheduledTask -TaskName $name -TaskPath '\' -ErrorAction SilentlyContinue
        if (-not $task) { continue }
        if (-not (Task-IsOwned $task)) {
            if ($name -eq $taskName) { throw '同名自启任务不属于此安装；请检查任务冲突。' }
            continue
        }
        Export-ScheduledTask -TaskName $name -TaskPath '\' | Set-Content -LiteralPath (Join-Path $backup ($name+'.xml')) -Encoding Unicode
        $taskBackups += $name
        Disable-ScheduledTask -TaskName $name -TaskPath '\' | Out-Null
        Stop-ScheduledTask -TaskName $name -TaskPath '\' -ErrorAction SilentlyContinue
    }
    # An existing tray can restart the daemon while it is being replaced.
    $trayProcess = @(Get-Process -Name 'hermes-relay-tray' -ErrorAction SilentlyContinue | Where-Object { $_.Path -ieq $tray })
    if ($trayProcess.Count) { $trayStopped = $true; $trayProcess | Stop-Process; Start-Sleep -Milliseconds 300 }
    # Only this profile's exact tray startup entry is migrated.
    if ($profileRoot -ieq [string]$oldEnvironment.USERPROFILE) {
        $entry = Get-ItemProperty -LiteralPath $runKey -Name HermesRelay -ErrorAction SilentlyContinue
        if ($entry) {
            $candidate = [string]$entry.HermesRelay
            if ($candidate -imatch ('^\s*"?' + [regex]::Escape($tray) + '"?(\s|$)')) {
                $runValue = $candidate
                $runValue | Set-Content -LiteralPath (Join-Path $backup 'tray-autostart.txt') -Encoding Unicode
                Remove-ItemProperty -LiteralPath $runKey -Name HermesRelay
                $runRemoved = $true
            }
        }
    }
    $oldDaemon = Read-ConnectorStatus
    Stop-ConnectorDaemon
    foreach ($name in $mutatedFiles) {
        $file = Join-Path $homeDir $name
        $existingFiles[$name] = Test-Path -LiteralPath $file
        if ($existingFiles[$name]) { Copy-Item -LiteralPath $file -Destination (Join-Path $backup $name) }
    }
    if ($hadCli) { Copy-Item -LiteralPath $cli -Destination (Join-Path $backup 'hermes-relay-before.exe') }
    Stop-RemainingConnectorProcesses
    # Copy beside the old binary first; Windows rejects replacement if any old
    # executable owner remains. No validation websocket is opened until stop.
    Copy-Item -LiteralPath $payload -Destination ($cli+'.new') -Force
    if ($hadCli) { [IO.File]::Replace(($cli+'.new'),$cli,[NullString]::Value) }
    else { [IO.File]::Move(($cli+'.new'),$cli) }
    $replaced = $true
    & $cli connector --setup $setupPath --grant-tools
    if ($LASTEXITCODE -ne 0) { throw '连接授权失败。检查网络或从下载页领取新的安装包。' }
    # The CLI returns zero only after permanent desktop-only credentials and
    # full access have been saved and read back. Old tokens may now be revoked.
    $authorized = $true
    $wrapper = @'
$ErrorActionPreference = 'Stop'
$env:USERPROFILE = __PROFILE__
$env:HOME = __PROFILE__
$env:HERMES_RELAY_HOST_ACCESS_POLICY_PATH = __POLICY__
$env:HERMES_RELAY_TOKEN = $null
$env:HERMES_RELAY_URL = $null
$cli = __CLI__
$lock = $null
try {
    $lock = [IO.File]::Open(__LOCK__,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $raw = (& $cli daemon status --json 2>$null | Out-String)
    $status = $null
    if ($raw.Trim().StartsWith('{')) { $status = $raw | ConvertFrom-Json }
    if ($status -and $status.alive) { exit 0 }
    & $cli daemon start --no-voice --remote __REMOTE__ *> $null
} catch { exit 1 } finally { if ($lock) { $lock.Dispose() } }
'@
    $wrapper = $wrapper.Replace('__PROFILE__',(Quote-Literal $profileRoot)).Replace('__POLICY__',(Quote-Literal $env:HERMES_RELAY_HOST_ACCESS_POLICY_PATH)).Replace('__CLI__',(Quote-Literal $cli)).Replace('__LOCK__',(Quote-Literal (Join-Path $homeDir 'connector-install.lock'))).Replace('__REMOTE__',(Quote-Literal $remote))
    [IO.File]::WriteAllText($wrapperPath,$wrapper.Replace("`r`n","`n").Replace("`n","`r`n"),$utf8Bom)
    $control = @'
param([ValidateSet('Status','Stop')][string]$Action = 'Status')
$ErrorActionPreference = 'Stop'
$env:USERPROFILE = __PROFILE__
$env:HOME = __PROFILE__
$env:HERMES_RELAY_HOST_ACCESS_POLICY_PATH = __POLICY__
$cli = __CLI__
if ($Action -eq 'Stop') {
    Disable-ScheduledTask -TaskName __TASK__ -TaskPath '\' | Out-Null
    Stop-ScheduledTask -TaskName __TASK__ -TaskPath '\' -ErrorAction SilentlyContinue
    & $cli daemon stop
    Write-Host '自动重连已暂停。重新运行安装器可恢复。'
} else {
    Get-ScheduledTask -TaskName __TASK__ -TaskPath '\' | Select-Object TaskName,State | Format-Table
    & $cli daemon status
}
'@
    $control = $control.Replace('__PROFILE__',(Quote-Literal $profileRoot)).Replace('__POLICY__',(Quote-Literal $env:HERMES_RELAY_HOST_ACCESS_POLICY_PATH)).Replace('__CLI__',(Quote-Literal $cli)).Replace('__TASK__',(Quote-Literal $taskName))
    [IO.File]::WriteAllText($controlPath,$control.Replace("`r`n","`n").Replace("`n","`r`n"),$utf8Bom)
    Copy-Item -LiteralPath (Join-Path $stage 'Stop.cmd') -Destination $homeDir -Force
    Copy-Item -LiteralPath (Join-Path $stage 'Status.cmd') -Destination $homeDir -Force
    $action = New-ScheduledTaskAction -Execute "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument ('-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$wrapperPath+'"')
    $triggers = @((New-ScheduledTaskTrigger -AtLogOn -User $user),(New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)))
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 1)
    # Register disabled until the exact binary has authenticated successfully.
    $settings.Enabled = $false
    Register-ScheduledTask -TaskName $taskName -TaskPath '\' -Action $action -Trigger $triggers -Principal $principal -Settings $settings -Description 'Relay 后台连接器，登录自启及进程恢复' -Force | Out-Null
    $newTaskRegistered = $true
    & $cli daemon start --no-voice --remote $remote
    $startExit = $LASTEXITCODE
    $status = Read-ConnectorStatus
    if ($startExit -ne 0 -or -not $status.alive -or $status.state -ne 'connected' -or $status.configured_url -cne $remote -or $status.access_mode -ne 'full_access' -or $status.voice_url) {
        throw '后台进程未能完成安全连接，正在检查恢复。'
    }
    Enable-ScheduledTask -TaskName $taskName -TaskPath '\' | Out-Null
    # One-use invitation is removed only after credentials and startup succeed.
    Remove-Item -LiteralPath $setupPath -Force
    Write-Host '安装完成。以后自动后台连接，无需配对码，也没有托盘弹窗。'
    Write-Host ('查看状态：' + (Join-Path $homeDir 'Status.cmd'))
    Write-Host ('暂停连接：' + (Join-Path $homeDir 'Stop.cmd'))
    Write-Host ('旧版、配对记录和原自启任务备份：' + $backup)
} catch {
    $failure = $_
    $rollbackErrors = @()
    if ($newTaskRegistered) {
        try {
            Disable-ScheduledTask -TaskName $taskName -TaskPath '\' | Out-Null
            Stop-ScheduledTask -TaskName $taskName -TaskPath '\' -ErrorAction SilentlyContinue
            if (-not $authorized) { Unregister-ScheduledTask -TaskName $taskName -TaskPath '\' -Confirm:$false }
        } catch { $rollbackErrors += '新自启任务' }
    }
    if ($authorized) {
        # Authorization may have replaced a revoked/legacy server token. Keep
        # this verified binary, device identity, credentials and full-access
        # policy together; restoring old credentials would guarantee failure.
        try {
            $attempt = Join-Path $backup 'failed-attempt'
            New-Item -ItemType Directory -Path $attempt -Force | Out-Null
            foreach ($name in $mutatedFiles) {
                $file = Join-Path $homeDir $name
                if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination (Join-Path $attempt $name) -Force }
            }
        } catch { $rollbackErrors += '新授权快照' }
        try {
            $status = Read-ConnectorStatus
            if (-not $status -or -not $status.alive) { & $cli daemon start --no-voice --remote $remote | Out-Null }
            $status = Read-ConnectorStatus
            if ($status -and $status.alive -and $status.state -eq 'connected' -and $status.access_mode -eq 'full_access' -and -not $status.voice_url) {
                Write-Warning '安装未完成；已保留新授权并恢复新版后台连接。自启任务仍需检查或重新安装。'
            } else {
                Write-Warning '安装未完成；已保留新版程序及新授权，后台连接尚未恢复。请检查网络后重新运行安装器。'
            }
        } catch { Write-Warning '安装未完成；已保留新版程序及新授权，请使用备份协助恢复。' }
        if ($rollbackErrors.Count) { Write-Warning ('需要检查：'+($rollbackErrors -join '、')) }
        Write-Warning ('原安装和本次授权快照：'+$backup)
        throw $failure
    }
    if ($replaced) {
        try {
            Stop-ConnectorDaemon
            # Retain the failed attempt's credentials too, for recovery after a
            # consumed one-use invite, before restoring the original snapshots.
            $attempt = Join-Path $backup 'failed-attempt'
            New-Item -ItemType Directory -Path $attempt -Force | Out-Null
            foreach ($name in $mutatedFiles) {
                $file = Join-Path $homeDir $name
                if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination (Join-Path $attempt $name) -Force }
                if ($existingFiles[$name]) { Copy-Item -LiteralPath (Join-Path $backup $name) -Destination $file -Force }
                else { Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue }
            }
            if ($hadCli) { Copy-Item -LiteralPath (Join-Path $backup 'hermes-relay-before.exe') -Destination $cli -Force }
            else { Remove-Item -LiteralPath $cli -Force -ErrorAction SilentlyContinue }
        } catch { $rollbackErrors += '程序和授权文件' }
    }
    # Restore the old daemon's own host and voice setting, never the new setup's
    # host. Restart only if it was running before installation.
    if ($hadCli -and $oldDaemon -and $oldDaemon.alive) {
        try {
            $restoreArgs = @('daemon','start','--remote',([string]$oldDaemon.configured_url))
            if (-not $oldDaemon.configured_url) { $restoreArgs[-1] = [string]$oldDaemon.url }
            if (-not $oldDaemon.voice_url) { $restoreArgs += '--no-voice' }
            & $cli @restoreArgs | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'restore failed' }
        } catch { $rollbackErrors += '原后台连接' }
    }
    foreach ($name in $taskBackups) {
        try { Register-ScheduledTask -TaskName $name -TaskPath '\' -Xml (Get-Content -LiteralPath (Join-Path $backup ($name+'.xml')) -Raw) -Force | Out-Null }
        catch { $rollbackErrors += ('原自启任务 '+$name) }
    }
    if ($runRemoved) { try { Set-ItemProperty -LiteralPath $runKey -Name HermesRelay -Value $runValue } catch { $rollbackErrors += '原托盘自启' } }
    if ($trayStopped) { try { Start-Process -FilePath $tray } catch { $rollbackErrors += '原托盘进程' } }
    if ($rollbackErrors.Count) { Write-Warning ('请从备份检查恢复：'+($rollbackErrors -join '、')+'；'+$backup) }
    throw $failure
} finally {
    Remove-Item -LiteralPath ($cli+'.new') -Force -ErrorAction SilentlyContinue
    foreach ($key in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key,$oldEnvironment[$key],'Process') }
    $lock.Dispose()
}
