#!/bin/bash
# swarm-digest.sh <tag> [tag...] - the swarm side of a run in twenty lines, from ti-<tag>.txt (or the -keep copy):
# hives (peak, end), falls and how many followed a strip to zero (hw59: 18 of 21), campaigns and what bound
# them (fund / fuel / supplies, from the saving lines), the focus history, strikes sized against met,
# exposure changes, and the posture line at the end. 2026-10-07, for the overnight iterations.
D="${LOCALAPPDATA:-$HOME/AppData/Local}/Temp/threatinc-tests"
for tag in "$@"; do
  f="$D/ti-$tag.txt"; [ -f "$f" ] || f="$D/ti-$tag-keep.txt"; [ -f "$f" ] || { echo "$tag: no log"; continue; }
  echo "===== $tag ($(wc -l < "$f") lines)"
  grep -o 'Clock: day -[0-9]* war [0-9]*' "$f" | sed -n '1p;$p' | tr '\n' ' '; echo
  grep 'Posture sector' "$f" | tail -1 | sed 's/surplus.*fleets/fleets/; s/; appetite.*//' | cut -c1-140
  echo "-- hives: $(grep -o 'of a [0-9]*-hive peak' "$f" | tail -1), live $(grep 'Stance: losing pressure' "$f" | tail -1 | grep -o '[0-9]* of a' | head -1 | cut -d' ' -f1) fallen in the last window"
  awk '/mustered from .* \(0 remain/{match($0,/mustered from [A-Za-z0-9 -]+ \(0/); c=substr($0,RSTART+14,RLENGTH-17); strip[c]=NR}
       /^Colony eradicated: /{c=substr($0,20); n++; if(c in strip && strip[c]<NR) s++}
       END{printf "-- eradicated %d, of which stripped to 0 earlier %d\n", n, s}' "$f"
  echo "-- musters to zero: $(grep -c 'mustered from .* (0 remain' "$f") of $(grep -c 'mustered from' "$f")"
  echo "-- campaigns launched $(grep -c 'Offensive launched' "$f"), held sailed $(grep -c 'sails on its day' "$f"), refused $(grep -c 'cannot sail today' "$f"), nothing-pays $(grep -c 'nothing the (fund|means) pay' -E "$f")"
  grep -o 'Strike launched from [A-Za-z -]* at [A-Za-z -]* ([a-z_]*, [0-9]* ly, ~[0-9]* days away; [0-9]* swarm(s) mustered in [0-9]* fleet(s), [0-9]* FP' "$f" \
    | awk '{fp=$(NF-1); n++; s+=fp; if(fp>=5000)b++} END{printf "-- strikes %d, mean %d FP, 5k+: %d\n", n, (n?s/n:0), b}'
  grep 'Swarm intel: a strike at' "$f" | awk '{for(i=1;i<=NF;i++){if($i=="for")e+=$(i+1); if($i=="met")m+=$(i+1)}; n++} END{printf "-- strikes reported %d: sized %d, met %d (%.2f)\n", n, e, m, (e>0?m/e:0)}'
  # what bound the saving: months to launch by fund vs fuel, from each saving line
  grep 'Offensive: saving' "$f" | awk '{
      cost=0; fund=0; need=0; stock=0;
      if (match($0,/[0-9]+ FP from the fund with/)) cost=substr($0,RSTART,RLENGTH-23)+0;
      if (match($0,/fund [0-9]+ FP \(/)) fund=substr($0,RSTART+5,RLENGTH-9)+0;
      if (match($0,/[0-9]+ fuel and/)) need=substr($0,RSTART,RLENGTH-9)+0;
      if (match($0,/, fuel [0-9]+ \(/)) stock=substr($0,RSTART+7,RLENGTH-9)+0;
      n++; if (need>stock) bf++; if (cost>fund) bc++; if (index($0,"not yet kept")) bs++;
    } END{printf "-- saving lines %d: fuel short on %d, fund short on %d, supplies do not keep the fleets away on %d\n", n, bf, bc, bs}'
  grep 'Census: threat' "$f" | tail -1 | grep -o 'fuel [0-9]* (+[0-9]*/mo, spent [0-9]*); supplies [0-9]* (+[0-9]*/mo, spent [0-9]*)' | sed 's/^/-- stock: /'
  echo "-- conversions $(grep -c '^Converted Fuel Production' "$f"), forge builds $(grep -c 'Hive planner: heavyindustry' "$f"), waiting-build turns $(grep -c 'Hive planner: waiting build' "$f"), seedings held $(grep -c 'Hive stock: a Seeding Swarm' "$f")"
  echo "-- focus:"; grep 'Offensive: focus' "$f" | cut -c1-160 | sed 's/^/   /' | head -8
  echo "-- fund: $(grep -o 'fund [0-9]* FP' "$f" | awk 'NR%8==1' | grep -o '[0-9]*' | tr '\n' ' ')"
  echo "-- chest: full $(grep -c 'Stance: the chest is full' "$f"), spent $(grep -c 'Stance: the chest is spent' "$f"); stance passes PRESS $(grep -c 'stance PRESS' "$f"), EXPAND $(grep -c 'stance EXPAND' "$f"), CONSOLIDATE $(grep -c 'stance CONSOLIDATE' "$f")"
  echo "-- hull convoys $(grep -c 'hulls lost to rebuild' "$f"); no-hulls lines $(grep -c 'Ally aid: no hulls' "$f"): $(grep 'Ally aid: no hulls' "$f" | tail -1 | cut -c1-200)"
  echo "-- humans: bases founded $(grep -c 'Frontline: .* founded' "$f"), lost to strikes $(grep -c 'destroyed by a Threat strike' "$f"), founding held for hulls $(grep -c 'founds no link' "$f"), cannot pay $(grep -c 'cannot pay for a link' "$f"); yards at end $(grep -o 'to rebuild at [0-9]*/mo' "$f" | tail -5 | grep -o '[0-9]*/mo' | tr '\n' ' ')"
  echo "-- exposure changes $(grep -c 'Posture: .* exposure' "$f"), systems at 1.00 now: $(grep 'Posture: .* exposure' "$f" | grep -c '> 1.00')"
  echo "-- sieges on hives (BESIEGED entries) $(grep -c 'Posture: .*->BESIEGED' "$f"); patrols: $(grep -c 'Patrol Swarm from' "$f") swarm, $(grep -c '^Patrol of ' "$f") human"
  echo "-- exceptions: $(grep -c 'Exception' "$D/exc-$tag.txt" 2>/dev/null)"
done
