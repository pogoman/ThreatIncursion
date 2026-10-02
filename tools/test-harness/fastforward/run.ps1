# Fast-forward the loaded campaign: Chunks x Seconds of held Shift, quicksave every SaveEvery
# chunks. After each chunk prints ThreatInc log lines written, window state, and a small shot
# (to $env:THREATINC_TEST_OUT, default %TEMP%\threatinc-tests).
param([int]$Chunks = 5, [int]$Seconds = 110, [int]$SaveEvery = 5, [string]$Tag = "c", [switch]$Unpause)
$h = Split-Path $PSScriptRoot -Parent
$log = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core\starsector.log"
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
New-Item -ItemType Directory -Force $out | Out-Null
function UI { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\ui.ps1" @args }
function Count { (Select-String -Path $log -Pattern "\[ThreatInc\]" | Measure-Object).Count }
function LastCensus { $l = Select-String -Path $log -Pattern "Census: threat" | Select-Object -Last 1; if ($l) { $t = $l.Line -replace '^.*Census: ', ''; $t.Substring(0, [Math]::Min(70, $t.Length)) } }

powershell -NoProfile -ExecutionPolicy Bypass -File "$h\place.ps1" -X 0 -Y 0 | Out-Null
if ($Unpause) { UI -Action key -Text " " | Out-Null; Start-Sleep 1 }
else {
  # probe: if 20 s of fast-forward writes nothing, the clock is paused - one space starts it
  $p0 = Count
  $hd = powershell -NoProfile -ExecutionPolicy Bypass -File "$h\hold.ps1" -Key shift -Seconds 20
  # another window took the foreground: someone is using the machine - leave it to them
  if ("$hd" -like "*FOREGROUND LOST*") { "FOREGROUND LOST - stopping ($hd)"; return }
  if ((Count) -eq $p0) { UI -Action key -Text " " | Out-Null; Start-Sleep 1; "probe: was paused, unpaused" } else { "probe: running" }
}
for ($i = 1; $i -le $Chunks; $i++) {
  $c0 = Count
  $hd = powershell -NoProfile -ExecutionPolicy Bypass -File "$h\hold.ps1" -Key shift -Seconds $Seconds
  if ("$hd" -like "*FOREGROUND LOST*") { "FOREGROUND LOST - stopping ($hd)"; break }
  $r = UI -Action rect
  $c1 = Count
  $shot = "$out\$Tag-$i.png"
  UI -Action shot -Out $shot -Scale 0.4 | Out-Null
  "{0} chunk {1}: +{2} ThreatInc lines | {3} | {4}" -f (Get-Date -Format HH:mm:ss), $i, ($c1 - $c0), $r, (LastCensus)
  if ($r -notlike "*1600x900*") { "WINDOW LOST - stopping"; break }
  # a rolled-over log reads negative; not a stall
  if ($c1 -ge $c0 -and ($c1 - $c0) -lt 5) {
    # a dialog (accident report, encounter) stops the clock: dismiss it once and retry
    UI -Action key -Text "{ENTER}" | Out-Null; Start-Sleep 1; UI -Action key -Text "{ESC}" | Out-Null; Start-Sleep 1
    $p0 = Count
    powershell -NoProfile -ExecutionPolicy Bypass -File "$h\hold.ps1" -Key shift -Seconds 20 | Out-Null
    if ((Count) -eq $p0) { UI -Action key -Text " " | Out-Null; Start-Sleep 1; powershell -NoProfile -ExecutionPolicy Bypass -File "$h\hold.ps1" -Key shift -Seconds 20 | Out-Null }
    if ((Count) -eq $p0) { "LITTLE PROGRESS - clock stopped, dismiss failed; see $shot"; break }
    "  stall dismissed, continuing"
  }
  if ($i % $SaveEvery -eq 0) {
    $cp = (Get-ItemProperty 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer').continue -replace '^\.\.\\saves\\', '' -replace '/', ''
    $cx = "C:\Program Files (x86)\Fractal Softworks\Starsector\saves\$cp\campaign.xml"
    $t0 = if (Test-Path $cx) { (Get-Item $cx).LastWriteTime } else { $null }
    UI -Action key -Text "{F5}" | Out-Null; Start-Sleep 20
    $t1 = if (Test-Path $cx) { (Get-Item $cx).LastWriteTime } else { $null }
    if ($t1 -ne $t0) { "  quicksaved" } else { "  QUICKSAVE NOT WRITTEN ($cx)" }
  }
}
