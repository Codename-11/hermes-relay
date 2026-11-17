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
$launcherPath = Join-Path $homeDir 'relay-connector-hidden.vbs'
$launcherExecutable = Join-Path $env:WINDIR 'System32\wscript.exe'
if (-not (Test-Path -LiteralPath $launcherExecutable)) { throw 'Windows 后台脚本启动器不可用，已保留原安装。' }
$controlPath = Join-Path $homeDir 'connector-control.ps1'
$readyPath = Join-Path $homeDir 'connector-install-ready'
$backup = Join-Path $homeDir ('connector-backup-' + (Get-Date -Format yyyyMMdd-HHmmss) + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $binDir -Force | Out-Null
$lock = [IO.File]::Open((Join-Path $homeDir 'connector-install.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
Remove-Item -LiteralPath $readyPath -Force -ErrorAction SilentlyContinue
$oldEnvironment = @{}
foreach ($key in @('USERPROFILE','HOME','HERMES_RELAY_HOST_ACCESS_POLICY_PATH','HERMES_RELAY_TOKEN','HERMES_RELAY_URL','HERMES_RELAY_CODE')) {
    $oldEnvironment[$key] = [Environment]::GetEnvironmentVariable($key,'Process')
}
$hadCli = Test-Path -LiteralPath $cli
$replaced = $false
$authorized = $false
$newTaskRegistered = $false
$taskReady = $false
$trayStopped = $false
$runRemoved = $false
$oldDaemon = $null
$taskBackups = @()
$mutatedFiles = @('remote-sessions.json','desktop-host-access.json','desktop-device-id','run-connector.ps1','relay-connector-hidden.vbs','connector-control.ps1','Stop.cmd','Status.cmd')
$existingFiles = @{}
$tray = Join-Path $binDir 'hermes-relay-tray.exe'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runValue = $null
$utf8Bom = New-Object Text.UTF8Encoding($true)
function Quote-Literal([string]$value) { return "'" + $value.Replace("'","''") + "'" }
function Invoke-ConnectorHiddenCli {
    param([string]$Executable,[string]$Arguments,[int]$TimeoutMilliseconds = 10000)
    # Both console layers must be suppressed before process creation. The
    # watchdog's hidden PowerShell window alone does not hide a CUI executable.
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    $info.Arguments = $Arguments
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = New-Object Text.UTF8Encoding($false)
    $info.StandardErrorEncoding = New-Object Text.UTF8Encoding($false)
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw '后台连接命令未能启动。' }
        # Drain both streams concurrently; sequential ReadToEnd can deadlock
        # when either pipe fills. Never include captured stderr in errors.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            try { $process.Kill() } catch {}
            [void]$process.WaitForExit(2000)
            throw '后台连接命令超时。'
        }
        $readers = [Threading.Tasks.Task[]]@($stdout,$stderr)
        if (-not [Threading.Tasks.Task]::WaitAll($readers,2000)) { throw '后台连接命令输出未能完成。' }
        return [pscustomobject]@{ExitCode=$process.ExitCode;StandardOutput=$stdout.Result}
    } finally { $process.Dispose() }
}
function Get-ConnectorProcessSnapshot {
    param([int]$ProcessId)
    $process = $null
    try {
        $process = [Diagnostics.Process]::GetProcessById($ProcessId)
        if ($process.HasExited) { return @{State='gone'} }
        $image = [string]$process.MainModule.FileName
        if (-not $image) { return @{State='unknown'} }
        return @{State='alive';ExecutablePath=$image}
    } catch [ArgumentException] { return @{State='gone'} }
    catch { return @{State='unknown'} }
    finally { if ($process) { $process.Dispose() } }
}
function Get-ConnectorDaemonState {
    param([string]$StatusPath,[string]$Executable)
    # Never launch another CLI/PowerShell just to inspect this local process.
    # Missing initial state permits first startup; unreadable existing state
    # and inaccessible processes fail closed until the next watchdog tick.
    try { [void][IO.File]::GetAttributes($StatusPath) }
    catch [IO.FileNotFoundException] { return 'missing' }
    catch [IO.DirectoryNotFoundException] { return 'missing' }
    catch { return 'unknown' }
    try {
        $status = [IO.File]::ReadAllText($StatusPath) | ConvertFrom-Json
        $daemonProcessId = 0
        if ([string]$status.pid -notmatch '^[1-9][0-9]{0,9}$' -or
            -not [int]::TryParse([string]$status.pid,[ref]$daemonProcessId)) { return 'unknown' }
        $snapshot = Get-ConnectorProcessSnapshot $daemonProcessId
        if ($snapshot.State -eq 'gone') { return 'gone' }
        if ($snapshot.State -ne 'alive' -or -not $snapshot.ExecutablePath) { return 'unknown' }
        if ([IO.Path]::GetFullPath([string]$snapshot.ExecutablePath) -ine [IO.Path]::GetFullPath($Executable)) { return 'recycled' }
        return 'alive'
    } catch { return 'unknown' }
}
function Get-ConnectorLauncherContent([string]$PowerShellPath,[string]$ScriptPath) {
    # WScript is a GUI subsystem executable, so Task Scheduler never creates a
    # console before PowerShell has a chance to apply its WindowStyle switch.
    $command = '"'+$PowerShellPath+'" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$ScriptPath+'"'
    return 'Option Explicit'+"`r`n"+'Dim shell, result'+"`r`n"+'Set shell = CreateObject("WScript.Shell")'+"`r`n"+'result = shell.Run("'+$command.Replace('"','""')+'", 0, True)'+"`r`n"+'WScript.Quit result'+"`r`n"
}
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
function Replace-ConnectorBinary {
    $candidate = $cli+'.new'
    $deadline = (Get-Date).AddSeconds(10)
    while ($true) {
        if (-not (Test-Path -LiteralPath $candidate)) {
            # A consumed source is never replayed. Confirm exact bytes before
            # accepting a completed replacement whose API reported an error.
            if ((Test-Path -LiteralPath $cli) -and (Get-FileHash -LiteralPath $cli -Algorithm SHA256).Hash -ieq $manifest.sha256) { return }
            throw '候选安装文件已不存在，停止替换。原安装快照已保留，请重新运行安装包。'
        }
        if (@(Get-ConnectorBinaryProcesses).Count) { throw '此安装路径重新出现进程占用，已停止替换并保留原安装。' }
        try {
            if ($hadCli) { [IO.File]::Replace($candidate,$cli,[NullString]::Value) }
            else { [IO.File]::Move($candidate,$cli) }
            return
        } catch {
            $cause = $_.Exception
            while ($cause.InnerException) { $cause = $cause.InnerException }
            $code = $cause.HResult -band 0xffff
            if ($cause -isnot [IO.IOException] -or $code -notin @(32,33)) { throw }
            if ((Get-Date) -ge $deadline) { throw '安装文件持续被占用，已停止替换并保留原安装。请稍后重试。' }
            Start-Sleep -Milliseconds 250
        }
    }
}
function Get-ConnectorTaskManagementSddl([string]$sddl,[Security.Principal.SecurityIdentifier]$sid) {
    $descriptor = New-Object Security.AccessControl.RawSecurityDescriptor($sddl)
    if (-not $descriptor.DiscretionaryAcl) { throw '连接器自启任务的权限结构无效。' }
    $full = [int][Security.AccessControl.FileSystemRights]::FullControl
    foreach ($ace in $descriptor.DiscretionaryAcl) {
        if ($ace -is [Security.AccessControl.CommonAce] -and $ace.AceQualifier -eq [Security.AccessControl.AceQualifier]::AccessAllowed -and
            $ace.SecurityIdentifier -eq $sid -and ($ace.AccessMask -band $full) -eq $full) { return $sddl }
    }
    # Preserve every existing ACE, including SYSTEM, Administrators and denies.
    # Insert after explicit deny entries rather than overriding their ordering.
    $index = 0
    for ($i=0;$i -lt $descriptor.DiscretionaryAcl.Count;$i++) {
        $ace = $descriptor.DiscretionaryAcl[$i]
        if ($ace -is [Security.AccessControl.CommonAce] -and -not $ace.IsInherited -and $ace.AceQualifier -eq [Security.AccessControl.AceQualifier]::AccessDenied) { $index=$i+1 }
    }
    $entry = New-Object Security.AccessControl.CommonAce([Security.AccessControl.AceFlags]::None,[Security.AccessControl.AceQualifier]::AccessAllowed,$full,$sid,$false,$null)
    $descriptor.DiscretionaryAcl.InsertAce($index,$entry)
    return $descriptor.GetSddlForm([Security.AccessControl.AccessControlSections]::Access)
}
function Grant-ConnectorTaskManagement {
    # This helper may grant management only for this exact Limited task. It is
    # never used on the legacy tasks or on a task running with highest rights.
    if ($taskName -notmatch '^HermesRelay-Connector-[A-Fa-f0-9]{12}$') { throw '连接器自启任务名称无效。' }
    $scheduler = New-Object -ComObject 'Schedule.Service'
    $scheduler.Connect()
    $task = $scheduler.GetFolder('\').GetTask($taskName)
    $definition = $task.Definition
    if ($task.Path -ine ('\'+$taskName) -or $definition.Principal.RunLevel -ne 0 -or $definition.Principal.LogonType -ne 3) { throw '拒绝修改不属于当前普通用户连接器的任务权限。' }
    $principal = [string]$definition.Principal.UserId
    if ($principal -match '^S-1-') { $principalSid = (New-Object Security.Principal.SecurityIdentifier($principal)).Value }
    else { $principalSid = (New-Object Security.Principal.NTAccount($principal)).Translate([Security.Principal.SecurityIdentifier]).Value }
    if ($principalSid -cne $userSid) { throw '连接器自启任务所属用户不匹配。' }
    $expectedExecutable = $launcherExecutable
    $expectedArguments = '//B //Nologo "'+$launcherPath+'"'
    if ($definition.Actions.Count -ne 1) { throw '连接器自启任务操作不匹配。' }
    $action = $definition.Actions.Item(1)
    if ($action.Type -ne 0 -or [IO.Path]::GetFullPath([string]$action.Path) -ine [IO.Path]::GetFullPath($expectedExecutable) -or $action.Arguments -cne $expectedArguments) { throw '连接器自启任务路径不匹配。' }
    $sid = New-Object Security.Principal.SecurityIdentifier($userSid)
    $before = $task.GetSecurityDescriptor(4)
    $updated = Get-ConnectorTaskManagementSddl $before $sid
    if ($updated -cne $before) { $task.SetSecurityDescriptor($updated,0x10) }
    $readback = $task.GetSecurityDescriptor(4)
    if ((Get-ConnectorTaskManagementSddl $readback $sid) -cne $readback) { throw '无法验证当前用户的连接器任务管理权限。' }
}
function Start-ConnectorScheduledDaemon {
    # The installer keeps its exclusive lock to exclude another upgrade. This
    # credential-free marker authorizes only the fully prepared task's final
    # activation to proceed while that lock is held.
    [IO.File]::WriteAllText($readyPath,[string]$PID,[Text.Encoding]::ASCII)
    try {
        Enable-ScheduledTask -TaskName $taskName -TaskPath '\' | Out-Null
        Start-ScheduledTask -TaskName $taskName -TaskPath '\'
        $deadline = (Get-Date).AddSeconds(45)
        do {
            $status = Read-ConnectorStatus
            if ($status -and $status.alive -and $status.state -eq 'connected' -and $status.configured_url -ceq $remote -and
                $status.access_mode -eq 'full_access' -and -not $status.voice_url -and $status.privilege -eq 'user') { return $status }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
        throw '普通用户后台任务未能完成连接，正在检查恢复。'
    } finally { Remove-Item -LiteralPath $readyPath -Force -ErrorAction SilentlyContinue }
}
function Task-IsRetiredLegacy([string]$name,$task) {
    return $name -in @('HermesRelay-KeepAlive','HermesRelayDaemon') -and [string]$task.State -eq 'Disabled'
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
        # Retired legacy tasks retain their registration and administrator ACL.
        # A paused current hash task still needs its normal backup and update.
        if (Task-IsRetiredLegacy $name $task) { continue }
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
    Replace-ConnectorBinary
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
    try { $lock = [IO.File]::Open(__LOCK__,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch [IO.IOException] { if (-not (Test-Path -LiteralPath __READY__)) { exit 1 } }
    $state = Get-ConnectorDaemonState __STATUS__ $cli
    if ($state -eq 'alive') { exit 0 }
    if ($state -eq 'unknown') { exit 1 }
    $result = Invoke-ConnectorHiddenCli $cli ('daemon start --no-voice --remote '+__REMOTE__) 35000
    if ($result.ExitCode -ne 0) { exit 1 }
} catch { exit 1 } finally { if ($lock) { $lock.Dispose() } }
'@
    $wrapper = $wrapper.Replace('__PROFILE__',(Quote-Literal $profileRoot)).Replace('__POLICY__',(Quote-Literal $env:HERMES_RELAY_HOST_ACCESS_POLICY_PATH)).Replace('__CLI__',(Quote-Literal $cli)).Replace('__LOCK__',(Quote-Literal (Join-Path $homeDir 'connector-install.lock'))).Replace('__REMOTE__',(Quote-Literal $remote)).Replace('__READY__',(Quote-Literal $readyPath)).Replace('__STATUS__',(Quote-Literal (Join-Path $homeDir 'daemon-status.json')))
    foreach ($name in @('Get-ConnectorProcessSnapshot','Get-ConnectorDaemonState','Invoke-ConnectorHiddenCli')) {
        $wrapper = 'function '+$name+' {'+(Get-Item ('Function:\'+$name)).ScriptBlock.ToString()+"}`r`n"+$wrapper
    }
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
    $launcher = Get-ConnectorLauncherContent "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" $wrapperPath
    # UTF-16 preserves arbitrary Windows profile names in the VBS source.
    [IO.File]::WriteAllText($launcherPath,$launcher,[Text.Encoding]::Unicode)
    $action = New-ScheduledTaskAction -Execute $launcherExecutable -Argument ('//B //Nologo "'+$launcherPath+'"')
    $triggers = @((New-ScheduledTaskTrigger -AtLogOn -User $user),(New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)))
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 1)
    # Register disabled until the exact binary has authenticated successfully.
    $settings.Enabled = $false
    Register-ScheduledTask -TaskName $taskName -TaskPath '\' -Action $action -Trigger $triggers -Principal $principal -Settings $settings -Description 'Relay 后台连接器，登录自启及进程恢复' -Force | Out-Null
    $newTaskRegistered = $true
    Grant-ConnectorTaskManagement
    $taskReady = $true
    $status = Start-ConnectorScheduledDaemon
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
            if ((-not $status -or -not $status.alive -or $status.state -ne 'connected') -and $taskReady) { $status = Start-ConnectorScheduledDaemon }
            $status = Read-ConnectorStatus
            if ($status -and $status.alive -and $status.state -eq 'connected' -and $status.access_mode -eq 'full_access' -and -not $status.voice_url -and $status.privilege -eq 'user') {
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
    Remove-Item -LiteralPath $readyPath -Force -ErrorAction SilentlyContinue
    foreach ($key in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key,$oldEnvironment[$key],'Process') }
    $lock.Dispose()
}
