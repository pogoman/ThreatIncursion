# Side-by-side game runs (2026-10-04): several new games on the hw4 sector at once, fast-forwarded together.
# Each game has its own saves and logs folder (<Starsector>\saves\_sbs\<tag> - saves is the one place under
# the game the harness may write, the Starsector folder itself is not: a copy of saves\common with the run's
# LunaLib store, its own clone of the pristine save, its own starsector.log), so the runs share nothing but
# the launcher prefs, which each launch sets in turn. A game is launched and loaded in the foreground (about
# 90 s each); after that no window needs the foreground.
#
# Fast-forward without the foreground: LWJGL keeps Left Shift down only while the SYSTEM reports it down
# (WindowsKeyboard.poll: GetAsyncKeyState), so a Shift posted to a window alone is dropped at once (trial
# sbs1: 7 days a minute against 215). The script holds Left Shift system-wide and posts the key-down to every
# game window four times a second. While it is held, typing on this machine is shifted: the moment keyboard
# or mouse is touched it lets go and waits for -IdleSeconds of quiet (the games run on at normal speed).
#
#   sbs.ps1 -Tags hw6a,hw6b,hw6c -Days 3750                       three runs on shipped defaults
#   sbs.ps1 -Tags a,b -Knobs "b:threatinc_someKnob=2;threatinc_other=true"   a knob set for one run only
#   sbs.ps1 -Tags sa,sb -TrialSeconds 150                         a timed trial: days run by each game
#
# Status lines in %TEMP%\threatinc-tests\sbs-status.txt, the logs ti-<tag>.txt and exc-<tag>.txt beside it,
# the dumps in tools\warsim\validation\<tag>; sbs-go.done at the end. The user's settings are restored.
# Never saves a game. Run with no game open; it kills any that is.
param([Parameter(Mandatory = $true)][string[]]$Tags, [int]$Days = 3750, [int]$TrialSeconds = 0,
  [string]$Knobs = "", [int]$IdleSeconds = 60, [int]$MaxMinutes = 240, [switch]$KeepSettings)
