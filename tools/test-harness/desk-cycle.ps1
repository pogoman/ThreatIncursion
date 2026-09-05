# Desk-monitor test cycle (game at 1920x1080 windowed on the 3440x1440 primary, 2026-09-05):
# kill, launch, force the launcher topmost (background scripts cannot steal foreground, so
# unplaced clicks land on whatever covers the game), Play, force the game window topmost,
# wait for the menu, Continue at (1486,314) with retries while it says "Preloading", wait
# for the save, optionally run the clock for -RunSeconds (the campaign is always PAUSED
# after a load and after leaving intel, so one space unpauses), open intel with E (the war
# board stays selected across launches), click a selector button, hover a row with real
# motion events (SetCursorPos alone shows no tooltip), capture via the game's own
# screenshot, then print the mod's log lines since launch.
#
#   desk-cycle.ps1 -Tag econ -Faction hegemony -RunSeconds 12 -HoverX 1120 -HoverY 426
#
# Selector buttons at 1920x1080 sit on the row at y=227 (with nine factions mobilised the
# Hegemony button was at x=712; re-measure from a capture when the roster differs).
param(
  [string]$Tag = "desk",
  [string]$OutDir = $PSScriptRoot,
  [int]$FactionX = 0, [int]$FactionY = 227,   # 0 = stay on the hive view
  [string]$Faction = "",                       # "hegemony" = x 712 (nine-faction roster)
  [int]$RunSeconds = 0,                        # unpaused seconds at 1x before opening intel
  [int]$HoverX = 0, [int]$HoverY = 0,          # 0 = no tooltip capture
  [string]$LogMatch = "War footing|War mode|Reserve ledger|Reserve cover|Reserve seed|Economy recomputed|Convoy arrived|Convoy dispatched|Expedition draw|ERROR|Exception|at threatinc"
)
$ErrorActionPreference = "Continue"
$ui = Join-Path $PSScriptRoot "ui.ps1"
$gs = Join-Path $PSScriptRoot "gameshot.ps1"
$place = Join-Path $PSScriptRoot "place.ps1"
$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core"
$log = "$core\starsector.log"
$t0 = Get-Date
function Elapsed { return [int]((Get-Date) - $t0).TotalSeconds }
Add-Type @"
using System; using System.Runtime.InteropServices;
public class DeskMouse {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, int dx, int dy, uint data, UIntPtr extra);
}
"@ -ErrorAction SilentlyContinue
function Hover([int]$x, [int]$y) {
  $r = & $ui -Action rect
  if ($r -match "at (\d+),(\d+)") { $ox = [int]$Matches[1]; $oy = [int]$Matches[2] } else { return }
  [DeskMouse]::SetCursorPos($ox + $x - 30, $oy + $y - 6) | Out-Null
  Start-Sleep -Milliseconds 200
  for ($i = 0; $i -lt 8; $i++) { [DeskMouse]::mouse_event(0x0001, 4, 1, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60 }
}
if ($Faction -eq "hegemony" -and $FactionX -eq 0) { $FactionX = 712 }

Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Starsector*" -or $_.Path -like "*Starsector*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
$menuBefore = (Select-String -Path $log -Pattern "Reading save data from" -ErrorAction SilentlyContinue | Measure-Object).Count
$before = (Select-String -Path $log -Pattern "Loading stage 39 - last" -ErrorAction SilentlyContinue | Measure-Object).Count
$logSize0 = (Get-Item $log -ErrorAction SilentlyContinue).Length
$lineStart = (Get-Content $log | Measure-Object -Line).Lines
function RolledOver { $s = (Get-Item $log -ErrorAction SilentlyContinue).Length; return ($s -lt $script:logSize0) }

Start-Process -FilePath "$core\starsector.bat" -WorkingDirectory $core -WindowStyle Minimized
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep -Seconds 1; $r = & $ui -Action rect } while ($r -eq "NOWINDOW" -and (Get-Date) -lt $deadline)
Start-Sleep -Seconds 3
& $place -X 300 -Y 300 | Out-Null
Start-Sleep -Seconds 1
$r = & $ui -Action rect
Write-Output "$(Elapsed)s launcher: $r"
if ($r -like "*client 597x*") { & $ui -Action click -X 298 -Y 254 | Out-Null } else { & $ui -Action click -X 402 -Y 343 | Out-Null }
$deadline = (Get-Date).AddSeconds(180)
do { Start-Sleep -Seconds 3; $r = & $ui -Action rect } while ($r -notlike "*client 1920x1080*" -and (Get-Date) -lt $deadline)
Write-Output "$(Elapsed)s game window: $r"
$deadline = (Get-Date).AddSeconds(240)
do { Start-Sleep -Seconds 3; if (RolledOver) { $menuBefore = 0; $before = 0; $lineStart = 0 }; $menuNow = (Select-String -Path $log -Pattern "Reading save data from" -ErrorAction SilentlyContinue | Measure-Object).Count } while ($menuNow -le $menuBefore -and (Get-Date) -lt $deadline)
Start-Sleep -Seconds 10
& $place -X 100 -Y 100 | Out-Null
Start-Sleep -Seconds 1
Write-Output "$(Elapsed)s menu"
$after = $before
for ($attempt = 1; $attempt -le 5 -and $after -le $before; $attempt++) {
  & $ui -Action click -X 1486 -Y 314 | Out-Null
  $deadline = (Get-Date).AddSeconds(45)
  do { Start-Sleep -Seconds 3; $after = (Select-String -Path $log -Pattern "Loading stage 39 - last" -ErrorAction SilentlyContinue | Measure-Object).Count } while ($after -le $before -and (Get-Date) -lt $deadline)
  Write-Output "$(Elapsed)s continue attempt $attempt loaded=$($after -gt $before)"
  if ($after -le $before) { Start-Sleep -Seconds 5 }
}
if ($after -le $before) { Write-Output "ABORT: save did not load"; exit 1 }
Start-Sleep -Seconds 8
if ($RunSeconds -gt 0) {
  & $ui -Action key -Text " " | Out-Null
  Start-Sleep -Seconds $RunSeconds
  & $ui -Action key -Text " " | Out-Null
  Start-Sleep -Seconds 1
}
& $ui -Action key -Text "e" | Out-Null
Start-Sleep -Seconds 4
if ($FactionX -gt 0) { & $ui -Action click -X $FactionX -Y $FactionY | Out-Null; Start-Sleep -Seconds 3 }
if ($HoverX -gt 0) {
  Hover $HoverX $HoverY
  Start-Sleep -Milliseconds 1800
  Write-Output ("tooltip: " + (& $gs -Out (Join-Path $OutDir "$Tag-tooltip.png") -Scale 0.6))
}
Hover 1910 1006
Start-Sleep -Milliseconds 800
Write-Output ("board: " + (& $gs -Out (Join-Path $OutDir "$Tag-board.png") -Scale 0.6))
Write-Output "$(Elapsed)s done - log since launch:"
Get-Content $log | Select-Object -Skip $lineStart | Where-Object { $_ -match $LogMatch } | ForEach-Object { $_.Substring(0, [Math]::Min(400, $_.Length)) } | Select-Object -First 80
