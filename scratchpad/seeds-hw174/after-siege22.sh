#!/bin/bash
# waits for run-siege-22 (hw217 / hw218) to finish, then rebuilds the jar (leash fix + invade-pass line are already
# in the sources, committed 911f71f0 / 04656c80) and launches hw219 / hw220 with the watcher (leash no longer ignored)
R="/c/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion"
S="$R/scratchpad/seeds-hw174"
for i in $(seq 1 150); do [ -f "$S/siege22.done" ] && break; sleep 60; done
[ -f "$S/siege22.done" ] || { echo "siege22 not done after 150 min"; exit 1; }
date; echo "siege22 done"
sleep 20
tasklist | grep -i java && { echo "java still running"; exit 1; }
cd "$R" || exit 1
before=$(stat -c %Y jars/ThreatInc.jar)
powershell -NoProfile -ExecutionPolicy Bypass -File compile.ps1 2>&1 | grep -v '^Note:' | grep -i 'error\|failed'
after=$(stat -c %Y jars/ThreatInc.jar)
[ "$after" -gt "$before" ] || { echo "JAR NOT REBUILT"; exit 1; }
ls -l --time-style=+%H:%M jars/ThreatInc.jar
cd "$S" || exit 1
sed -i -e 's/for n in 217 218;/for n in 219 220;/' -e 's/siege22\.done/siege23.done/g' -e 's/,leash"/"/' watch-siege.sh
grep -n 'for n in\|^IGN=' watch-siege.sh | cut -c1-80
echo "launching hw219 / hw220 $(date +%T)"
powershell -NoProfile -ExecutionPolicy Bypass -File run-siege-23.ps1 > "$S/run-siege-23.out" 2>&1 &
sleep 40
bash watch-siege.sh > "$S/watch-siege-23.out" 2>&1 &
echo "watcher started"
