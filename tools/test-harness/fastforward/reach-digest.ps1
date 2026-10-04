# Digest of a run's billed-reach behaviour from ti-<Tag>.txt (extract.ps1): the monthly Reach lines,
# launches, holds, claims, waves, scouts, raids, stance, upkeep, census, the war council, the swarm's fog.
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
# the war council (docs/war-council.md section 16)
Show "Council strategy" '^Council \S+: strategy ' 30
Show "Council focus" '^Council \S+: \S+ focus ' 20
Show "Council picture" '^Council \S+: picture ' 12 -Last
Show "Council stance" 'war council: ' 20
Show "Play phases" '^Play \S+ \S+ \S+ at ' 60
Show "Play outcomes" '^Play \S+: (success|failure|neutral) ' 40
Show "Play forces" '^Play \S+ force ' 20
Show "Play raids" '^Play \S+ \S+: raid on ' 20
Show "Council errors" '^(Council \S+|Plays): error ' 10
# the swarm's fog of war (docs/threat-fog.md): first sightings by source, convoys among them, places, census
Show "Swarm sees by eyes" '^Swarm intel: sees .* by eyes\b' 20
Show "Swarm sees by picket" '^Swarm intel: sees .* by picket\b' 20
Show "Swarm sees by scout" '^Swarm intel: sees .* by scout\b' 20
Show "Swarm sees convoys" '^Swarm intel: sees \S+ convoy of ' 12
Show "Swarm places" '^Swarm intel: (eyes|radar|scout) on ' 30
Show "Swarm census" '^Swarm intel census:' 12 -Last
