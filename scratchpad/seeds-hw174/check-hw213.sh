#!/bin/bash
# early check for hw213 (council off + allied convoys + hull lending): the round's own hypotheses
D="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"
date
for g in a b c; do f="$D/ti-hw213$g.txt"; [ -f "$f" ] || { echo "== hw213$g no log"; continue; }
  echo "== hw213$g  exc $(grep -c 'Exception' "$f")  lines $(wc -l < "$f")"
  echo "   hives: $(grep '^Census: threat hives' "$f" | tail -1 | cut -c1-80)"
  echo "   planner plans: $(grep -c '^Plan ' "$f")  prongs sailed: $(grep -c 'Saturate or siege of' "$f")  postponed: $(grep -c 'Expedition postponed' "$f")  hull-bound (pay for): $(grep -c 'Expedition postponed .* pay for' "$f")  council plays: $(grep -c '^Play f#' "$f")"
  echo "   allied convoys: $(grep -c 'Allied Convoy Sails' "$f") -> $(grep -o 'Allied Convoy Sails | [A-Za-z -]* sends [0-9,]* [a-z ]* from' "$f" | sed 's/.* sends [0-9,]* //; s/ from//' | sort | uniq -c | tr '\n' ';')  siege-short lends: $(grep -c 'a siege short of hulls' "$f")  no hulls: $(grep -c '^Ally aid: no hulls' "$f")"
  echo "   help requests posted $(grep -c '^Help request posted: Deliver' "$f") expired $(grep -c '^Help request withdrawn (expired): Deliver' "$f") neutralized $(grep -c '^Help request withdrawn (neutralized): Deliver' "$f")"
  echo "   hulls: $(grep '^Hulls: hegemony hulls\|^Hulls: Hegemony hulls' "$f" | tail -1 | cut -c1-140)"
  grep -m2 'Exception' "$f" | cut -c1-200
done
tail -3 "$D/sbs-status.txt"
