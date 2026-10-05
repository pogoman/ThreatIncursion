#!/bin/bash
# batch.sh <month> <tag>... : every digest for a batch of game runs, to the month given, on stdout (redirect it
# into %TEMP%\threatinc-tests\<batch>-findings-m<month>.txt - a findings file per checkpoint). Reads ti-<tag>.txt
# and exc-<tag>.txt from %TEMP%\threatinc-tests. swarm.pl, calloff.pl, fall.pl and cover.pl read the whole log so
# far; uat.pl stops at the month.
#   bash tools/test-harness/digest/batch.sh 60 hw11a hw11b hw11c
m=${1:?month}; shift
g="$(cd "$(dirname "$0")" && pwd)"
cd "${THREATINC_TEST_OUT:-$TEMP/threatinc-tests}" || exit 1
echo "batch digest to month $m, written $(date '+%Y-%m-%d %H:%M'): $*"
for t in "$@"; do
  [ -f ti-$t.txt ] || { echo "== $t: no log"; continue; }
  echo "== $t: months logged $(grep -a -c '^Census: threat' ti-$t.txt), last clock: $(grep -a '^Clock: day' ti-$t.txt | tail -1)"
done
echo "#### the swarm's side"
perl "$g/swarm.pl" "$@"
echo "#### the cover over a landed army (the 2026-10-05 change)"
perl "$g/cover.pl" "$@"
echo "#### how the sieges ended"
perl "$g/calloff.pl" "$@"
echo "#### how long a hive takes to fall"
perl "$g/fall.pl" "$@"
echo "#### hives and hives found, by year"
for t in "$@"; do
  [ -f ti-$t.txt ] || continue
  echo "== $t"
  grep -a '^Census: threat' ti-$t.txt | awk 'NR%12==0{print "  m" NR ": " $0}' | sed -E 's/, income.*//; s/Census: threat //'
done
echo "#### the humans' side"
for t in "$@"; do perl "$g/uat.pl" $t $m; done
perl "$g/funnel.pl" "$@"
perl "$g/hammer.pl" "$@"
echo "#### errors"
for t in "$@"; do
  [ -f ti-$t.txt ] || continue
  printf "== %s: council errors %s, exceptions file %s bytes\n" "$t" \
    "$(grep -a -c -E '^(Play .*: error|Council .*: error|Plays: error)' ti-$t.txt)" "$(stat -c %s exc-$t.txt 2>/dev/null)"
done
