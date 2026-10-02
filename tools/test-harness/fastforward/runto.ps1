# Fast-forwards the loaded campaign until it has run -Days game days, in rounds of run.ps1 (Chunks x
# Seconds of held Shift, then a quicksave). Reads the game day from the "Clock: day N" lines of
# ti-<Tag>.txt, so tail-ti.ps1 -Tag <Tag> must be running (and threatinc_debugLogging on). Appends
# every round to run-<Tag>.txt and writes run-<Tag>.done when it ends, with the reason - made to be
# started detached (Start-Process -WindowStyle Hidden) and polled.
# It yields the machine: when another window has the foreground it lets go of Shift and waits. A
# toast or chat popup with nobody at the machine (no keyboard or mouse for -IdleSeconds) is ridden
# out and the run carries on; someone at work for -WaitMinutes stops it with FOREGROUND LOST and
# the game window is sent to the back, left running at normal speed. Start runto again (same -Tag,
# same -Days: the count is from the run's first Clock line) when the machine is free.
param([int]$Days = 3300, [string]$Tag = "x", [int]$Chunks = 3, [int]$Seconds = 110, [int]$MaxRounds = 60, [int]$IdleSeconds = 90, [int]$WaitMinutes = 20)
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
New-Item -ItemType Directory -Force $out | Out-Null
$txt = "$out\run-$Tag.txt"; $done = "$out\run-$Tag.done"; $ti = "$out\ti-$Tag.txt"
Remove-Item $done -Force -ErrorAction SilentlyContinue
Add-Type @"
using System; using System.Runtime.InteropServices;
public class RT {
  [StructLayout(LayoutKind.Sequential)] public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
  [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO p);
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
function IdleSeconds { $l = New-Object RT+LASTINPUTINFO; $l.cbSize = 8; [RT]::GetLastInputInfo([ref]$l) | Out-Null; [int](([Environment]::TickCount - [int]$l.dwTime) / 1000) }
# Another window has the foreground. A toast or a chat popup takes it for a moment with nobody at
# the machine: wait until the game has it back, or nobody has touched keyboard or mouse for
# -IdleSeconds (the next round then takes it back). $false after -WaitMinutes of someone at work.
function Free($g) {
  $until = (Get-Date).AddMinutes($WaitMinutes)
  while ((Get-Date) -lt $until) {
    if ([RT]::GetForegroundWindow() -eq $g.MainWindowHandle) { return $true }
    if ((IdleSeconds) -ge $IdleSeconds) { return $true }
    Start-Sleep 10
  }
  return $false
}
$why = "MAX ROUNDS"; $line = ""
for ($r = 1; $r -le $MaxRounds; $r++) {
  $g = Game
  if (-not $g) { $why = "STOPPED: WINDOW LOST (no game process)"; break }
  # the first round may take the foreground (the caller has just launched the game); later ones only from an idle machine
  if ($r -gt 1 -and [RT]::GetForegroundWindow() -ne $g.MainWindowHandle -and -not (Free $g)) { $why = "STOPPED: FOREGROUND LOST"; break }
  $o = powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\run.ps1" -Chunks $Chunks -Seconds $Seconds -SaveEvery $Chunks -Tag $Tag
  $o | Add-Content $txt
  $c = Clock
  $el = if ($c) { $c.Last - $c.First } else { -1 }
  $line = "{0} round {1}: {2} days run, war day {3} ({4})" -f (Get-Date -Format HH:mm:ss), $r, $el, $(if ($c) { $c.War }), $(if ($c) { $c.Date })
  $line | Add-Content $txt
  if (($o -join "`n") -match "WINDOW LOST|LITTLE PROGRESS") { $why = "STOPPED: " + $Matches[0]; break }
  if ($el -ge $Days) { $why = "REACHED"; break }
  if (($o -join "`n") -match "FOREGROUND LOST") {
    if (Free $g) { "$(Get-Date -Format HH:mm:ss) foreground lost, machine idle - carrying on" | Add-Content $txt }
    else { $why = "STOPPED: FOREGROUND LOST"; break }
  }
}
if ($why -like "*FOREGROUND LOST*") {
  # out of the user's way: not topmost, behind everything, without activating it (NOMOVE|NOSIZE|NOACTIVATE)
  $g = Game
  if ($g) { [RT]::SetWindowPos($g.MainWindowHandle, [IntPtr](-2), 0, 0, 0, 0, 0x13) | Out-Null; [RT]::SetWindowPos($g.MainWindowHandle, [IntPtr]1, 0, 0, 0, 0, 0x13) | Out-Null }
}
"$why after $r rounds; $line" | Tee-Object -FilePath $done | Add-Content $txt
