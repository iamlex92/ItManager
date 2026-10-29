# ruff: noqa: E501  -- PowerShell text: its lines are kept whole so the scripts stay readable as PowerShell.
"""PowerShell run over WinRM on the Group Policy host (a domain controller or an admin server with GPMC).

Each script prints one JSON document. Arguments from the API are checked by backend.py and passed in as one
base64-encoded JSON object ($a), never spliced into the script text.
"""

import base64
import json
from typing import Any

_PRELUDE = (
    "$ErrorActionPreference = 'Stop'\n"
    "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n"
    "Import-Module GroupPolicy\n"
    "Import-Module ActiveDirectory\n"
)

# Every place a GPO is linked to, from the gPLink attribute of the domain, OUs and sites:
# [LDAP://cn={GUID},cn=policies,cn=system,DC=...;N], N: 0 enabled, 1 disabled, 2 enforced, 3 both.
# GPMC's link order 1 is the last entry of the attribute.
_LINKS = r"""
function Get-AllLinks {
  $roots = @((Get-ADDomain).DistinguishedName)
  $objs = Get-ADObject -LDAPFilter '(gPLink=*)' -Properties gPLink, gPOptions, CanonicalName
  $out = @()
  foreach ($o in $objs) {
    $entries = [regex]::Matches($o.gPLink, '\[LDAP://cn=\{([0-9a-fA-F-]+)\},[^;]+;(\d)\]')
    $n = $entries.Count
    for ($i = 0; $i -lt $n; $i++) {
      $m = $entries[$i]
      $opt = [int]$m.Groups[2].Value
      $out += [pscustomobject]@{
        gpo = $m.Groups[1].Value.ToLower()
        target = $o.DistinguishedName
        target_name = $o.CanonicalName
        enabled = -not ($opt -band 1)
        enforced = [bool]($opt -band 2)
        order = $n - $i
        blocks_inheritance = ($o.gPOptions -eq 1)
      }
    }
  }
  $out
}
"""


def _args(values: dict[str, Any]) -> str:
    raw = base64.b64encode(json.dumps(values, ensure_ascii=False).encode("utf-8")).decode("ascii")
    return f"$a = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{raw}')) | ConvertFrom-Json\n"


LIST = _PRELUDE + _LINKS + r"""
$links = @(Get-AllLinks)
$gpos = foreach ($g in Get-GPO -All) {
  $id = $g.Id.Guid.ToLower()
  [pscustomobject]@{
    id = $id
    name = $g.DisplayName
    status = $g.GpoStatus.ToString()
    description = $g.Description
    owner = $g.Owner
    created = $g.CreationTime.ToString('o')
    modified = $g.ModificationTime.ToString('o')
    wmi_filter = $(if ($g.WmiFilter) { $g.WmiFilter.Name } else { $null })
    links = @($links | Where-Object { $_.gpo -eq $id })
  }
}
@{ gpos = @($gpos) } | ConvertTo-Json -Depth 6 -Compress
"""

OUS = _PRELUDE + r"""
$d = Get-ADDomain
$ous = @([pscustomobject]@{ dn = $d.DistinguishedName; name = $d.DNSRoot })
$ous += Get-ADOrganizationalUnit -Filter * -Properties CanonicalName |
  Sort-Object CanonicalName | ForEach-Object { [pscustomobject]@{ dn = $_.DistinguishedName; name = $_.CanonicalName } }
@{ ous = $ous } | ConvertTo-Json -Depth 4 -Compress
"""

# The registry part of a GPO (Administrative Templates and registry preferences under the policy keys):
# Get-GPRegistryValue lists one key level at a time, so the policy roots are walked down.
_REGISTRY = r"""
function Walk-Gpo($guid, $key, $depth) {
  if ($depth -gt 12) { return }
  $items = @()
  try { $items = @(Get-GPRegistryValue -Guid $guid -Key $key -ErrorAction Stop) } catch { return }
  foreach ($i in $items) {
    if ($i.HasValue) {
      $v = $i.Value
      if ($v -is [array]) { $v = ($v -join "`n") }
      elseif ($v -is [byte[]]) { $v = [BitConverter]::ToString($v) }
      [pscustomobject]@{ key = $i.FullKeyPath; name = $i.ValueName; type = $i.Type.ToString();
                         value = [string]$v; state = $i.PolicyState.ToString() }
    } elseif ($i.FullKeyPath -and $i.FullKeyPath -ne $key) {
      Walk-Gpo $guid $i.FullKeyPath ($depth + 1)
    }
  }
}
"""

