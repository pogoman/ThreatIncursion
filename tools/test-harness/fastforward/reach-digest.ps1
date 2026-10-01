# Digest of a run's billed-reach behaviour from ti-<Tag>.txt (extract.ps1): the monthly Reach lines,
# launches, holds, claims, waves, scouts, raids, stance, upkeep, census.
param([string]$Tag = "x", [int]$N = 12, [int]$W = 230)
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
$L = [System.IO.File]::ReadAllLines("$out\ti-$Tag.txt")
function Cut($s) { if ($s.Length -gt $W) { $s.Substring(0, $W) + "..." } else { $s } }
function Show($title, $pat, [int]$n = $N, [switch]$Last) {
  $m = @($L | Where-Object { $_ -match $pat })
  "=== $title ($($m.Count))"
  $pick = if ($Last) { $m | Select-Object -Last $n } else { $m | Select-Object -First $n }
  $pick | ForEach-Object { Cut $_ }
}
Show "Reach monthly" '^Reach:' 40
Show "Strike launched" '(?i)strike launched' 30
Show "Strike waits (supplies)" '(?i)strikes? from .* (wait|held)' 8
Show "War mode" '^War mode: ' 12
Show "Spread to" '^Spread to' 20
Show "Seeding Swarm" 'Seeding Swarm from' 20
Show "Scouting" 'Scouting Swarm from' 12
Show "Raids" 'Raider detached' 10
Show "Stance" '^Stance' 12 -Last
Show "Colony upkeep" '^Colony upkeep' 6 -Last
Show "Census threat" '^Census: threat' 6 -Last
Show "Eradicated" '^Colony eradicated' 30
Show "Planner" '^Hive planner' 30
