# Laptop-panel (1920x1080) test cycle: kill the game, launch, Play at the 597x373
# launcher, Continue at the menu, wait for the save, open intel (the war board
# stays selected across launches), optionally switch to a faction view, then
# capture via the game's own screenshot (the only reliable path on this panel).
param(
  [string]$Tag = "lap",
  [string]$Faction = "",          # "" = hive view, "hegemony", "player"
  [double]$Scale = 0.5,
  [switch]$NoCapture
)
$sp = $PSScriptRoot
$ui = "C:\Program Files (x86)\Fractal Softworks\Starsector\mods\ThreatIncursion\tools\test-harness\ui.ps1"
$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core"
$log = "$core\starsector.log"
$t0 = Get-Date
function Elapsed { return [int]((Get-Date) - $t0).TotalSeconds }

Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Starsector*" -or $_.Path -like "*Starsector*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
$before = (Select-String -Path $log -Pattern "Loading stage 39 - last" -ErrorAction SilentlyContinue | Measure-Object).Count
$menuBefore = (Select-String -Path $log -Pattern "Reading save data from" -ErrorAction SilentlyContinue | Measure-Object).Count
# log4j rolls starsector.log over at ~10 MB: if the file shrinks after launch,
# every earlier count is stale and the fresh file counts from zero
$logSize0 = (Get-Item $log -ErrorAction SilentlyContinue).Length
function RolledOver { $s = (Get-Item $log -ErrorAction SilentlyContinue).Length; return ($s -lt $script:logSize0) }

Start-Process -FilePath "$core\starsector.bat" -WorkingDirectory $core -WindowStyle Minimized
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep -Seconds 1; $r = & $ui -Action rect } while ($r -eq "NOWINDOW" -and (Get-Date) -lt $deadline)
Write-Output "$(Elapsed)s launcher: $r"
Start-Sleep -Seconds 3
# Play: launcher is 805x503 nominal, 597x373 on this panel
if ($r -like "*client 597x*") { & $ui -Action click -X 298 -Y 254 | Out-Null } else { & $ui -Action click -X 402 -Y 343 | Out-Null }

$deadline = (Get-Date).AddSeconds(180)
do { Start-Sleep -Seconds 3; $r = & $ui -Action rect } while ($r -notlike "*client 1920x1080*" -and (Get-Date) -lt $deadline)
Write-Output "$(Elapsed)s game window: $r"
# main menu is up once it reads the save descriptors for Continue ("Reading save
# data"); the music line fires earlier, during loading
$deadline = (Get-Date).AddSeconds(240)
do { Start-Sleep -Seconds 3; if (RolledOver) { $menuBefore = 0; $before = 0 }; $menuNow = (Select-String -Path $log -Pattern "Reading save data from" | Measure-Object).Count } while ($menuNow -le $menuBefore -and (Get-Date) -lt $deadline)
Start-Sleep -Seconds 8
Write-Output "$(Elapsed)s menu"

# Continue: the button goes live a few seconds after the descriptors are read;
# click, give the load 45 s to start, and click again if it did not (up to 4x)
$after = $before
for ($attempt = 1; $attempt -le 4 -and $after -le $before; $attempt++) {
  Start-Sleep -Seconds 6
  & $ui -Action click -X 1392 -Y 372 | Out-Null
  $deadline = (Get-Date).AddSeconds(45)
  do { Start-Sleep -Seconds 3; if (RolledOver) { $before = 0 }; $after = (Select-String -Path $log -Pattern "Loading stage 39 - last" -ErrorAction SilentlyContinue | Measure-Object).Count } while ($after -le $before -and (Get-Date) -lt $deadline)
  Write-Output "$(Elapsed)s continue attempt $attempt loaded=$($after -gt $before)"
}
if ($after -le $before) {
  $deadline = (Get-Date).AddSeconds(240)
  do { Start-Sleep -Seconds 3; $after = (Select-String -Path $log -Pattern "Loading stage 39 - last" -ErrorAction SilentlyContinue | Measure-Object).Count } while ($after -le $before -and (Get-Date) -lt $deadline)
}
Start-Sleep -Seconds 6
Write-Output "$(Elapsed)s save loaded: $($after -gt $before)"
if ($after -le $before) { Write-Output "ABORT: save did not load"; exit 1 }

& $ui -Action key -Text "e" | Out-Null
Start-Sleep -Seconds 4
if ($Faction -eq "hegemony") { & $ui -Action click -X 730 -Y 194 | Out-Null; Start-Sleep -Seconds 3 }
elseif ($Faction -eq "player") { & $ui -Action click -X 862 -Y 194 | Out-Null; Start-Sleep -Seconds 3 }
& $ui -Action move -X 1900 -Y 1000 | Out-Null
Start-Sleep -Milliseconds 600
if (-not $NoCapture) { & "$sp\gameshot.ps1" -Out "$sp\$Tag.png" -Scale $Scale }
Write-Output "$(Elapsed)s done"