_ROOTS = (
    "HKLM\\Software\\Policies",
    "HKCU\\Software\\Policies",
    "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies",
    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies",
)


def detail(gpo_id: str) -> str:
    return _PRELUDE + _LINKS + _REGISTRY + _args({"id": gpo_id, "roots": list(_ROOTS)}) + r"""
$g = Get-GPO -Guid $a.id
$id = $g.Id.Guid.ToLower()
$registry = foreach ($r in $a.roots) { Walk-Gpo $a.id $r 0 }
$filter = Get-GPPermission -Guid $a.id -All | Where-Object { $_.Permission -eq 'GpoApply' } |
  ForEach-Object { [pscustomobject]@{ name = $_.Trustee.Name; type = $_.Trustee.SidType.ToString() } }
@{
  gpo = [pscustomobject]@{
    id = $id; name = $g.DisplayName; status = $g.GpoStatus.ToString(); description = $g.Description
    owner = $g.Owner; created = $g.CreationTime.ToString('o'); modified = $g.ModificationTime.ToString('o')
    wmi_filter = $(if ($g.WmiFilter) { $g.WmiFilter.Name } else { $null })
    links = @(Get-AllLinks | Where-Object { $_.gpo -eq $id })
  }
  registry = @($registry)
  security_filter = @($filter)
} | ConvertTo-Json -Depth 6 -Compress
"""


def report(gpo_id: str) -> str:
    return _PRELUDE + _args({"id": gpo_id}) + r"""
@{ html = (Get-GPOReport -Guid $a.id -ReportType Html) } | ConvertTo-Json -Compress
"""


def scope(gpo_id: str) -> str:
    """Computers the GPO reaches: under its enabled links (blocked inheritance stops unenforced ones) and passing
    its security filter. The same rules Windows applies, short of WMI filters and loopback."""
    return _PRELUDE + _LINKS + _args({"id": gpo_id}) + r"""
$id = $a.id.ToLower()
$all = @(Get-AllLinks)
$blockers = @($all | Where-Object { $_.blocks_inheritance } | Select-Object -ExpandProperty target -Unique)
$mine = @($all | Where-Object { $_.gpo -eq $id -and $_.enabled })
$found = @{}
foreach ($l in $mine) {
  if ($l.target -notmatch '^(OU|DC)=') { continue }
  foreach ($c in Get-ADComputer -SearchBase $l.target -SearchScope Subtree -Filter * -Properties DNSHostName, Enabled) {
    $dn = $c.DistinguishedName
    $blocked = $false
    if (-not $l.enforced) {
      foreach ($b in $blockers) { if ($b -ne $l.target -and $dn.EndsWith(',' + $b) -and $b.EndsWith(',' + $l.target)) { $blocked = $true } }
    }
    if (-not $blocked) { $found[$dn] = $c }
  }
}
$perm = @(Get-GPPermission -Guid $a.id -All | Where-Object { $_.Permission -eq 'GpoApply' })
$everyone = @($perm | Where-Object { $_.Trustee.Sid.Value -in @('S-1-5-11', 'S-1-1-0', 'S-1-5-15') -or $_.Trustee.Name -eq 'Domain Computers' }).Count -gt 0
$allowed = @{}
if (-not $everyone) {
  foreach ($p in $perm) {
    if ($p.Trustee.SidType.ToString() -eq 'Computer') { $allowed[$p.Trustee.Name.TrimEnd('$').ToLower()] = $true }
    elseif ($p.Trustee.SidType.ToString() -match 'Group') {
      foreach ($m in Get-ADGroupMember -Identity $p.Trustee.Sid.Value -Recursive | Where-Object objectClass -eq 'computer') {
        $allowed[$m.Name.ToLower()] = $true
      }
    }
  }
}
$computers = foreach ($c in $found.Values) {
  if ($everyone -or $allowed.ContainsKey($c.Name.ToLower())) {
    [pscustomobject]@{ name = $c.Name; dns = $c.DNSHostName; ou = ($c.DistinguishedName -replace '^CN=[^,]+,', ''); enabled = $c.Enabled }
  }
}
@{ computers = @($computers | Sort-Object name); filtered = (-not $everyone) } | ConvertTo-Json -Depth 4 -Compress
"""


def _done(what: str) -> str:
    return f"@{{ ok = $true; done = '{what}' }} | ConvertTo-Json -Compress\n"


def _backup(path: str) -> str:
    """Backup-GPO before a change, when GPO_BACKUP_PATH is set: the previous state can be restored in GPMC."""
    if not path:
        return ""
    return "Backup-GPO -Guid $a.id -Path $a.backup -Comment ('NetAdmin: перед ' + $a.what) | Out-Null\n"