$star = 'C:\Program Files (x86)\Fractal Softworks\Starsector'
$core = "$star\starsector-core"; $saves = "$star\saves"; $root = "$saves\_sbs"
$mod = "$star\mods\ThreatIncursion"; $h = "$mod\tools\test-harness"; $ff = "$h\fastforward"
$d = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
$base = 'save_AmaruDugas_2921423183749615243'
$key = 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer'
$st = "$d\sbs-status.txt"
$Tags = @($Tags | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
function Say($m) { "$(Get-Date -Format 'HH:mm:ss') $m" | Add-Content $st }
Add-Type @"
using System; using System.Runtime.InteropServices;
public class SBS {
  [StructLayout(LayoutKind.Sequential)] public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
  [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO p);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  // the visible window of this process whose title holds the needle (the launcher, then the game)
  public static IntPtr Find(uint pid, string needle) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      uint p; GetWindowThreadProcessId(h, out p);
      if (p != pid || !IsWindowVisible(h)) return true;
      var sb = new System.Text.StringBuilder(256); GetWindowText(h, sb, 256);
      if (sb.ToString().IndexOf(needle, StringComparison.OrdinalIgnoreCase) >= 0) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
  public static uint LastInput() { var l = new LASTINPUTINFO(); l.cbSize = 8; GetLastInputInfo(ref l); return l.dwTime; }
}
"@
function UI { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\ui.ps1" @args }
# topmost while a game is launched (its clicks must land on it whatever else is open), not after
function Place($hwnd, $x, $y) { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\place.ps1" -X $x -Y $y -Hwnd $hwnd | Out-Null }
function Idle { (([long][BitConverter]::ToUInt32([BitConverter]::GetBytes([Environment]::TickCount), 0)) - [long][SBS]::LastInput()) / 1000 }
function Games { Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like '*com.fs.starfarer.StarfarerLauncher*' } }
function KillGames { Games | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Start-Sleep 3 }
function Hits($log, $pat) { if (Test-Path $log) { (Select-String -Path $log -Pattern $pat | Measure-Object).Count } else { 0 } }
function Day($tag) {
  $ti = "$d\ti-$tag.txt"
  if (-not (Test-Path $ti)) { return $null }
  $c = Select-String -Path $ti -Pattern '^Clock: day (-?\d+) war (-?\d+)'
  if (-not $c) { return $null }
  [pscustomobject]@{ Run = [long]$c[$c.Count - 1].Matches[0].Groups[1].Value - [long]$c[0].Matches[0].Groups[1].Value
    War = [int]$c[$c.Count - 1].Matches[0].Groups[2].Value }
}
function Post($hwnd, $vk, $scan) {
  [SBS]::PostMessage($hwnd, 0x100, [IntPtr]$vk, [IntPtr](($scan -shl 16) -bor 1)) | Out-Null
  Start-Sleep -Milliseconds 80
  [SBS]::PostMessage($hwnd, 0x101, [IntPtr]$vk, [IntPtr]::new(([long]$scan -shl 16) -bor 1 -bor 0xC0000000L)) | Out-Null
}
function Restore {
  if ($KeepSettings) { return }
  powershell -NoProfile -ExecutionPolicy Bypass -File "$d\backup-20261002\restore.ps1" | Out-Null
  Say "user settings restored"
}

New-Item -ItemType Directory -Force $d | Out-Null
Remove-Item "$d\sbs-go.done", $st -ErrorAction SilentlyContinue
if (Get-Process LogonUI -ErrorAction SilentlyContinue) { Say "LOCKED - nothing run"; "done" | Set-Content "$d\sbs-go.done"; exit }
KillGames
# the run settings into the shared store (resolution, autosave off, Shift at 48x, the debug switches); every
# game's own copy of saves\common is taken after, so restore.ps1 at the end leaves the user's as it was
powershell -NoProfile -ExecutionPolicy Bypass -File "$ff\run-settings.ps1" -Save $base | Out-Null
$knobs = @{}
foreach ($part in ($Knobs -split '\|' | Where-Object { $_ })) { $t, $kv = $part -split ':', 2; $knobs[$t] = $kv }
$java = (Get-Content "$core\starsector.bat" -TotalCount 1)
# the windows side by side where the screen is wide enough, overlapping where it is not
[SBS]::SetProcessDPIAware() | Out-Null
$sw = [SBS]::GetSystemMetrics(0); $sh = [SBS]::GetSystemMetrics(1)
$rx = [Math]::Max(0, [Math]::Min(1720, $sw - 1620)); $ry = [Math]::Max(0, [Math]::Min(470, $sh - 990))
$slots = @(@(0, 0), @($rx, 0), @(0, $ry), @($rx, $ry))
$g = @()
$i = 0
foreach ($tag in $Tags) {
  $inst = "$root\$tag"; $log = "$inst\logs\starsector.log"; $name = "${base}sbs$tag"
  Remove-Item $inst -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item "$saves\$name" -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item "$d\ti-$tag.txt", "$d\exc-$tag.txt", "$d\tail-$tag.stop" -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force "$inst\saves", "$inst\logs" | Out-Null
  if (-not (Test-Path "$inst\logs")) { Say "$tag NO FOLDER at $inst"; continue }
  powershell -NoProfile -ExecutionPolicy Bypass -File "$ff\newgame-prep.ps1" -Base $base -To "sbs$tag" | Out-Null
  if (-not (Test-Path "$saves\$name\campaign.xml")) { Say "$tag CLONE FAILED"; continue }
  Move-Item "$saves\$name" "$inst\saves\$name"
  Copy-Item "$saves\common" "$inst\saves\common" -Recurse
  Get-ChildItem "$inst\saves\common" -Filter 'threatinc_sim*' | Remove-Item -Force
  if ($knobs[$tag]) {
    $store = "$inst\saves\common\LunaSettings\threatinc.json.data"
    $t = [IO.File]::ReadAllText($store)
    foreach ($kv in $knobs[$tag].Split(';')) {
      $k, $v = $kv.Split('=')
      $pat = '"' + [regex]::Escape($k) + '":\s*[^,\r\n}]+'
      if ($t -match $pat) { $t = [regex]::Replace($t, $pat, '"' + $k + '": ' + $v) }
      else { $at = $t.IndexOf('{'); $t = $t.Substring(0, $at + 1) + "`n" + '   "' + $k + '": ' + $v + ',' + $t.Substring($at + 1) }
      Say "$tag knob $k = $v"
    }
    [IO.File]::WriteAllText($store, $t, (New-Object System.Text.UTF8Encoding($false)))
  }
  # Continue at this game's clone (the prefs escape each capital with a slash), its own saves and logs
  $esc = -join ($name.ToCharArray() | ForEach-Object { if ([char]::IsUpper($_)) { "/$_" } else { "$_" } })
  Set-ItemProperty $key -Name continue -Value "..\saves\_sbs\$tag\saves\$esc"
  $line = $java -replace '-Dcom\.fs\.starfarer\.settings\.paths\.saves=\S+', "-Dcom.fs.starfarer.settings.paths.saves=../saves/_sbs/$tag/saves" `
    -replace '-Dcom\.fs\.starfarer\.settings\.paths\.logs=\S+', "-Dcom.fs.starfarer.settings.paths.logs=../saves/_sbs/$tag/logs"
  [IO.File]::WriteAllText("$inst\start.bat", $line + "`r`n", (New-Object System.Text.ASCIIEncoding))
  Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$ff\tail-ti.ps1`" -Tag $tag -FromStart -Log `"$log`"" -WindowStyle Hidden
  $x = $slots[$i % $slots.Count][0]; $y = $slots[$i % $slots.Count][1]; $i++
  Say "$tag launching at $x,$y"
  Start-Process -FilePath "$inst\start.bat" -WorkingDirectory $core -WindowStyle Minimized
  # its java process: the one whose command line names this game's saves folder
  $procId = 0; $deadline = (Get-Date).AddSeconds(60)
  do { Start-Sleep 1; $p = Games | Where-Object { $_.CommandLine -like "*_sbs/$tag/saves*" } | Select-Object -First 1; if ($p) { $procId = [uint32]$p.ProcessId } } while (-not $procId -and (Get-Date) -lt $deadline)
  if (-not $procId) { Say "$tag NO PROCESS"; continue }
  # the launcher: Play sits at the same share of it whatever the display scaling; retried while it is the window up
  $deadline = (Get-Date).AddSeconds(180); $hwnd = [IntPtr]::Zero; $placed = $false; $r = ""
  do {
    Start-Sleep 3
    $hwnd = [SBS]::Find($procId, "Starsector")
    if ($hwnd -eq [IntPtr]::Zero) { continue }
    $r = UI -Action rect -Hwnd $hwnd
    if ($r -match 'client (\d+)x(\d+)' -and [int]$Matches[1] -lt 1000) {
      if (-not $placed) { Place $hwnd ($x + 200) ($y + 150); $placed = $true }
      UI -Action click -Hwnd $hwnd -X ([int]([int]$Matches[1] * 298 / 597)) -Y ([int]([int]$Matches[2] * 254 / 373)) | Out-Null
      Start-Sleep 4
    }
  } while ($r -notlike "*client 1600x900*" -and (Get-Date) -lt $deadline)
  if ($r -notlike "*client 1600x900*") { Say "$tag NO GAME WINDOW ($r)"; Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue; continue }
  Place $hwnd $x $y
  if (-not (Test-Path $log)) { Say "$tag NO LOG at $log (the logs path was not taken)"; Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue; continue }
  $deadline = (Get-Date).AddSeconds(300)
  do { Start-Sleep 3 } while ((Hits $log "Reading save data from") -lt 1 -and (Get-Date) -lt $deadline)
  Start-Sleep 8
  $loaded = $false
  foreach ($xy in @(@(1290, 256), @(1290, 256), @(1190, 282))) {
    $hwnd = [SBS]::Find($procId, "Starsector")
    UI -Action click -Hwnd $hwnd -X $xy[0] -Y $xy[1] | Out-Null
    $deadline = (Get-Date).AddSeconds(60)
    do { Start-Sleep 3 } while ((Hits $log "Loading stage 39 - last") -lt 1 -and (Get-Date) -lt $deadline)
    if ((Hits $log "Loading stage 39 - last") -ge 1) { $loaded = $true; break }
  }
  if (-not $loaded) { Say "$tag NOT LOADED (Continue missed, or the save was not found)"; Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue; continue }
  Start-Sleep 6
  # paused after the load: a posted Space starts the clock (a second one if the first stopped it)
  $running = $false
  foreach ($try in 1..2) {
    $c0 = Hits "$d\ti-$tag.txt" '^Clock: day'
    Post $hwnd 0x20 0x39
    Start-Sleep 28
    if ((Hits "$d\ti-$tag.txt" '^Clock: day') -gt $c0) { $running = $true; break }
  }
  [SBS]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x13) | Out-Null
  Say ("$tag loaded, pid $procId, clock " + $(if ($running) { "running" } else { "NOT RUNNING" }))
  $g += [pscustomobject]@{ Tag = $tag; ProcId = $procId; Hwnd = $hwnd; Inst = $inst; Done = $false; Last = -1; LastAt = (Get-Date); Nudged = 0 }
}

if ($g.Count -gt 0) {
  $down = [IntPtr]0x002A0001
  $held = $false; $mark = 0; $t0 = Get-Date; $next = $t0; $ffSeconds = 0.0; $tick = Get-Date
  $start = @{}; foreach ($x in $g) { $c = Day $x.Tag; $start[$x.Tag] = if ($c) { $c.Run } else { 0 } }
  Say ("fast-forward: " + ($g | ForEach-Object { $_.Tag }) -join ', ')
  try {
    while ($true) {
      $now = Get-Date
      if (-not $held) {
        # hold Left Shift system-wide; what the hold itself stamps as the last input is the mark to compare with
        [SBS]::keybd_event(0xA0, 0x2A, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 150
        $held = $true; $mark = [SBS]::LastInput(); $tick = Get-Date
      } elseif ([SBS]::LastInput() -ne $mark) {
        # someone is at the machine: let go, and wait for it to fall quiet
        [SBS]::keybd_event(0xA0, 0x2A, 2, [UIntPtr]::Zero); $held = $false
        $ffSeconds += ((Get-Date) - $tick).TotalSeconds
        Say "keyboard or mouse touched: Shift released, waiting for $IdleSeconds s of quiet"
        do { Start-Sleep 5; $idle = Idle } while ($idle -lt $IdleSeconds -and ((Get-Date) - $t0).TotalMinutes -lt $MaxMinutes)
        foreach ($x in $g) { $x.LastAt = Get-Date }
        continue
      }
      foreach ($x in $g) { if (-not $x.Done) { [SBS]::PostMessage($x.Hwnd, 0x100, [IntPtr]0x10, $down) | Out-Null } }
      Start-Sleep -Milliseconds 250
      if ($now -lt $next) { continue }
      $next = $now.AddSeconds(20)
      foreach ($x in $g) {
        if ($x.Done) { continue }
        if (-not (Get-Process -Id $x.ProcId -ErrorAction SilentlyContinue)) { $x.Done = $true; Say "$($x.Tag) GAME GONE"; continue }
        $c = Day $x.Tag
        $run = if ($c) { $c.Run } else { 0 }
        if ($run -gt $x.Last) { $x.Last = $run; $x.LastAt = $now; $x.Nudged = 0 }
        elseif (($now - $x.LastAt).TotalSeconds -gt 60 -and $x.Nudged -lt 3) {
          # the clock stands: a dialog (Enter, Escape), then a pause (Space)
          $x.Nudged++; $x.LastAt = $now
          if ($x.Nudged -eq 2) { Post $x.Hwnd 0x20 0x39 } else { Post $x.Hwnd 0x0D 0x1C; Start-Sleep 1; Post $x.Hwnd 0x1B 0x01 }
          Say "$($x.Tag) clock stands at day ${run}: nudge $($x.Nudged)"
        }
        if ($TrialSeconds -le 0 -and $run -ge $Days) {
          $x.Done = $true
          Stop-Process -Id $x.ProcId -Force -ErrorAction SilentlyContinue
          Say "$($x.Tag) REACHED $run days, war day $($c.War)"
        }
      }
      $el = ((Get-Date) - $t0).TotalSeconds
      if (-not ($g | Where-Object { -not $_.Done })) { break }
      if ($TrialSeconds -gt 0 -and $el -ge $TrialSeconds) { break }
      if ($el / 60 -ge $MaxMinutes) { Say "MAX MINUTES"; break }
    }
  } finally {
    [SBS]::keybd_event(0xA0, 0x2A, 2, [UIntPtr]::Zero)
    if ($held) { $ffSeconds += ((Get-Date) - $tick).TotalSeconds }
  }
  foreach ($x in $g) {
    $c = Day $x.Tag
    $run = if ($c) { $c.Run } else { 0 }
    Say ("{0}: {1} days in {2:0} s of fast-forward ({3:0} a minute), war day {4}" -f $x.Tag, ($run - $start[$x.Tag]), $ffSeconds,
      $(if ($ffSeconds -gt 0) { ($run - $start[$x.Tag]) * 60 / $ffSeconds } else { 0 }), $(if ($c) { $c.War }))
  }
}
foreach ($tag in $Tags) { "stop" | Set-Content "$d\tail-$tag.stop" }
Start-Sleep 5
KillGames
foreach ($x in $g) {
  $dump = @(Get-ChildItem "$($x.Inst)\saves\common" -Filter 'threatinc_sim*' -ErrorAction SilentlyContinue)
  if ($TrialSeconds -le 0 -and $dump.Count -gt 0) {
    $val = "$mod\tools\warsim\validation\$($x.Tag)"
    New-Item -ItemType Directory -Force $val | Out-Null
    $dump | Copy-Item -Destination $val -Force
    powershell -NoProfile -ExecutionPolicy Bypass -File "$ff\guard-digest.ps1" -Tag $x.Tag | Out-File -Encoding utf8 "$d\digest-$($x.Tag).txt"
  }
  Say "$($x.Tag) dumps: $($dump.Count)"
  # the clone is not kept (21 MB a game); the log and the dumps are
  Remove-Item "$($x.Inst)\saves\${base}sbs$($x.Tag)" -Recurse -Force -ErrorAction SilentlyContinue
}
Restore
Say "done"
"done" | Set-Content "$d\sbs-go.done"
