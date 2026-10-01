# Prepares a new-game test save: clones the pristine new game (clone.ps1: supplies and fuel refilled,
# Continue pointed at it) and sets pirates and Pathers neutral to the player. For the Threat, which
# the mod pins hostile, turn on Debug > Fleets Ignore You (player-ignored.ps1 on) for the run.
param([Parameter(Mandatory = $true)][string]$Base, [Parameter(Mandatory = $true)][string]$To)
powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\clone.ps1" -Base $Base -To $To
# a clone that did not happen (the target exists) must not have its save rewritten
if ($LASTEXITCODE -ne 0) { "clone failed - nothing edited"; exit 1 }
$f = "C:\Program Files (x86)\Fractal Softworks\Starsector\saves\$Base$To\campaign.xml"
if (-not (Test-Path $f)) { "no save at $f"; exit 1 }
$lines = [System.IO.File]::ReadAllLines($f)
$n = 0
for ($i = 0; $i -lt $lines.Count - 5; $i++) {
  $t = $lines[$i].Trim()
  if ($t -ne '<st>pirates_player</st>' -and $t -ne '<st>player_pirates</st>' -and $t -ne '<st>luddic_path_player</st>' -and $t -ne '<st>player_luddic_path</st>') { continue }
  if ($lines[$i+1] -notmatch '<FMRelation z=' -or $lines[$i+4] -notmatch '<value>') { continue }
  "$t : $($lines[$i+4].Trim()) -> <value>0.0</value>"
  $lines[$i+4] = $lines[$i+4] -replace '<value>[-\d\.E]+</value>', '<value>0.0</value>'
  $n++
}
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($f, $lines, $enc)
"relations edited: $n"
