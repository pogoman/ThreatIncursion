#!/bin/bash
# read-hw156.sh [hw] - one digest per game for a founding-convoy batch (2026-10-11): the war's shape (hives,
# fleets, human colonies first/last), the founding convoys (sailed / landed / lost / turned back / short /
# passage refused), bases founded and killed, base strikes, landings, and the carried-provisions build's
# own signals (handover-2026-10-11-provisions-testing.md A/B: Order draw holds, upkeep owed, restocks,
# stand-downs supplies low, sieges short). Reads $LOCALAPPDATA/Temp/threatinc-tests/ti-<hw><g>.txt.
hw=${1:-hw156}
T="$LOCALAPPDATA/Temp/threatinc-tests"
for g in a b c; do
  F=$T/ti-$hw$g.txt; [ -f "$F" ] || continue
  echo "== $hw$g ($(grep -c . "$F") lines)"
  grep '^Census: threat hives' "$F" | sed -n '1p;$p' | grep -o 'hives [0-9]* (size [0-9]*), found [0-9]*, fleets [0-9]* FP' | sed 's/^/   swarm /'
  grep '^Census: threat hives' "$F" | awk 'NR%20==0' | grep -o 'hives [0-9]*' | tr '\n' ' ' | sed 's/^/   hives by month: /'; echo
  for f in hegemony independent persean tritachyon luddic_church sindrian_diktat; do
    grep "^Census: $f " "$F" | sed -n '1p;$p' | grep -o "colonies [0-9]*.*links [0-9]* (size [0-9]*" | tr '\n' '|' | sed "s/^/   $f: /"; echo
  done | grep -v ": $"
  sailed=$(grep -c '^Founding convoy dispatched' "$F"); landed=$(grep -c '^Frontline: the kit for .* landed' "$F")
  lost=$(grep -c '^Founding convoy lost' "$F"); back=$(grep -c '^Founding convoy turned back' "$F"); short=$(grep -c '^Founding convoy arrived short' "$F")
  passage=$(grep -c 'cannot pay the passage' "$F"); nopay=$(grep -c 'cannot pay for a link' "$F"); unloaded=$(grep -c 'unloaded, nothing sails' "$F")
  unguarded=$(grep -c 'stands unguarded' "$F")
  echo "   founding: sailed $sailed, landed $landed, lost $lost, turned back $back, short $short, unguarded $unguarded; refused: passage $passage, no payer $nopay, unloaded $unloaded"
  grep '^Founding convoy dispatched' "$F" | grep -o '([0-9]* supplies.*' | awk -F'[ ,;]+' '{e+=$6; s+=$8; ly+=$10; n++} END{if(n) printf "   convoys: mean escort %d FP, %d ships, %d ly\n", e/n, s/n, ly/n}'
  founded=$(grep -c 'Frontline: .* founded' "$F"); destroyed=$(grep -c 'destroyed by a Threat strike' "$F"); dismantled=$(grep -c 'Frontline: .* dismantled' "$F")
  bstrikes=$(grep -o '^Strike launched from [A-Za-z'"'"' -]* at [A-Za-z'"'"' -]*Forward Base' "$F" | wc -l)
  echo "   bases: founded $founded, destroyed by strike $destroyed, dismantled (all causes) $dismantled; base strikes $bstrikes"
  echo "   ground: landings $(grep -c '^Siege pass (landing)' "$F"), eradicated $(grep -c '^Notice: Hive Eradicated' "$F"); offensive launches $(grep -c '^Offensive launched' "$F"), $(grep '^Offensive launched' "$F" | tail -1 | grep -o 'fund left [0-9]* FP')"
  echo "   provisions: order draws $(grep -c '^Order draw at' "$F"), upkeep lines $(grep -c '^Fleet upkeep:' "$F") (owed>0: $(grep '^Fleet upkeep:' "$F" | grep -vc ' 0 owed')), restocked $(grep -c '^Restocked at' "$F"), stood down supplies low $(grep -c 'stood down (supplies low' "$F"), sieges short $(grep -c 'Siege Short of Supplies' "$F"), out of supplies $(grep -c 'Siege Out of Supplies' "$F")"
  echo "   exceptions: $(grep -c . $T/exc-$hw$g.txt 2>/dev/null) lines ($(grep -vc 'ShipHullSpreadsheetLoader' $T/exc-$hw$g.txt 2>/dev/null) real)"
done
tail -2 $T/sbs-status.txt
