#!/bin/sh
# One-screen read of a side-by-side batch's logs: sh batch-read.sh hw38 [a b c]
# Reads $LOCALAPPDATA/Temp/threatinc-tests/ti-<tag><x>.txt (or the -keep copy).
tag="$1"; shift
games="${*:-a b c}"
D="$LOCALAPPDATA/Temp/threatinc-tests"
for g in $games; do
  L="$D/ti-$tag$g-keep.txt"; [ -f "$L" ] || L="$D/ti-$tag$g.txt"
  [ -f "$L" ] || { echo "== $tag$g: no log"; continue; }
  echo "== $tag$g  $(grep 'Clock' "$L" | tail -n1 | cut -c1-45)  exc $(grep -c -v ShipHullSpreadsheetLoader "$D/exc-$tag$g.txt" 2>/dev/null)"
  echo "  strikes $(grep -c 'Strike launched' "$L"); human sieges $(grep -c 'sails from' "$L"); hunts-wait $(grep -c '^Hunting force waits' "$L"); founded $(grep -c 'Colony founded:' "$L"); eradicated $(grep -c 'Colony eradicated:' "$L"); trade $(grep -c 'Allied Convoy Sails' "$L"); holds $(grep -c 'holds .* supplies for' "$L"); HI $(grep -c 'Frontline: .* builds heavyindustry' "$L"); yards-idle $(grep -c 'yards idle' "$L"); recycled $(grep -c 'recycled a' "$L"); swarm-out-of-supplies $(grep -c 'Swarm Out of Supplies\|owes .* supplies for its fleets away' "$L")"
  grep "Census: threat" "$L" | awk 'NR%20==0' | sed 's/.*hives \([0-9]*\) (size \([0-9]*\)).*fleets \([0-9]*\) FP.*fuel \([0-9]*\).*supplies \([0-9]*\) (+\([0-9]*\)\/mo, spent \([0-9]*\)).*/  swarm: hives \1 fleets \3 FP fuel \4 supplies \5 (+\6 -\7)/' | tr '\n' ';' | sed 's/;$/\n/'
  grep "Census: threat" "$L" | tail -n1 | sed 's/.*hives \([0-9]*\) (size \([0-9]*\)).*fleets \([0-9]*\) FP.*fuel \([0-9]*\).*supplies \([0-9]*\) (+\([0-9]*\)\/mo, spent \([0-9]*\)).*/  swarm end: hives \1 size \2 fleets \3 FP fuel \4 supplies \5 (+\6 -\7)/'
  perl -ne 'if (/^Hulls: (.+?) hulls .*standing upkeep paid (\d+) of (\d+) supplies(?:, (\d+) FP starved)?/) { $p{$1}+=$2; $w{$1}+=$3; $s{$1}+=$4||0; $n{$1}++ } END { for (sort keys %w) { printf "  upkeep %-16s %3d mo: paid %7d of %7d (%.0f%%), starved %d FP\n", $_, $n{$_}, $p{$_}, $w{$_}, $w{$_}?100*$p{$_}/$w{$_}:0, $s{$_} } }' "$L"
  grep "Hulls: .* hulls .* FP" "$L" | grep -v "Your faction" | perl -ne 'if (/^Hulls: (.+?) hulls (\d+) FP \((\d+) built\): (\d+) out, (\d+) lost, (\d+) free; yards (\d+)/) { $h{$1}="$2 FP ($3 built, $4 out, $5 lost, $6 free; yards $7/mo)" } END { print "  navy $_: $h{$_}\n" for sort keys %h }'
  grep "Census: " "$L" | grep -v threat | perl -ne 'if (/Census: ([a-z_]+) colonies (\d+).*links (\d+).*bases (\d+).*fuel (\d+), supplies (\d+), stance (.*)/) { $c{$1}="colonies $2 links $3 bases $4 fuel $5 supplies $6 $7" } END { print "  human $_: $c{$_}\n" for sort keys %c }'
done
