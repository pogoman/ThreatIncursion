# Once on a machine, BEFORE its first game run: saves the user's launcher prefs (continue, resolution, fullscreen,
# gameplay settings) and Shift multiplier to %TEMP%\threatinc-tests\backup-20261002, with restore.ps1 beside them.
# sbs.ps1 runs that restore.ps1 at the end of every batch; without the backup the run settings (1600x900 windowed,
# autosave off, Shift at 48x, the debug switches) are left on. Refuses to overwrite a backup, so the run settings
# are never saved as the user's. Run with the game closed, after the game has been started once with the mods on.
param([switch]$Force)
$d = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
$bk = "$d\backup-20261002"
$key = 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer'
$common = 'C:\Program Files (x86)\Fractal Softworks\Starsector\saves\common'
if ((Test-Path "$bk\prefs.json") -and -not $Force) { "a backup is already at $bk - left as it is"; exit 0 }
if (-not (Test-Path $key)) { "no launcher prefs at $key - start the game once first"; exit 1 }
foreach ($f in 'shiftspeed.json.data', 'threatinc.json.data') {
  if (-not (Test-Path "$common\LunaSettings\$f")) { "no $common\LunaSettings\$f - start the game once with LunaLib, Shift Speed and the mod enabled, open the mod settings (F2 at the main menu) and save"; exit 1 }
}
New-Item -ItemType Directory -Force $bk | Out-Null
$q = Get-ItemProperty $key
[ordered]@{ fullscreen = "$($q.fullscreen)"; resolution = "$($q.resolution)"; continue = "$($q.continue)"
  gameplaySettings = "$($q.'gameplay/Settings')" } | ConvertTo-Json | Set-Content "$bk\prefs.json" -Encoding UTF8
Copy-Item "$common\LunaSettings\shiftspeed.json.data" "$bk\LunaSettings_shiftspeed.json.data" -Force
Copy-Item "$common\LunaSettings\threatinc.json.data" "$bk\LunaSettings_threatinc.json.data" -Force
Copy-Item "$PSScriptRoot\restore-settings.ps1" "$bk\restore.ps1" -Force
"backed up to $bk"
"resolution = $($q.resolution), fullscreen = $($q.fullscreen), continue = $($q.continue)"
Get-Content "$bk\LunaSettings_shiftspeed.json.data"
