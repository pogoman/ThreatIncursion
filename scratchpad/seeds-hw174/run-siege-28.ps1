# hw191-hw196 (2026-10-18 10:45, the rerun of hw189-hw194 after the daily strike's nothing-to-land gate was fixed to read Math.max(worldShare(), troopsAboard) - hw189 landed whole strikes on arrival unbombarded; original header: hw189-hw194 (2026-10-18 09:50, the user: "run tests from random seeds from just before first strike to test siege
# changes on balance"): six rounds on the merged jar 0fcb269e (built 09:45 - the other machine's 2026-10-17/18 defenders
# build: swarm strikes under the ground doctrine run a day at a time, Reinforce convoys, convoys hold off at a
# Threat blockade, defender arms, Push / Dig in stance, marineCounterAttackMult 1.0, frontOverrunMusterDays 10).
# The recipe is run-prewar.ps1's (s53): each game resumes a sector's last 300-day snapshot before its opening strike
# (hw182/hw183, openings w1,996-2,226) and runs 1,800 days to ~w3,800; set A / set B alternate, letters rotate.
# Baselines on the same snapshots: hw185 (A: AmaruDugas 127 / PoseidonDeimos 118 / AresDaniels 85) and
# hw186 (B: OceanPena 115 / ZhiFarah 138 / AphelionDysnomia 185), both the swarm's on the a961b808 jar.
$ff = "C:\Program Files (x86)\Fractal Softworks\Starsector\mods\ThreatIncursion\tools\test-harness\fastforward"
$dir = $PSScriptRoot
$log = Join-Path $dir "siege.log"
$out = Join-Path $env:TEMP "threatinc-tests"
$rounds = 3
$setA = @("save_AmaruDugas_2921423183749615243hw182ad1998", "save_PoseidonDeimos_8594169485512677365hw182bd2013", "save_AresDaniels_5500560879992783035hw182cd1913")
$setB = @("save_OceanPena_1024894203100914406hw183ad1988", "save_ZhiFarah_3202965389391319559hw183bd1723", "save_AphelionDysnomia_4103534775338436064hw183cd2199")
function Log($m) { Add-Content -Path $log -Value ("{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $m) }
Log "hw229-hw230 (S1, the user 15:30 after hw227 88 / - / 176 hives with 0 of 424 staged hammers ever filled): STAGING IS THE BASE'S COMMITMENT (a play's staging outlives the play until a siege sails from the base or 180 d pass), the hammer waits while its refused stock rises (a tenth of the need a 30-d window), two staging hammers a faction at once, one a base - on the allied staging + convoys only build, jar 15:37; set B then set A"
$n = 229
$round = 1
while ($round -lt $rounds) {
  $set = if ($round % 2 -eq 0) { $setA } else { $setB }
  $shift = [math]::Floor($round / 2) % 3
  $tag = "hw$n"
  $letters = @("a", "b", "c")
  $pairs = @(); $tags = @()
  for ($i = 0; $i -lt 3; $i++) { $t = "$tag$($letters[$i])"; $tags += $t; $pairs += "$t=$($set[($i + $shift) % 3])" }
  $bases = $pairs -join ";"
  Log "ROUND $tag start: $bases"
  Remove-Item (Join-Path $out "sbs-go.done") -ErrorAction SilentlyContinue
  powershell -NoProfile -ExecutionPolicy Bypass -File "$ff\sbs.ps1" -Tags ($tags -join ",") -Bases $bases -Days 1800 -Snapshots 300 -Resolution 1920x1080 -MaxMinutes 90
  Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Copy-Item (Join-Path $out "sbs-status.txt") (Join-Path $dir "sbs-status-$tag.txt") -ErrorAction SilentlyContinue
  Log "ROUND $tag end"
  $n++; $round++
  Start-Sleep -Seconds 20
}
Log "done: $round rounds"
New-Item -ItemType File -Force (Join-Path $dir "siege28.done") | Out-Null
