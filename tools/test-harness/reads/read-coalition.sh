#!/bin/bash
# read-coalition.sh <tag> - the coalition-relief round's end figures (rounds 44-45, hw262-hw265) per game a/b/c:
# hives, human colonies, colonies lost, hives eradicated, sieges sailed, own reliefs, partners' reliefs for base-less
# worlds (A), coalition reliefs sailed / FP / under 100 FP, coalition held, 'cannot relieve'.
T="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"
R="$(dirname "${BASH_SOURCE[0]}")"
for g in a b c; do
  F="$T/ti-$1$g.txt"; D="$T/digest-$1$g.txt"; [ -f "$F" ] || { echo "$1$g: no log"; continue; }
  hives=$(grep -o '^Census: threat hives [0-9]*' "$F" | tail -1 | awk '{print $4}')
  war=$(grep -o 'war [0-9]*' "$F" | tail -1 | cut -d' ' -f2)
  cols=$(bash "$R/colonies.sh" "$F" | awk '{print $1}')
  lost=$(grep -c '^Notice: Colony Lost' "$F")
  erad=$(grep -o 'hives eradicated *[0-9]*' "$D" 2>/dev/null | awk '{print $3}')
  sieges=$(grep -c 'sails from' "$F")
  own=$(grep -cE '^Relief: [a-z_]+ sends [0-9]+ FP from' "$F")
  partners=$(grep -c "FP of its partners' to invaded" "$F")
  coal=$(grep '^Coalition relief: [0-9]* FP' "$F" | awk '{n++; s+=$3; if ($3<100) u++} END{printf "%d sailed %d FP, %d under 100", n, s, u}')
  held=$(grep -c '^Coalition relief held:' "$F")
  cannot=$(grep -c 'cannot relieve invaded' "$F")
  exc=$(grep -ci 'exception' "$F")
  echo "$1$g war $war: hives $hives, colonies $cols, lost $lost, eradicated $erad, sieges $sieges, own reliefs $own, partners' (A) $partners, coalition $coal, held $held, cannot $cannot, exc $exc"
done
