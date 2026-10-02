# Digest of a run's ti-<Tag>.txt for the two questions the war simulator asks of a real run
# (docs/war-sim-rounds.md 16a, handover-2026-10-02.md 3): how often a forward base's guard is
# called against a seen strike (ThreatFrontlines.callGuard) and what it meets, and what the NPC
# sieges are postponed for (IncursionManager.launchSiegeExpedition's gates), beside what sailed.
param([string]$Tag = "x", [int]$N = 6, [int]$W = 220)
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
$L = [System.IO.File]::ReadAllLines("$out\ti-$Tag.txt")
function Cut($s) { if ($s.Length -gt $W) { $s.Substring(0, $W) + "..." } else { $s } }
function Count($pat) { @($L | Where-Object { $_ -match $pat }).Count }
function Show($title, $pat, [int]$n = $N) {
  $m = @($L | Where-Object { $_ -match $pat })
  "=== $title ($($m.Count))"
  $m | Select-Object -First $n | ForEach-Object { Cut $_ }
}
$clock = @($L | Where-Object { $_ -match '^Clock: day' })
if ($clock.Count) { "run: $($clock.Count) game days, $($clock[0]) .. $($clock[$clock.Count - 1])" }

"##### Guards called against a seen strike"
"rear link garrisoned/reinforced on a strike on its way : " + (Count '^Frontline: .* (garrisons|reinforces) .*\(rear; strike on its way')
"front link garrisoned/reinforced (strike in reach)      : " + (Count '^Frontline: .* (garrisons|reinforces) .*\(front; strike in reach')
"guards behind the front sent                            : " + (Count '^Frontline: \S+ sent .* the guards behind the front')
"guard sailing home turned back                          : " + (Count '^Frontline: the guard sailing home from .* turned back')
"faces the strike short, no guard called (at detection)  : " + (Count '^Frontline: .* faces the strike \d+ short')
"cannot guard (later polls, logged once a link)          : " + (Count '^Frontline: \S+ cannot guard ')
"--- why no guard was called"
$L | Where-Object { $_ -match '^Frontline: .* faces the strike \d+ short .* no guard called: (.*?) \(strike in' } |
  ForEach-Object { $Matches[1] -replace 'no fleet came out of .*', 'no fleet came out of <base>' } | Group-Object | Sort-Object Count -Descending |
  ForEach-Object { "{0,6}  {1}" -f $_.Count, (Cut $_.Name) }
Show "samples: rear call" '^Frontline: .* (garrisons|reinforces) .*\(rear; strike on its way' 3
Show "samples: faces the strike" '^Frontline: .* faces the strike \d+ short' 3
Show "Frontline lines of any other kind (first words)" '^Frontline: ' 0
$L | Where-Object { $_ -match '^Frontline: ' } | ForEach-Object {
  if ($_ -match '(garrisons|reinforces|faces the strike|cannot guard|turned back|guards behind the front|founded|lost|destroyed|abandon\w*|went home|sails home|stands down|relieved)') { $Matches[1] } else { ($_ -split ' ')[2..4] -join ' ' }
} | Group-Object | Sort-Object Count -Descending | Select-Object -First 25 | ForEach-Object { "{0,6}  {1}" -f $_.Count, $_.Name }

"##### Sieges: postponed for what"
$post = @($L | Where-Object { $_ -match '^Expedition postponed at ' })
"postponements logged: $($post.Count)  (logQuiet: one line a base-target pair until it changes)"
$make = @{ orbit = 0; marines = 0; armaments = 0; soften = 0; strength = 0; bound = 0; fuel = 0; supplies = 0; both = 0; other = 0 }
$fuelRatio = New-Object System.Collections.Generic.List[double]; $supRatio = New-Object System.Collections.Generic.List[double]
foreach ($p in $post) {
  if ($p -match 'FP of Defense Swarms over') { $make.orbit++ }
  elseif ($p -match 'marines available') { $make.marines++ }
  elseif ($p -match 'armaments available') { $make.armaments++ }
  elseif ($p -match 'cannot soften it enough') { $make.soften++ }
  elseif ($p -match 'ground strength the siege sails with') { $make.strength++ }
  elseif ($p -match ': (\d+)/(\d+) fuel.*?, (\d+)/(\d+) supplies') {
    $f = [double]$Matches[1] / [Math]::Max(1, [double]$Matches[2]); $s = [double]$Matches[3] / [Math]::Max(1, [double]$Matches[4])
    $fuelRatio.Add($f); $supRatio.Add($s)
    if ($f -lt 1 -and $s -lt 1) { $make.both++ } elseif ($f -lt 1) { $make.fuel++ } elseif ($s -lt 1) { $make.supplies++ } else { $make.other++ }
  }
  elseif ($p -match 'FP the .* needs') { $make.bound++ }
  else { $make.other++ }
}
"orbit outweighed {0} | marines {1} | armaments {2} | cannot soften {3} | ground strength unpaid {4} | bound's FP unpaid {5}" -f $make.orbit, $make.marines, $make.armaments, $make.soften, $make.strength, $make.bound
"provisions: fuel short only {0} | supplies short only {1} | both short {2} | other {3}" -f $make.fuel, $make.supplies, $make.both, $make.other
function Med($l) { if ($l.Count -eq 0) { return "-" }; $a = $l.ToArray(); [Array]::Sort($a); "{0:0.00}" -f $a[[int]($a.Length / 2)] }
"provisions lines: median fuel have/need $(Med $fuelRatio), median supplies have/need $(Med $supRatio)"
Show "samples: provisions" '^Expedition postponed at .*\d+/\d+ fuel' 4
Show "samples: other postponements" '^Expedition postponed at (?!.*(\d+/\d+ fuel|Defense Swarms over))' 4

"##### What sailed and what it achieved"
foreach ($k in @(
    @("sieges launched", '(?i)^(Siege|Expedition) (launched|sails|sailed)|launched (a |the )?siege'),
    @("attack planner passes", '^Hive planner'),
    @("council plays begun", '^Play \S+ \S+ \S+ at '),
    @("play outcomes: success", '^Play \S+: success '),
    @("play outcomes: failure", '^Play \S+: failure '),
    @("play outcomes: neutral", '^Play \S+: neutral '),
    @("hives eradicated", '^Colony eradicated'),
    @("strikes launched", '(?i)strike launched'),
    @("forward bases founded", '(?i)^Frontline: .*(founded|establish)'),
    @("forward bases lost", '(?i)^Frontline: .*(lost|destroyed|fell)'))) {
  "{0,-28} {1}" -f $k[0], (Count $k[1])
}
Show "Play outcomes" '^Play \S+: (success|failure|neutral) ' 30
Show "Eradicated" '^Colony eradicated' 30
Show "Census threat (last)" '^Census: threat' 0
$L | Where-Object { $_ -match '^Census: threat' } | Select-Object -Last 3 | ForEach-Object { Cut $_ }
