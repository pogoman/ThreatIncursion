# One test cycle: kill the game, relaunch, reach The Threat War Effort, capture it.
# Waits are adaptive: the launcher and game windows are detected, the main menu by the
# Continue button lighting up, and the save load by a new "Loading stage 39 - last" line
# in starsector.log. See docs/testing-harness.md for coordinates per resolution.
param(
  [string]$Tag = "board",
  [double]$Scale = 0.5,
  [string]$OutDir = $PSScriptRoot,
  [int]$ContinueX = 2250, [int]$ContinueY = 492,
  [int]$TabX = 1357, [int]$TabY = 1155,
  [int]$EntryX = 800, [int]$EntryY = 692,
  [int]$ParkX = 3300, [int]$ParkY = 1400,
  [switch]$StopAtMenu,
  [switch]$StopAtIntel
)
$ErrorActionPreference = "Continue"
$ui = Join-Path $PSScriptRoot "ui.ps1"
$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core"
$log = "$core\starsector.log"
$t0 = Get-Date
function Elapsed { return [int]((Get-Date) - $t0).TotalSeconds }

Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "*Starsector*" -or $_.MainWindowTitle -like "*Starsector*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
$before = (Select-String -Path $log -Pattern "Loading stage 39 - last" -ErrorAction SilentlyContinue | Measure-Object).Count

Start-Process -FilePath "$core\starsector.bat" -WorkingDirectory $core -WindowStyle Minimized
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep -Seconds 1; $r = & $ui -Action rect } while ($r -eq "NOWINDOW" -and (Get-Date) -lt $deadline)
Write-Output "$(Elapsed)s launcher: $r"
Start-Sleep -Seconds 2
& $ui -Action click -X 402 -Y 343 | Out-Null

# main menu: the window grows to the game resolution; the menu is usable once Continue is lit
$deadline = (Get-Date).AddSeconds(240)
do { Start-Sleep -Seconds 2; $r = & $ui -Action rect } while ($r -like "*client 805x*" -and (Get-Date) -lt $deadline)
do { Start-Sleep -Seconds 2; $r = & $ui -Action rect } while ($r -eq "NOWINDOW" -and (Get-Date) -lt $deadline)
Write-Output "$(Elapsed)s game window: $r"
$deadline = (Get-Date).AddSeconds(180)
if ($StopAtMenu) { Start-Sleep -Seconds 35 } else {
  do {
    Start-Sleep -Seconds 2
    $px = & $ui -Action pixel -X $ContinueX -Y $ContinueY
    $lit = $false
    if ($px -match "PIXEL (\d+) (\d+) (\d+)") { $lit = ([int]$Matches[1] + [int]$Matches[2] + [int]$Matches[3]) -gt 120 }
  } while (-not $lit -and (Get-Date) -lt $deadline)
  Start-Sleep -Seconds 3
}
& $ui -Action shot -Out (Join-Path $OutDir "$Tag-menu.png") -Scale 0.4 | Out-Null
Write-Output "$(Elapsed)s menu captured"
if ($StopAtMenu) { exit 0 }

& $ui -Action click -X $ContinueX -Y $ContinueY | Out-Null
$deadline = (Get-Date).AddSeconds(240)
do {
  Start-Sleep -Seconds 2
  $after = (Select-String -Path $log -Pattern "Loading stage 39 - last" -ErrorAction SilentlyContinue | Measure-Object).Count
} while ($after -le $before -and (Get-Date) -lt $deadline)
Start-Sleep -Seconds 4
Write-Output "$(Elapsed)s save loaded"

& $ui -Action key -Text "e" | Out-Null
Start-Sleep -Seconds 3
if ($StopAtIntel) { & $ui -Action shot -Out (Join-Path $OutDir "$Tag-intel.png") -Scale 0.5; exit 0 }
& $ui -Action click -X $TabX -Y $TabY | Out-Null      # Major events tab
Start-Sleep -Seconds 2
& $ui -Action click -X $EntryX -Y $EntryY | Out-Null  # The Threat War Effort entry
Start-Sleep -Seconds 2
& $ui -Action move -X $ParkX -Y $ParkY | Out-Null     # off the table so no tooltip covers it
Start-Sleep -Seconds 1
& $ui -Action shot -Out (Join-Path $OutDir "$Tag.png") -Scale $Scale
Write-Output "$(Elapsed)s board captured"
Select-String -Path $log -Pattern "war board render" | Select-Object -Last 1 | ForEach-Object { $_.Line }
