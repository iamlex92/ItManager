"""PowerShell run on a domain computer over WinRM, one script per action of the old Connect-DomainPC.ps1.

Every script prints a single JSON document (ConvertTo-Json) so the server never parses console text.
Output goes out as UTF-8; a tool that writes in the console code page (gpupdate) is read with CP866 first.
Nothing here takes text from the phone into the script except through the checked arguments
(a process name, a command typed on purpose in the console), which are passed base64-encoded.
"""

import base64

_PRELUDE = "$ErrorActionPreference = 'SilentlyContinue'\n[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n"

# The licences block: Windows (BIOS, registry), Office (last five characters), 1C (.lic files). Shared by the
# system information and the key inventory, like in the script.
_LICENCES = r"""
$biosKey = (Get-CimInstance -ClassName SoftwareLicensingService).OA3xOriginalProductKey
$spp = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SoftwareProtectionPlatform'
$regKey = (Get-ItemProperty -Path $spp).BackupProductKeyDefault
$officeInstalled = $false
$officeKeys = @()
$osppPaths = @(
  "${env:ProgramFiles}\Microsoft Office\Office16\ospp.vbs",
  "${env:ProgramFiles(x86)}\Microsoft Office\Office16\ospp.vbs",
  "${env:ProgramFiles}\Microsoft Office\Office15\ospp.vbs",
  "${env:ProgramFiles(x86)}\Microsoft Office\Office15\ospp.vbs"
)
$ospp = $osppPaths | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($ospp) {
  $officeInstalled = $true
  $status = cscript.exe //nologo $ospp /dstatus 2>$null
  foreach ($line in ($status | Where-Object { $_ -match 'Last 5 characters of installed product key:' })) {
    $officeKeys += ($line -split ': ')[1].Trim()
  }
}
$oneC = @()
$licPaths = @(
  "$env:ProgramData\1C\licenses\*.lic",
  "$env:ProgramData\1C\1Cv82\conf\*.lic",
  "$env:ProgramFiles\1cv8\conf\*.lic",
  "${env:ProgramFiles(x86)}\1cv8\conf\*.lic"
)
foreach ($f in (Get-ChildItem -Path $licPaths)) {
  $oneC += @{ path = $f.FullName; name = $f.Name; date = $f.LastWriteTime.ToString('yyyy-MM-dd') }
}
$user = (Get-CimInstance Win32_ComputerSystem).UserName
"""

_LICENCE_FIELDS = (
    "user = $user; windows_bios_key = $biosKey; windows_registry_key = $regKey; "
    "office_installed = $officeInstalled; office_keys = @($officeKeys); one_c = @($oneC)"
)

INFO = (
    _PRELUDE
    + _LICENCES
    + r"""
$os = Get-CimInstance Win32_OperatingSystem
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
$uptime = (Get-Date) - $os.LastBootUpTime
@{
  computer = $env:COMPUTERNAME; os = $os.Caption; architecture = $os.OSArchitecture; build = $os.Version;
  uptime_minutes = [int]$uptime.TotalMinutes; cpu = $cpu;
  ram_gb = [math]::Round($os.TotalVisibleMemorySize / 1MB, 2);
"""
    + "  "
    + _LICENCE_FIELDS
    + "\n} | ConvertTo-Json -Compress -Depth 4\n"
)

KEYS = (
    _PRELUDE
    + _LICENCES
    + r"""
$os = (Get-CimInstance Win32_OperatingSystem).Caption
@{ computer = $env:COMPUTERNAME; os = $os; """
    + _LICENCE_FIELDS
    + " } | ConvertTo-Json -Compress -Depth 4\n"
)

DISKS = (
    _PRELUDE
    + r"""
@(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object {
  @{ drive = $_.DeviceID; label = $_.VolumeName; free_gb = [math]::Round($_.FreeSpace / 1GB, 2);
     total_gb = [math]::Round($_.Size / 1GB, 2) }
}) | ConvertTo-Json -Compress -Depth 3
"""
)

FOLDERS = (
    _PRELUDE
    + r"""
$fso = New-Object -ComObject Scripting.FileSystemObject
$rows = @(Get-ChildItem -Path 'C:\' -Directory -Force | ForEach-Object {
  $size = 0
  try { $size = $fso.GetFolder($_.FullName).Size } catch {}
  @{ name = $_.Name; size_gb = [math]::Round($size / 1GB, 2) }
} | Where-Object { $_.size_gb -gt 0.1 } | Sort-Object { $_.size_gb } -Descending)
ConvertTo-Json -InputObject $rows -Compress -Depth 3
"""
)

PROCESSES = (
    _PRELUDE
    + r"""
$rows = @(Get-Process | Sort-Object WorkingSet -Descending | Select-Object -First 20 | ForEach-Object {
  @{ id = $_.Id; name = $_.ProcessName; ram_mb = [math]::Round($_.WorkingSet / 1MB, 1) }
})
ConvertTo-Json -InputObject $rows -Compress -Depth 3
"""
)

CLEANUP = (
    _PRELUDE
    + r"""
$before = (Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'").FreeSpace
Remove-Item -Path 'C:\Windows\Temp\*' -Recurse -Force
Get-ChildItem -Path 'C:\Users\*\AppData\Local\Temp\*' -Recurse -Force | Remove-Item -Recurse -Force
Stop-Service -Name wuauserv -Force
Start-Sleep -Seconds 2
Remove-Item -Path 'C:\Windows\SoftwareDistribution\Download\*' -Recurse -Force
Start-Service -Name wuauserv
$after = (Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'").FreeSpace
@{ freed_mb = [math]::Round(($after - $before) / 1MB, 1); free_gb = [math]::Round($after / 1GB, 2) } |
  ConvertTo-Json -Compress
"""
)

# gpupdate writes in the OEM code page: read it as CP866, hand it on as UTF-8.
GPUPDATE = (
    _PRELUDE
    + r"""
[Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding(866)
$out = (gpupdate /force 2>&1 | Out-String)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
@{ output = $out.Trim() } | ConvertTo-Json -Compress
"""
)

REBOOT = (
    _PRELUDE
    + r"""
Restart-Computer -Force
@{ output = 'Компьютер отправлен в перезагрузку' } | ConvertTo-Json -Compress
"""
)


def _b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def kill(process: str) -> str:
    """Stops every process with this name (already checked: letters, digits, dot, dash, underscore)."""
    return _PRELUDE + (
        f"$name = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{_b64(process)}'))\n"
        "$found = @(Get-Process -Name $name)\n"
        "$found | Stop-Process -Force\n"
        "@{ stopped = $found.Count; name = $name } | ConvertTo-Json -Compress\n"
    )


def command(text: str) -> str:
    """The console: one command typed by the administrator, its output as text. No state between commands."""
    return _PRELUDE + (
        f"$cmd = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{_b64(text)}'))\n"
        "$out = (Invoke-Expression $cmd 2>&1 | Out-String -Width 160)\n"
        "@{ output = $out.TrimEnd() } | ConvertTo-Json -Compress\n"
    )
