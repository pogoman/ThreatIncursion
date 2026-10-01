# Writes the [ThreatInc] lines (and exceptions) since the last save load to ti-<Tag>.txt in the
# output dir ($env:THREATINC_TEST_OUT, default %TEMP%\threatinc-tests), prefix stripped, then prints
# a digest. Reads starsector.log.1 first when the load is older than the current log (the game
# rolls the log over at ~50 MB).
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
if ($start -lt 0) {
  # the load is in the rolled-over log: take it from there, then the current log whole
  $old = ReadLog "$core\starsector.log.1"
  for ($i = $old.Count - 1; $i -ge 0; $i--) { if ($old[$i].Contains($Marker)) { $start = $i; break } }
  if ($start -ge 0) { $lines = @($old[$start..($old.Count - 1)]) + $lines; $start = 0; "(load found in starsector.log.1)" }
  else { $start = 0; "(no load marker found; whole current log)" }
}
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
