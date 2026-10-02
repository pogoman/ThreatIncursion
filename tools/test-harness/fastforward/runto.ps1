# Fast-forwards the loaded campaign until it has run -Days game days, in rounds of run.ps1 (Chunks x
# Seconds of held Shift, then a quicksave). Reads the game day from the "Clock: day N" lines of
# ti-<Tag>.txt, so tail-ti.ps1 -Tag <Tag> must be running (and threatinc_debugLogging on). Appends
# every round to run-<Tag>.txt and writes run-<Tag>.done when it ends, with the reason - made to be
# started detached (Start-Process -WindowStyle Hidden) and polled.
# It yields the machine: when another window has the foreground (someone is using the laptop, here
# or over remote desktop) it stops with FOREGROUND LOST instead of taking the foreground back, and
# sends the game window to the back. The game is left running at normal speed; start runto again
# (same -Tag, same -Days: the count is from the run's first Clock line) when the machine is free.
param([int]$Days = 3300, [string]$Tag = "x", [int]$Chunks = 3, [int]$Seconds = 110, [int]$MaxRounds = 60)
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
New-Item -ItemType Directory -Force $out | Out-Null
$txt = "$out\run-$Tag.txt"; $done = "$out\run-$Tag.done"; $ti = "$out\ti-$Tag.txt"
Remove-Item $done -Force -ErrorAction SilentlyContinue
Add-Type @"
using System; using System.Runtime.InteropServices;
public class RT {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@
function Game { Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Starsector*" } | Select-Object -First 1 }
function Clock {
  if (-not (Test-Path $ti)) { return $null }
  $c = Select-String -Path $ti -Pattern '^Clock: day (-?\d+) war (-?\d+) \((.*)\)'
  if (-not $c) { return $null }
  $a = $c[0].Matches[0].Groups; $z = $c[$c.Count - 1].Matches[0].Groups
  [pscustomobject]@{ First = [long]$a[1].Value; Last = [long]$z[1].Value; War = [int]$z[2].Value; Date = $z[3].Value }
}
$why = "MAX ROUNDS"; $line = ""
for ($r = 1; $r -le $MaxRounds; $r++) {
  $g = Game
  if (-not $g) { $why = "STOPPED: WINDOW LOST (no game process)"; break }
  # the first round may take the foreground (the caller has just launched the game); later ones never
  if ($r -gt 1 -and [RT]::GetForegroundWindow() -ne $g.MainWindowHandle) { $why = "STOPPED: FOREGROUND LOST"; break }
  $o = powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\run.ps1" -Chunks $Chunks -Seconds $Seconds -SaveEvery $Chunks -Tag $Tag
  $o | Add-Content $txt
  $c = Clock
  $el = if ($c) { $c.Last - $c.First } else { -1 }
  $line = "{0} round {1}: {2} days run, war day {3} ({4})" -f (Get-Date -Format HH:mm:ss), $r, $el, $(if ($c) { $c.War }), $(if ($c) { $c.Date })
  $line | Add-Content $txt
  if (($o -join "`n") -match "WINDOW LOST|LITTLE PROGRESS|FOREGROUND LOST") { $why = "STOPPED: " + $Matches[0]; break }
  if ($el -ge $Days) { $why = "REACHED"; break }
}
if ($why -like "*FOREGROUND LOST*") {
  # out of the user's way: not topmost, behind everything, without activating it (NOMOVE|NOSIZE|NOACTIVATE)
  $g = Game
  if ($g) { [RT]::SetWindowPos($g.MainWindowHandle, [IntPtr](-2), 0, 0, 0, 0, 0x13) | Out-Null; [RT]::SetWindowPos($g.MainWindowHandle, [IntPtr]1, 0, 0, 0, 0, 0x13) | Out-Null }
}
"$why after $r rounds; $line" | Tee-Object -FilePath $done | Add-Content $txt