def set_status(gpo_id: str, status: str, backup: str) -> str:
    return _PRELUDE + _args({"id": gpo_id, "status": status, "backup": backup, "what": "сменой состояния"}) + (
        _backup(backup) + "(Get-GPO -Guid $a.id).GpoStatus = $a.status\n" + _done("status")
    )


def link_add(gpo_id: str, target: str, enforced: bool) -> str:
    return _PRELUDE + _args({"id": gpo_id, "target": target, "enforced": enforced}) + (
        "New-GPLink -Guid $a.id -Target $a.target -LinkEnabled Yes "
        "-Enforced $(if ($a.enforced) { 'Yes' } else { 'No' }) | Out-Null\n" + _done("link_add")
    )


def link_set(gpo_id: str, target: str, enabled: bool | None, enforced: bool | None) -> str:
    parts = ""
    if enabled is not None:
        parts += "Set-GPLink -Guid $a.id -Target $a.target -LinkEnabled $(if ($a.enabled) { 'Yes' } else { 'No' }) | Out-Null\n"
    if enforced is not None:
        parts += "Set-GPLink -Guid $a.id -Target $a.target -Enforced $(if ($a.enforced) { 'Yes' } else { 'No' }) | Out-Null\n"
    return _PRELUDE + _args({"id": gpo_id, "target": target, "enabled": enabled, "enforced": enforced}) + parts + _done("link_set")


def link_remove(gpo_id: str, target: str) -> str:
    return _PRELUDE + _args({"id": gpo_id, "target": target}) + (
        "Remove-GPLink -Guid $a.id -Target $a.target | Out-Null\n" + _done("link_remove")
    )


def registry_set(gpo_id: str, key: str, name: str, kind: str, value: Any, backup: str) -> str:
    args = {"id": gpo_id, "key": key, "name": name, "type": kind, "value": value, "backup": backup,
            "what": "изменением параметра"}  # fmt: skip
    return _PRELUDE + _args(args) + _backup(backup) + r"""
$v = $a.value
if ($a.type -eq 'DWord') { $v = [uint32]$a.value }
elseif ($a.type -eq 'QWord') { $v = [uint64]$a.value }
elseif ($a.type -eq 'MultiString') { $v = [string[]]($a.value -split "`n") }
Set-GPRegistryValue -Guid $a.id -Key $a.key -ValueName $a.name -Type $a.type -Value $v | Out-Null
""" + _done("registry_set")


def registry_remove(gpo_id: str, key: str, name: str, backup: str) -> str:
    args = {"id": gpo_id, "key": key, "name": name, "backup": backup, "what": "удалением параметра"}
    return _PRELUDE + _args(args) + _backup(backup) + (
        "Remove-GPRegistryValue -Guid $a.id -Key $a.key -ValueName $a.name | Out-Null\n" + _done("registry_remove")
    )


def create(name: str, comment: str) -> str:
    return _PRELUDE + _args({"name": name, "comment": comment}) + r"""
$g = New-GPO -Name $a.name -Comment $a.comment
@{ ok = $true; id = $g.Id.Guid.ToLower() } | ConvertTo-Json -Compress
"""


def delete(gpo_id: str, backup: str) -> str:
    return _PRELUDE + _args({"id": gpo_id, "backup": backup, "what": "удалением"}) + (
        _backup(backup) + "Remove-GPO -Guid $a.id\n" + _done("delete")
    )


# On a computer: which GPOs it really applied, from gpresult (computer part; the console user's part too).
GPRESULT = r"""
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$f = Join-Path $env:TEMP ('netadmin-gpresult-' + [guid]::NewGuid() + '.xml')
gpresult /scope computer /x $f /f | Out-Null
[xml]$x = Get-Content -Path $f -Encoding UTF8
Remove-Item $f -Force
$r = $x.Rsop.ComputerResults
$applied = foreach ($g in $r.GPO) {
  [pscustomobject]@{
    name = $g.Name; enabled = ($g.Enabled -eq 'true'); applied = ($g.IsValid -eq 'true' -and $g.FilterAllowed -eq 'true' -and $g.AccessDenied -ne 'true')
    filtered = ($g.FilterAllowed -ne 'true'); denied = ($g.AccessDenied -eq 'true'); link = $g.Link.SOMPath
    id = ($g.Path.Identifier.'#text' -replace '[{}]', '').ToLower()
  }
}
@{ read_at = $r.ReadTime; site = $r.Site; gpos = @($applied) } | ConvertTo-Json -Depth 4 -Compress
"""
