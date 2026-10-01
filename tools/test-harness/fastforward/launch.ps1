# Launch Starsector at the 1600x900 pref and Continue into whatever the prefs key points at.
# -MenuOnly stops at the main menu (for a new game). Survives the game rolling starsector.log over.
param([switch]$MenuOnly)
$h = Split-Path $PSScriptRoot -Parent
$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core"
$log = "$core\starsector.log"
function UI { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\ui.ps1" @args }
function Place($x, $y) { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\place.ps1" -X $x -Y $y }
$t0 = Get-Date
function El { [int]((Get-Date) - $t0).TotalSeconds }
function Hits($pat) { if (Test-Path $log) { (Select-String -Path $log -Pattern $pat | Measure-Object).Count } else { 0 } }
# the game rolls starsector.log over at ~50 MB: a shorter log than at launch counts from zero
function Rolled { $l = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }; if ($l -lt $script:len0) { $script:len0 = 0; return $true }; $script:len0 = [Math]::Max($script:len0, $l); return $false }

Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "*Starsector*" } | Stop-Process -Force
Start-Sleep 2
$menuBefore = Hits "Reading save data from"
$before = Hits "Loading stage 39 - last"
$script:len0 = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }

Start-Process -FilePath "$core\starsector.bat" -WorkingDirectory $core -WindowStyle Minimized
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep 1; $r = UI -Action rect } while ($r -eq "NOWINDOW" -and (Get-Date) -lt $deadline)
Start-Sleep 3
Place 200 150 | Out-Null
UI -Action click -X 298 -Y 254 | Out-Null
$deadline = (Get-Date).AddSeconds(180)
do { Start-Sleep 3; $r = UI -Action rect } while ($r -notlike "*client 1600x900*" -and (Get-Date) -lt $deadline)
"$(El)s window: $r"
Place 0 0 | Out-Null
$deadline = (Get-Date).AddSeconds(300)
do {
  Start-Sleep 3
  if (Rolled) { $menuBefore = 0; $before = 0; "  (log rolled over)" }
  $m = Hits "Reading save data from"
} while ($m -le $menuBefore -and (Get-Date) -lt $deadline)
Start-Sleep 8
"$(El)s menu"
if ($MenuOnly) { return }
for ($a = 1; $a -le 4; $a++) {
  UI -Action click -X 1190 -Y 282 | Out-Null
  $deadline = (Get-Date).AddSeconds(60)
  do {
    Start-Sleep 3
    if (Rolled) { $before = 0 }
    $after = Hits "Loading stage 39 - last"
  } while ($after -le $before -and (Get-Date) -lt $deadline)
  if ($after -gt $before) { break }
}
Start-Sleep 6
"$(El)s loaded=$($after -gt $before) attempts=$a"
