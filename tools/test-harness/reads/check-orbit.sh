#!/bin/bash
# check-orbit.sh [hw] - the hw161 hypotheses (handover-2026-10-12-orbit-contest-testing.md section 2):
# A. starved guns weighed: hive siegeSlices under a full blockade keep a defence above the bare anchor and a
#    return fire (loss > 0) - before, defence fell to 400 x strata and loss read 0 from the first day;
# B. the swarm's orbit bombardment runs: "Swarm bombards the <owner> front at <hive>: N FP over the orbit, M
#    marines a day ..." once a day, M = 0.05 x N / cover (cover 1..1.5), fuel 2.86 x N a day paid from the
#    stock (or "Swarm bombardment over <hive> held: no fuel"), "Front at <hive> (<owner>) destroyed by swarm
#    orbital bombardment" when it grinds one out; reinforcements "engage" on arrival before they are seated.
# Prints per game; flags nothing (the signals are counts to read, the war's shape is the watcher's).
hw=${1:-hw161}; T="$LOCALAPPDATA/Temp/threatinc-tests"
for g in a b c; do
  F=$T/ti-$hw$g.txt; [ -f "$F" ] || continue
  war=$(grep -o 'war [0-9]*' "$F" | tail -1 | cut -d' ' -f2)
  echo "== $hw$g war day ${war:-0}: $(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'hives [0-9]*\|fleets [0-9]* FP\|fuel [0-9]* (+[0-9]*/mo, spent [0-9]*)\|supplies [0-9]* (+[0-9]*/mo' | tr '\n' ' ')"
  echo "   humans: $(for f in hegemony independent persean tritachyon luddic_church sindrian_diktat; do grep "^Census: $f " "$F" | tail -1 | grep -o 'colonies [0-9]*' | sed "s/colonies/$f/"; done | tr '\n' ' ')"
  # A. hive slices
  hs=$(grep -c '^siegeSlice .*\[hive\]' "$F"); cs=$(grep -c '^siegeSlice .*\[colony\]' "$F")
  hl=$(grep '^siegeSlice .*\[hive\]' "$F" | grep -vc 'loss=0.00')
  cut100=$(grep -c '^Blockade of .*imports cut 100%' "$F"); cutany=$(grep -c '^Blockade of' "$F")
  echo "   A. hive slices $hs (loss>0 on $hl), colony slices $cs; blockade lines $cutany (100% cut $cut100)"
  grep '^siegeSlice .*\[hive\]' "$F" | sed -E 's/.*fp=([0-9]+) defence=([0-9]+) rate=([0-9.]+) days=[0-9.]+ condition=([0-9.]+) loss=([0-9.]+).*/\1 \2 \3 \4 \5/' \
    | awk '{n++; if($4>=0.95){h++; dh+=$2} else {l++; dl+=$2}; if($5>0)L++} END{if(n) printf "      condition>=0.95: %d slices, mean defence %.0f; worn: %d slices, mean defence %.0f\n", h, (h?dh/h:0), l, (l?dl/l:0)}'
  grep '^siegeSlice .*\[hive\]' "$F" | tail -2 | sed 's/^/      /'
  # B. swarm bombardment
  sb=$(grep -c '^Swarm bombards the' "$F"); held=$(grep -c '^Swarm bombardment over .* held: no \(fuel\|ordnance\)' "$F")
  fd=$(grep -c 'destroyed by swarm orbital bombardment' "$F"); rl=$(grep -c 'destroyed by relief bombardment' "$F")
  eng=$(grep -c '^Reinforcement engages at' "$F"); arr=$(grep -c '^Reinforcement arrived at' "$F")
  cover=$(grep -c '^Orbit cover over .* lost' "$F"); holds=$(grep -c '^Orbit cover over .*: the .* holds it' "$F")
  echo "   B. swarm bombard days $sb, held no fuel $held, fronts destroyed by swarm bombardment $fd (by relief bombardment $rl); reinforcements engage $eng / arrived $arr; orbit cover lost $cover, human flotilla holds $holds"
  if [ "$sb" -gt 0 ]; then
    grep '^Swarm bombards the' "$F" | sed -E 's/.*: ([0-9]+) FP (armed of [0-9]+ )?over the orbit, ([0-9]+) marines a day, front ([0-9]+) marines, held ([0-9]+) d, fuel ([0-9]+) a day/\1 \3 \4 \5 \6/' \
      | awk '{n++; fp+=$1; m+=$2; fuel+=$5; r=$2/$1; if(r>0.051)hi++; else if(r<0.032)lo++; if($4>mx)mx=$4} END{printf "      mean FP over orbit %.0f, mean marines/day %.1f (ratio out of 0.033-0.05: %d high, %d low), fuel paid %d total, longest hold %d d\n", fp/n, m/n, hi, lo, fuel, mx}'
    grep '^Swarm bombards the' "$F" | tail -2 | cut -c1-200 | sed 's/^/      /'
    grep 'destroyed by swarm orbital bombardment' "$F" | sed 's/^Front at //; s/ destroyed.*//' | sort | uniq -c | sort -rn | head -5 | tr '\n' ';' | sed 's/^/      destroyed: /'; echo
  fi
  [ "$held" -gt 0 ] && grep '^Swarm bombardment over .* held' "$F" | tail -1 | sed 's/^/      /'
  grep '^Reinforcement engages at' "$F" | tail -1 | cut -c1-180 | sed 's/^/      /'
  # the war: human landings on hives and hives lost on the ground
  echo "   war: hives eradicated by human fronts $(grep -c 'has destroyed the Fabrication Core' "$F"), hive fronts destroyed $(grep -c '^Front at .* (threat) destroyed' "$F"), strikes $(grep -c '^Strike launched' "$F"), offensive launches $(grep -c '^Offensive launched' "$F"), sieges called off $(grep -ci 'siege.*called off' "$F")"
done
# jar 19:20 (333c0840) adds the carried hold: count its lines too
for g in a b c; do F=$T/ti-$hw$g.txt; [ -f "$F" ] || continue
  echo "   hold $hw$g: sails armed $(grep -c '^Swarm sails armed' "$F") (stock paid $(grep -o '^Swarm sails armed.*loads [0-9]* fuel' "$F" | grep -o '[0-9]* fuel' | awk '{s+=$1} END{print s+0}')), rearms at port $(grep -c '^Swarm rearms at' "$F"), rearm trips $(grep -c '^Swarm rearms: ' "$F") returned $(grep -c '^Swarm rearmed at' "$F"), dry nowhere $(grep -c '^Swarm dry at' "$F"), no ordnance days $(grep -c 'held: no ordnance aboard' "$F")"
done
