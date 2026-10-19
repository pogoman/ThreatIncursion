#!/bin/bash
# early check for hw220 (council back + N1): the round's own hypotheses
D="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"
date
for g in a b c; do f="$D/ti-hw220$g.txt"; [ -f "$f" ] || { echo "== hw220$g no log"; continue; }
  echo "== hw220$g  exc $(grep -c 'Exception' "$f")  lines $(wc -l < "$f")"
  echo "   hives: $(grep '^Census: threat hives' "$f" | tail -1 | cut -c1-80)"
  echo "   planner plans: $(grep -c '^Plan ' "$f")  prongs sailed: $(grep -c 'Saturate or siege of' "$f")  postponed: $(grep -c 'Expedition postponed' "$f")  hull-bound (pay for): $(grep -c 'Expedition postponed .* pay for' "$f")  council plays: $(grep -c "^Play [a-z_]*#[0-9]*: start -> prepare" "$f")"
  echo "   allied convoys: $(grep -c 'Allied Convoy Sails' "$f") -> $(grep -o 'Allied Convoy Sails | [A-Za-z -]* sends [0-9,]* [a-z ]* from' "$f" | sed 's/.* sends [0-9,]* //; s/ from//' | sort | uniq -c | tr '\n' ';')  siege-short lends: $(grep -c 'a siege short of hulls' "$f")  no hulls: $(grep -c '^Ally aid: no hulls' "$f")"
  echo "   plays refused (could not be paid): $(grep -c "could not be paid after all" "$f")  play sieges: $(grep -c "^Play [a-z_]*#[0-9]* [A-Z]*: siege of" "$f")"
  echo "   help requests posted $(grep -c '^Help request posted: Deliver' "$f") expired $(grep -c '^Help request withdrawn (expired): Deliver' "$f") neutralized $(grep -c '^Help request withdrawn (neutralized): Deliver' "$f")"
  echo "   hulls: $(grep '^Hulls: hegemony hulls\|^Hulls: Hegemony hulls' "$f" | tail -1 | cut -c1-140)"
  grep -m2 'Exception' "$f" | cut -c1-200
done
tail -3 "$D/sbs-status.txt"
