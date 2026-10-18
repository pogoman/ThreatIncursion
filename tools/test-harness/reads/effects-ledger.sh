#!/bin/bash
# effects-ledger.sh <tag>[a|b|c ...] - attempts against effects for every logged mechanism, per game, from
# %LOCALAPPDATA%/Temp/threatinc-tests/ti-<tag><g>.txt. A ratio of 0 or 100 percent is a bug candidate: a path that
# fires and does nothing (the daily strike's outpost exit, hw191: 36 of 36 did nothing) or a path that never fires.
# Written 2026-10-18 after two such bugs were found only because the user asked about the mechanism (facts.md).
T="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"; tag=$1; shift; games=${*:-a b c}
c() { grep -c -- "$1" "$F"; }
row() { printf '  %-44s %6s %6s  %s\n' "$1" "$2" "$3" "$4"; }
for g in $games; do
  F="$T/ti-$tag$g.txt"; [ -f "$F" ] || continue
  echo "== $tag$g $(grep -o 'war [0-9]*' "$F" | tail -1), $(wc -l < "$F") lines, $(grep -ci exception "$F") exceptions"
  printf '  %-44s %6s %6s  %s\n' mechanism tried effect note
  row "swarm strike -> daily takeover"        "$(c '^Strike launched')" "$(c 'Daily strike takes over')" "strikes launched vs sieges taken over"
  row "daily siege at a base -> assault"      "$(c 'Daily strike at .*outpost')" "$(c 'Station assault on')" "outpost exits vs assaults (hw191: N vs 0 = the bug)"
  row "base assault -> base destroyed"        "$(c 'Station assault on')" "$(c 'station destroyed by a Threat strike')" ""
  row "daily siege -> landed"                 "$(c 'Daily strike at .*fight days')" "$(c 'Daily strike at .*landed')" "all daily summaries vs landed (ready/dry)"
  row "landing -> Threat ground victory"      "$(c 'Notice: Threat Landing')" "$(c '^Threat ground victory')" ""
  row "drops under the guns -> marines lost"  "$(c '^Drop at')" "$(grep -h '^Drop at' "$F" | grep -o 'lost [0-9]*' | awk '{s+=$2} END{print s+0}')" "landings the batteries fired on vs marines they took (dropLoss, 2026-10-18); of $(grep -h '^Drop at' "$F" | grep -o 'of [0-9]* marines' | awk '{s+=$2} END{print s+0}') dropped"
  row "human siege -> landed"                 "$(c '^Daily siege of ')" "$(c 'landed (dry)\|landed (ready)')" "purge daily summaries vs landings"
  row "human landing -> core destroyed"       "$(c 'landed (dry)\|landed (ready)')" "$(c '^Colony eradicated:')" ""
  row "counter-attack -> repelled"            "$(c '^Counter-attack at')" "$(c '^Counter-attack repelled')" "0 repelled is the NPC stance AI digging in when it would lose"
  row "counter-attack -> overran"             "$(c '^Counter-attack at')" "$(c 'overran the beachhead')" ""
  row "NPC defenders dig in / push"           "$(c '^Marines of .* dig in')" "$(c '^Marines of .* push')" "tickDefenderStance; no line = the AI never ran"
  row "front braces -> resumes its assault"   "$(c '^Front at .* braces for')" "$(c '^Front at .* resumes its assault')" "flapping worlds (>5 braces): $(grep -h '^Front at .* braces for' "$F" | sed -E 's/^Front at (.*) braces.*/\1/' | sort | uniq -c | awk '$1>5' | wc -l) - hw194: brace and NPC dig-in chased each other daily"
  row "convoy dispatched -> arrived"          "$(c '^Convoy dispatched')" "$(c '^Convoy arrived')" ""
  row "convoy held at blockade -> ran in"     "$(c '^Convoy holding off')" "$(c '^Convoy runs in to')" "gave up: $(c '^Convoy gave up at')"
  row "relief dispatched -> bombard days"     "$(c '^Relief convoy:')" "$(c 'bombard the Threat army')" ""
  row "front reinforced (resupply landing)"   "$(c 'Siege pass (reinforce)')" "$(c '^Front reinforced at')" ""
  row "feeding swarm -> landed (hive upkeep)"  "$(c '^Feeding swarm .* -> ')" "$(c '^Feeding swarm landed')" "tangible feeding 2026-10-18; lost $(c '^Feeding swarm lost'), held $(c '^Feeding: no hive can spare')"
  row "feeding convoy -> absorbed (base upkeep)" "$(c '^Feeding convoy [a-z_]* ')" "$(c '^Feeding convoy absorbed')" "held $(c '^Feeding: no market of')"
  row "hive cover issues"                      "$(c '^Hive cover: .* issues')" "-" "hive cover 2026-10-18; cannot cover $(c 'cannot cover one')"
  row "core colonies starved down (size upkeep)" "$(c '^Core upkeep: .* starved down')" "-" "core size upkeep 2026-10-18"
  row "core build starts (producers + military)" "$(c '^Core build: .* \(builds\|answers\|upgrades\) ')" "-" "ThreatCoreBuild 2026-10-18; holds $(c '^Core build: .* holds ')"
  row "bases founded"                           "$(c '^Frontline: .* founded ')" "-" "founded as needed 2026-10-18"
  row "relief leash recalls (at hold range)"  "$(c '^Relief over .*strayed')" "$(grep '^Relief over .*strayed [0-9]* units' "$F" | grep -o 'strayed [0-9]* units' | awk '$2<3000' | wc -l)" "recalled at < 2x hold range: the hw186c leash defect"
  row "swarm defend leash recalls"            "$(c '^Swarm defend .*strayed')" "$(grep '^Swarm defend .*strayed [0-9]* units' "$F" | grep -o 'strayed [0-9]* units' | awk '$2<3000' | wc -l)" "same"
  row "reinforcement sent -> arrived"         "$(c '^Posture: .* sent [0-9]* FP to')" "$(c '^Reinforcement arrived')" ""
  row "founding convoy -> base founded"       "$(c '^Founding convoy')" "$(c '^Frontline: .* founded')" ""
  row "garrison musters"                      "$(c '^Garrison musters')" "-" "no effect line logged"
  row "defenders dry -> rearmed"              "$(c '^Defenders of .* dry:')" "$(c '^Defenders of .* rearmed:')" "burnDefenderArms (logged since the 13:03 jar); landings logged: $(c '^Defenders of .*: [0-9]* armed marines')"
done
