# Puts back what a game run changed on this machine: the launcher prefs (continue, resolution, fullscreen,
# autosave), the Shift multiplier, and the three test switches in the LunaLib store. Reads the backup beside it
# (backup-settings.ps1 copies this file there as restore.ps1, which is what sbs.ps1 runs at the end of a batch).
# Run with the game closed.
$bk = $PSScriptRoot
$key = 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer'
if (-not (Test-Path "$bk\prefs.json")) { "no prefs.json beside this script - run backup-settings.ps1 first"; exit 1 }
$p = Get-Content "$bk\prefs.json" -Raw | ConvertFrom-Json
if ($p.continue) { Set-ItemProperty $key -Name continue -Value $p.continue }
Set-ItemProperty $key -Name resolution -Value $p.resolution
Set-ItemProperty $key -Name fullscreen -Value $p.fullscreen
Set-ItemProperty $key -Name 'gameplay/Settings' -Value $p.gameplaySettings
$common = 'C:\Program Files (x86)\Fractal Softworks\Starsector\saves\common'
if (Test-Path "$bk\LunaSettings_shiftspeed.json.data") {
  Copy-Item "$bk\LunaSettings_shiftspeed.json.data" "$common\LunaSettings\shiftspeed.json.data" -Force
}
$ff = 'C:\Program Files (x86)\Fractal Softworks\Starsector\mods\ThreatIncursion\tools\test-harness\fastforward'
foreach ($k in 'threatinc_debugLogging', 'threatinc_debugSimDump', 'threatinc_debugPlayerIgnored') {
  powershell -NoProfile -ExecutionPolicy Bypass -File "$ff\luna-set.ps1" -Key $k -Value false
}
$q = Get-ItemProperty $key
"continue   = $($q.continue)"
"resolution = $($q.resolution)"
"autosave   = " + [regex]::Match($q.'gameplay/Settings', '"autosave/On":[a-z]+').Value
Get-Content "$common\LunaSettings\shiftspeed.json.data"
