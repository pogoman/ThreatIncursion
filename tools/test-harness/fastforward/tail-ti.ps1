# Follows starsector.log from where it stands now and appends the [ThreatInc] lines (prefix stripped)
# to ti-<Tag>.txt and exception/error lines (with the first stack frames) to exc-<Tag>.txt in the
# output dir ($env:THREATINC_TEST_OUT, default %TEMP%\threatinc-tests). Start it before launch.ps1
# and leave it running: unlike extract.ps1 it never depends on how many rolled-over logs the game
# keeps (three, 50 MB each - a long run with debugLogging can write more). Ends when
# tail-<Tag>.stop appears in the output dir.
param([string]$Tag = "x", [switch]$FromStart, [string]$Log = "")
# -Log: the log of one of several games running side by side (its own logs folder)
$log = if ($Log) { $Log } else { "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core\starsector.log" }
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
New-Item -ItemType Directory -Force $out | Out-Null
$ti = "$out\ti-$Tag.txt"; $ex = "$out\exc-$Tag.txt"; $stop = "$out\tail-$Tag.stop"
$dec = [System.Text.Encoding]::UTF8.GetDecoder()
$enc = New-Object System.Text.UTF8Encoding($false)
$script:carry = ""; $script:trace = 0
function Bytes($path, [long]$from) {
  if (-not (Test-Path $path)) { return ,(New-Object byte[] 0) }
  $fs = New-Object System.IO.FileStream($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  try {
    if ($from -ge $fs.Length) { return ,(New-Object byte[] 0) }
    $fs.Position = $from
    $b = New-Object byte[] ([int][Math]::Min($fs.Length - $from, 64MB))
    $n = $fs.Read($b, 0, $b.Length)
    if ($n -lt $b.Length) { [Array]::Resize([ref]$b, $n) }
    return ,$b
  } finally { $fs.Close() }
}
function Take([byte[]]$b) {
  if ($b.Length -eq 0) { return }
  $c = New-Object char[] ($dec.GetCharCount($b, 0, $b.Length))
  $n = $dec.GetChars($b, 0, $b.Length, $c, 0)
  $parts = ($script:carry + (New-Object string ($c, 0, $n))) -split "`n"
  $script:carry = $parts[$parts.Count - 1]
  $t = New-Object System.Collections.Generic.List[string]
  $e = New-Object System.Collections.Generic.List[string]
  for ($i = 0; $i -lt $parts.Count - 1; $i++) {
    $l = $parts[$i].TrimEnd("`r")
    $k = $l.IndexOf("[ThreatInc] ")
    if ($k -ge 0) { $t.Add($l.Substring($k + 12)); $script:trace = 0 }
    elseif ($l -cmatch "Exception|ERROR") { $e.Add($l); $script:trace = 8 }
    elseif ($script:trace -gt 0 -and $l -match "^\s+at |^Caused by") { $e.Add($l); $script:trace-- }
    else { $script:trace = 0 }
  }
  if ($t.Count -gt 0) { [System.IO.File]::AppendAllLines($ti, $t, $enc) }
  if ($e.Count -gt 0) { [System.IO.File]::AppendAllLines($ex, $e, $enc) }
}
[long]$pos = if ($FromStart -or -not (Test-Path $log)) { 0 } else { (Get-Item $log).Length }
while (-not (Test-Path $stop)) {
  $len = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
  if ($len -lt $pos) {
    # rolled over: what was left unread is now the tail of .1
    Take (Bytes "$log.1" $pos)
    $pos = 0
  }
  if ($len -gt $pos) { $b = Bytes $log $pos; $pos += $b.Length; Take $b }
  Start-Sleep -Seconds 2
}
Take (Bytes $log $pos)
Remove-Item $stop -Force -ErrorAction SilentlyContinue
