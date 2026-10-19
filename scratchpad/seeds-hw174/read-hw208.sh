#!/bin/bash
# full read of a round: per game, census/bases/guard/convoy counters (hw208 option-1 first cut and after)
T="$LOCALAPPDATA/Temp/threatinc-tests"; tag=${1:-hw208}
for g in a b c; do f="$T/ti-$tag$g.txt"; [ -f "$f" ] || continue
  war=$(grep -o 'war day [0-9]*' "$f" | tail -1 | grep -o '[0-9]*$')
  hives=$(grep '^Census: threat hives' "$f" | tail -1 | grep -o 'hives [0-9]*' | grep -o '[0-9]*')
  peak=$(grep '^Census: threat hives' "$f" | grep -o 'hives [0-9]*' | grep -o '[0-9]*' | sort -n | tail -1)
  cols=$(grep -c '^Census: .* colonies' "$f")
  humancols=$(awk '/^Census: [a-z_]+ colonies/{if(!inblk){blk=""; inblk=1} blk=blk $0 "\n"; next} {if(inblk){last=blk; inblk=0}} END{if(inblk) last=blk; printf "%s", last}' "$f" | awk '{s+=$4} END {print s}')  # the LAST census block (7 factions, not tail -8 - that double-counted one faction until 2026-10-19)
  echo "== $tag$g war $war exc $(grep -c 'Exception' "$f") hives $hives (peak $peak) human colonies(last census block) $humancols"
  echo "   founded $(grep -c '^Frontline: .* founded ' "$f") on sites $(grep '^Frontline: .* founded ' "$f" | grep -o 'founded [^(]*' | sort -u | wc -l) dispatched $(grep -c '^Founding convoy dispatched' "$f") turnedback $(grep -c 'Founding convoy turned back' "$f") adopted $(grep -c 'its escort stands as the garrison' "$f") rear-home $(grep -c 'rear, the convoy goes home' "$f") noescort $(grep -c 'no escort to stand guard' "$f")"
  echo "   guard-refused-own $(grep -c "cannot pay the guard's passage" "$f") guard-refused-pool $(grep -c 'and its pool' "$f") dismantled-noguard $(grep -c 'no garrison to hold it' "$f") dismantled-destroyed $(grep -c 'dismantled .* (station destroyed' "$f") recalled $(grep -c 'garrison of .* recalled' "$f") eradicated(hives) $(grep -c '^Colony eradicated:' "$f") groundvictory $(grep -c '^Threat ground victory' "$f") corebuild $(grep -c '^Core build: ' "$f") cover $(grep -c '^Hive cover: .* issues' "$f")"
  grep "cannot pay the guard's passage" "$f" | grep -o '([0-9]* of [0-9]* FP' | sort | uniq -c | sort -rn | head -3 | sed 's/^/   passage: /'
done
