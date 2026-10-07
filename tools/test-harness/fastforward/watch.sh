#!/bin/bash
# watch.sh <hwNN> [maxMinutes] - polls a running batch every 3 minutes and exits EARLY on the first
# fail signal: a non-loading exception, or a held prong refused ("cannot sail today"). Prints only new
# signals plus a one-line progress mark, so a background run of it notifies as soon as a batch is dead.
TAG=$1; MAX=${2:-130}; T="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"
declare -A seen; start=$(date +%s)
while :; do
  fail=0
  for g in a b c; do
    F=$T/ti-$TAG$g.txt; E=$T/exc-$TAG$g.txt
    [ -f "$F" ] || continue
    war=$(grep -o 'war [0-9]*' $F | tail -1)
    ex=$(grep -v "ShipHullSpreadsheetLoader" $E 2>/dev/null | grep -c "Exception\|Error")
    ref=$(grep -c 'cannot sail today' $F); la=$(grep -c '^Offensive launched' $F); hs=$(grep -c 'sails on its day' $F)
    hull=$(grep -c 'ship hulls from' $F); land=$(grep -c '^Hulls: .* lands' $F)
    k="$g:$ex:$ref"
    echo "$(date +%T) $TAG$g $war | launches $la held-sailed $hs refused $ref | exc $ex | hull convoys $hull landed $land"
    if [ "$ex" -gt 0 ] && [ -z "${seen[e$g]}" ]; then seen[e$g]=1; fail=1; echo "  FIRST EXCEPTION:"; grep -v "ShipHullSpreadsheetLoader" $E | grep -m1 -A6 "Exception\|Error" | grep "Exception\|Error\|threatinc" | head -4 | cut -c1-200; fi
    if [ "$ref" -gt 0 ] && [ -z "${seen[r$g]}" ]; then seen[r$g]=1; fail=1; echo "  FIRST REFUSAL:"; grep -m1 -B1 'cannot sail today' $F | cut -c1-200; fi
  done
  [ $fail = 1 ] && { echo "FAIL SIGNAL - stop the batch or read on"; exit 1; }
  [ $(( ($(date +%s)-start)/60 )) -ge $MAX ] && exit 0
  tasklist 2>/dev/null | grep -qi java.exe || { echo "batch ended"; exit 0; }
  sleep 180
done
