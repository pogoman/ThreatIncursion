# Fast-forwards the loaded campaign until it has run -Days game days, in rounds of run.ps1 (Chunks x
# Seconds of held Shift, then a quicksave). Reads the game day from the "Clock: day N" lines of
# ti-<Tag>.txt, so tail-ti.ps1 -Tag <Tag> must be running (and threatinc_debugLogging on). Appends
# every round to run-<Tag>.txt and writes run-<Tag>.done when it ends, with the reason - made to be
# started detached (Start-Process -WindowStyle Hidden) and polled.
param([int]$Days = 3300, [string]$Tag = "x", [int]$Chunks = 3, [int]$Seconds = 110, [int]$MaxRounds = 60)
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
New-Item -ItemType Directory -Force $out | Out-Null
$txt = "$out\run-$Tag.txt"; $done = "$out\run-$Tag.done"; $ti = "$out\ti-$Tag.txt"
Remove-Item $done -Force -ErrorAction SilentlyContinue
function Clock {
  if (-not (Test-Path $ti)) { return $null }
  $c = Select-String -Path $ti -Pattern '^Clock: day (-?\d+) war (-?\d+) \((.*)\)'
  if (-not $c) { return $null }
  $a = $c[0].Matches[0].Groups; $z = $c[$c.Count - 1].Matches[0].Groups
  [pscustomobject]@{ First = [long]$a[1].Value; Last = [long]$z[1].Value; War = [int]$z[2].Value; Date = $z[3].Value }
}
$why = "MAX ROUNDS"
for ($r = 1; $r -le $MaxRounds; $r++) {
  $o = powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\run.ps1" -Chunks $Chunks -Seconds $Seconds -SaveEvery $Chunks -Tag $Tag
  $o | Add-Content $txt
  $c = Clock
  $el = if ($c) { $c.Last - $c.First } else { -1 }
  $line = "{0} round {1}: {2} days run, war day {3} ({4})" -f (Get-Date -Format HH:mm:ss), $r, $el, $(if ($c) { $c.War }), $(if ($c) { $c.Date })
  $line | Add-Content $txt
  if (($o -join "`n") -match "WINDOW LOST|LITTLE PROGRESS") { $why = "STOPPED: " + $Matches[0]; break }
  if ($el -ge $Days) { $why = "REACHED"; break }
}
"$why after $r rounds; $line" | Set-Content $done
