# Writes the [ThreatInc] lines (and exceptions) since the last save load to ti-<Tag>.txt in the
# output dir ($env:THREATINC_TEST_OUT, default %TEMP%\threatinc-tests), prefix stripped, then prints
# a digest. Reads the rolled-over logs (.1, .2, ...) back to the load when it is older than the
# current log (the game rolls the log over at ~50 MB, more than once in a long run).
param([string]$Tag = "x", [int]$Show = 60, [string]$Marker = "Reading save data from")
$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core"
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
New-Item -ItemType Directory -Force $out | Out-Null
function ReadLog($path) {
  if (-not (Test-Path $path)) { return @() }
  $fs = New-Object System.IO.FileStream($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  $sr = New-Object System.IO.StreamReader($fs)
  $t = $sr.ReadToEnd(); $sr.Close()
  return ($t -split "`r?`n")
}
$lines = ReadLog "$core\starsector.log"
$start = -1
for ($i = $lines.Count - 1; $i -ge 0; $i--) { if ($lines[$i].Contains($Marker)) { $start = $i; break } }
# the load is in a rolled-over log: a long run can roll it more than once, so walk back
# .1, .2, ... until the load is found, then take it from there and every newer log whole
$n = 1
while ($start -lt 0 -and (Test-Path "$core\starsector.log.$n")) {
  $old = ReadLog "$core\starsector.log.$n"
  for ($i = $old.Count - 1; $i -ge 0; $i--) { if ($old[$i].Contains($Marker)) { $start = $i; break } }
  if ($start -ge 0) { $lines = @($old[$start..($old.Count - 1)]) + $lines; $start = 0; "(load found in starsector.log.$n)" }
  else { $lines = @($old) + $lines; $n++ }
}
if ($start -lt 0) { $start = 0; "(no load marker found; every log whole)" }
$ti = New-Object System.Collections.Generic.List[string]
$exc = New-Object System.Collections.Generic.List[string]
for ($i = $start; $i -lt $lines.Count; $i++) {
  $l = $lines[$i]
  $k = $l.IndexOf("[ThreatInc] ")
  if ($k -ge 0) { $ti.Add($l.Substring($k + 12)) }
  elseif ($l -cmatch "Exception|ERROR") { $exc.Add($l) }
}
[System.IO.File]::WriteAllLines("$out\ti-$Tag.txt", $ti)
"lines $($ti.Count) -> $out\ti-$Tag.txt, exception/error lines $($exc.Count)"
$exc | Select-Object -First 8
"--- digest"
$ti | Where-Object { $_ -match "^Census: threat|^Posture sector|^Stance" } | Select-Object -Last $Show
