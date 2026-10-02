# Takes a simulator start state from a save: loads it with threatinc_debugSimDump on, lets the
# clock run until the mod writes its dump (the first poll of a session always dumps), kills the
# game WITHOUT saving, and copies the map and the dump to tools\warsim\start\<Name>
# (docs/war-sim.md 12). The save is not changed. Restores the Continue pref and the knob.
param([Parameter(Mandatory = $true)][string]$Save, [Parameter(Mandatory = $true)][string]$Name)
$h = Split-Path $PSScriptRoot -Parent
$root = Split-Path (Split-Path $h -Parent) -Parent
$star = "C:\Program Files (x86)\Fractal Softworks\Starsector"
$common = "$star\saves\common"
$prefs = 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer'
if (-not (Test-Path "$star\saves\$Save")) { "missing save: $Save"; exit 1 }
function KillGame { Get-Process -Name java, javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "*Starsector*" } | Stop-Process -Force; Start-Sleep 3 }
function UI { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\ui.ps1" @args }

KillGame
$was = (Get-ItemProperty $prefs).continue
Remove-Item "$common\threatinc_sim*.json.data" -ErrorAction SilentlyContinue
& "$PSScriptRoot\luna-set.ps1" -Key threatinc_debugSimDump -Value true | Out-Null
# the prefs escape each capital with a slash
$esc = -join ($Save.ToCharArray() | ForEach-Object { if ([char]::IsUpper($_)) { "/$_" } else { "$_" } })
Set-ItemProperty $prefs -Name continue -Value "..\saves\$esc"
try {
  & "$PSScriptRoot\launch.ps1" | Select-Object -Last 2
  powershell -NoProfile -ExecutionPolicy Bypass -File "$h\place.ps1" -X 0 -Y 0 | Out-Null
  $got = $false
  for ($i = 0; $i -lt 6 -and -not $got; $i++) {
    powershell -NoProfile -ExecutionPolicy Bypass -File "$h\hold.ps1" -Key shift -Seconds 6 | Out-Null
    $got = @(Get-ChildItem "$common\threatinc_simdump_d*.json.data" -ErrorAction SilentlyContinue).Count -gt 0
    # nothing written: the clock is paused, one space starts it
    if (-not $got) { UI -Action key -Text " " | Out-Null; Start-Sleep 1 }
  }
  KillGame
  if (-not $got) { "NO DUMP written - is the incursion started in this save?"; exit 1 }
  $dst = Join-Path $root "tools\warsim\start\$Name"
  New-Item -ItemType Directory -Force $dst | Out-Null
  Remove-Item "$dst\threatinc_sim*" -ErrorAction SilentlyContinue
  # the earliest dump is the save's own state; game days are negative, so the name sorts last
  $first = Get-ChildItem "$common\threatinc_simdump_d*.json.data" | Sort-Object { [long]($_.Name -replace '^threatinc_simdump_d', '' -replace '\.json\.data$', '') } | Select-Object -First 1
  Copy-Item "$common\threatinc_simmap.json.data" "$dst\threatinc_simmap.json"
  Copy-Item $first.FullName (Join-Path $dst ($first.Name -replace '\.data$', ''))
  "start state: $dst ($($first.Name))"
} finally {
  KillGame
  Remove-Item "$common\threatinc_sim*.json.data" -ErrorAction SilentlyContinue
  & "$PSScriptRoot\luna-set.ps1" -Key threatinc_debugSimDump -Value false | Out-Null
  Set-ItemProperty $prefs -Name continue -Value $was
}
