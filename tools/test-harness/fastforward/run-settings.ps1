# The long-run settings on this laptop (restore.ps1 puts the user's back): 1600x900 windowed, vanilla
# autosave off, Shift at 48x, debugLogging / debugSimDump / Fleets Ignore You on, Continue at -Save.
# Run with the game closed.
param([Parameter(Mandatory = $true)][string]$Save)
$key = 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer'
$saves = 'C:\Program Files (x86)\Fractal Softworks\Starsector\saves'
if (-not (Test-Path "$saves\$Save\campaign.xml")) { "no save $Save"; exit 1 }
# Java prefs spell a capital as /X
$esc = [regex]::Replace($Save, '[A-Z]', { param($m) '/' + $m.Value })
Set-ItemProperty $key -Name continue -Value "..\saves\$esc"
Set-ItemProperty $key -Name resolution -Value '1600x900'
Set-ItemProperty $key -Name fullscreen -Value 'false'
$g = (Get-ItemProperty $key).'gameplay/Settings' -replace '"autosave/On":true', '"autosave/On":false'
Set-ItemProperty $key -Name 'gameplay/Settings' -Value $g
$ss = "$saves\common\LunaSettings\shiftspeed.json.data"
$t = [IO.File]::ReadAllText($ss); $t = [regex]::Replace($t, '"shiftspeed_mult":\s*[\d.]+', '"shiftspeed_mult": 48')
[IO.File]::WriteAllText($ss, $t, (New-Object System.Text.UTF8Encoding($false)))
$ff = 'C:\Program Files (x86)\Fractal Softworks\Starsector\mods\ThreatIncursion\tools\test-harness\fastforward'
foreach ($k in 'threatinc_debugLogging', 'threatinc_debugSimDump', 'threatinc_debugPlayerIgnored') {
  powershell -NoProfile -ExecutionPolicy Bypass -File "$ff\luna-set.ps1" -Key $k -Value true
}
$q = Get-ItemProperty $key
"continue   = $($q.continue)"
"resolution = $($q.resolution)"
"autosave   = " + [regex]::Match($q.'gameplay/Settings', '"autosave/On":[a-z]+').Value
Get-Content $ss
