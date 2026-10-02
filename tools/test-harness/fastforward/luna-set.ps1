# Sets one ThreatInc knob in the LunaLib store, e.g. -Key threatinc_swarmFogOfWar -Value false for a
# fog-off comparison run. The store is global, not per save: set it back when the run is done.
param([Parameter(Mandatory = $true)][string]$Key, [Parameter(Mandatory = $true)][string]$Value)
$f = "C:\Program Files (x86)\Fractal Softworks\Starsector\saves\common\LunaSettings\threatinc.json.data"
# the game writes the store on exit: edit it with the game closed
if (-not (Test-Path $f)) { "no LunaLib store at $f - open the mod's settings once in game first"; exit 1 }
$t = [IO.File]::ReadAllText($f)
if ([string]::IsNullOrWhiteSpace($t)) { "empty store - nothing edited"; exit 1 }
$k = [regex]::Escape($Key)
if ($t -match ('"' + $k + '":\s*[^,\r\n}]+')) {
  $t = [regex]::Replace($t, '"' + $k + '":\s*[^,\r\n}]+', '"' + $Key + '": ' + $Value)
} else {
  $i = $t.IndexOf('{')
  $t = $t.Substring(0, $i + 1) + "`n" + '   "' + $Key + '": ' + $Value + ',' + $t.Substring($i + 1)
}
$enc = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($f, $t, $enc)
[regex]::Match($t, '"' + $k + '":\s*[^,\r\n}]+').Value
