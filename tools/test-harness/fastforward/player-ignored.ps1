# Sets Debug > Fleets Ignore You (threatinc_debugPlayerIgnored) in the LunaLib store. The store is
# global, not per save: turn it OFF again when testing is done, or no fleet will ever meet yours.
param([ValidateSet("on", "off")][string]$State = "on")
$f = "C:\Program Files (x86)\Fractal Softworks\Starsector\saves\common\LunaSettings\threatinc.json.data"
# the game writes the store on exit: edit it with the game closed
if (-not (Test-Path $f)) { "no LunaLib store at $f - open the mod's settings once in game first"; exit 1 }
$t = [IO.File]::ReadAllText($f)
if ([string]::IsNullOrWhiteSpace($t)) { "empty store - nothing edited"; exit 1 }
$v = if ($State -eq "on") { "true" } else { "false" }
if ($t -match '"threatinc_debugPlayerIgnored":\s*(true|false)') {
  $t = [regex]::Replace($t, '"threatinc_debugPlayerIgnored":\s*(true|false)', '"threatinc_debugPlayerIgnored": ' + $v)
} else {
  $t = [regex]::Replace($t, '("threatinc_debugGrantSensorMods":\s*(true|false),)', '$1' + "`n" + '   "threatinc_debugPlayerIgnored": ' + $v + ',')
}
$enc = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($f, $t, $enc)
[regex]::Match($t, '"threatinc_debugPlayerIgnored":\s*(true|false)').Value
