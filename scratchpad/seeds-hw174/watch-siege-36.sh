#!/bin/bash
# watch-siege.sh - runs reads/watch.sh over the six hw189-hw194 rounds of run-siege.ps1 in turn, with check-siege.sh,
# and exits 1 on the first FAIL signal so a background run of it notifies at once. Output per round in watch-hwNNN.out.
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
W="/c/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion/tools/test-harness/reads/watch.sh"
T="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"
IGN="hoard,starved,broke,hulls,humans,nowar,convoywait,dry"  # dry: the bombard-hold fuel drain (O1 open since hw165), seen on this snapshot in hw201b/hw205b too  # nowar: the snapshots resume at war day ~2000 with a fresh log, so it trips at the first poll
for n in 250 251; do
  tag=hw$n
  # wait for the round's first game to start writing (up to 15 min), then watch it to its end
  for i in $(seq 1 90); do [ -f "$T/ti-${tag}a.txt" ] && break; [ -f "$D/siege36.done" ] && exit 0; sleep 10; done
  [ -f "$T/ti-${tag}a.txt" ] || { echo "$tag never started"; exit 1; }
  echo "=== $tag $(date +%T)"
  bash "$W" $tag 50 "$IGN" "$D/check-siege.sh" | tee "$D/watch-$tag.out"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 1 ]; then echo "FAIL in $tag - see $D/watch-$tag.out"; exit 1; fi
  sleep 60
done
echo "all rounds watched"
