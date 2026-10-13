#!/bin/bash
# check-breakup.sh [hw] - the hw172 hypothesis (2026-10-13, "guns while the fuel is good, soldiers while it is not"):
# a hold loads only from the fuel stock above a reserve (one month's income + the founding kit); a dry swarm over
# its own besieged hive breaks up ships into hive troops (ThreatGroundFronts.breakUpForHive). Per game: hives at
# the end / peak, fuel stock and the hold economy (loads, rearms, fired, home), break-ups (lines, FP, troops), the
# held-for-reserve days, counter-attacks at hives, cores destroyed. Flags nothing; read against the source game
# (hw171a 92 -> 69, hw171b 67 -> 59, hw167b peak 75 at m113).
hw=${1:-hw172}; T="$LOCALAPPDATA/Temp/threatinc-tests"
for g in a b c; do
  F=$T/ti-$hw$g.txt; [ -f "$F" ] || continue
  war=$(grep -o '^Clock: day \S* war [0-9]*' "$F" | tail -1 | awk '{print $NF}')
  echo "== $hw$g war day ${war:-0}: $(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'hives [0-9]*\|fleets [0-9]* FP\|fuel [0-9]* (+[0-9]*/mo, spent [0-9]*)\|supplies [0-9]* (+[0-9]*/mo' | tr '\n' ' ')"
  grep '^Census: threat hives' "$F" | grep -o 'hives [0-9]*' | awk '{h=$2; if(h>pk){pk=h;pi=NR}} END{printf "   hives peak %d at census %d of %d, end %d\n", pk, pi, NR, h}'
  echo "   humans: $(for f in hegemony independent persean tritachyon luddic_church sindrian_diktat; do grep "^Census: $f " "$F" | tail -1 | grep -o 'colonies [0-9]*' | sed "s/colonies/$f/"; done | tr '\n' ' ')"
  # the hold economy
  awk '/^Swarm sails armed/{if(match($0,/loads [0-9]+ fuel/)){L+=substr($0,RSTART+6,RLENGTH-11)}; ln++}
       /^Swarm rearms at/{if(match($0,/[0-9]+ fuel/)){R+=substr($0,RSTART,RLENGTH-5)}; rn++}
       /^Swarm home with/{if(match($0,/[0-9]+ fuel/)){H+=substr($0,RSTART,RLENGTH-5)}; hn++}
       /^Swarm lost with/{if(match($0,/[0-9]+ fuel/)){X+=substr($0,RSTART,RLENGTH-5)}; xn++}
       /^Swarm bombards the/{if(match($0,/fuel [0-9]+ a day/)){B+=substr($0,RSTART+5,RLENGTH-11)}; bn++}
       /stock short [0-9]+ \(reserve/{short++}
       END{printf "   holds: loads %d (%dk) + rearms %d (%dk), cut short by the reserve %d; fired %dk over %d days; home %dk (%d), lost %dk (%d)\n", ln, L/1000, rn, R/1000, short, B/1000, bn, H/1000, hn, X/1000, xn}' "$F"
  # the break-up
  awk '/^Swarm breaks up over/{n++; if(match($0,/\(([0-9]+) FP\)/)){fp+=substr($0,RSTART+1,RLENGTH-5)}; if(match($0,/lands [0-9]+ troops/)){t+=substr($0,RSTART+6,RLENGTH-13)}; if(match($0,/hive troops [0-9]+/)){ht=substr($0,RSTART+12,RLENGTH-12); if(ht>mx)mx=ht}}
       /held: no ordnance aboard/{held++} /FP broke up for the ground/{heldb++}
       /^Counter-attack repelled at/{rep++} /^Counter-attack at .* retook/{took++} /hive troops lost/{htl++}
       END{printf "   break-up: %d lines, %d FP into %d troops, largest hive pool %d; held-no-ordnance days %d (%d with a break-up); counter-attacks repelled %d, retook a stratum %d\n", n, fp, t, mx, held, heldb, rep, took}' "$F"
  grep '^Swarm breaks up over' "$F" | tail -2 | cut -c1-230 | sed 's/^/      /'
  grep 'held: no ordnance aboard' "$F" | tail -1 | cut -c1-200 | sed 's/^/      /'
  # the war
  echo "   war: cores destroyed $(grep -c 'has destroyed the Fabrication Core' "$F"), fronts destroyed by swarm bombardment $(grep -c 'destroyed by swarm orbital bombardment' "$F"), hive siege slices $(grep -c '^siegeSlice .*\[hive\]' "$F"), strikes $(grep -c '^Strike launched' "$F"), sends held $(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'sends held [0-9]*')"
done
