param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('add', 'remove')]
  [string]$Action,

  [Parameter(Mandatory = $true)]
  [string]$Directory
)

$current = [Environment]::GetEnvironmentVariable('Path', 'User')
$parts = @()
if ($current) {
  $parts = @($current -split ';' | Where-Object { $_ -and $_ -ne $Directory })
}
if ($Action -eq 'add') {
  $parts += $Directory
}
[Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')
