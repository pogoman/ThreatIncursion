#!/bin/bash
# waits for run-siege-21 (hw215 / hw216) to finish, then applies patch-n1b, rebuilds, and launches hw217 / hw218
R="/c/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion"
S="$R/scratchpad/seeds-hw174"
T="$(cygpath -u "$LOCALAPPDATA")/Temp/threatinc-tests"
for i in $(seq 1 150); do [ -f "$S/siege21.done" ] && break; sleep 60; done
[ -f "$S/siege21.done" ] || { echo "siege21 not done after 150 min"; exit 1; }
date; echo "siege21 done"
sleep 20
tasklist | grep -i java && { echo "java still running"; exit 1; }
cd "$R" || exit 1
perl scratchpad/patch-n1b.pl || exit 1
before=$(stat -c %Y jars/ThreatInc.jar)
powershell -NoProfile -ExecutionPolicy Bypass -File compile.ps1 2>&1 | grep -v '^Note:' | grep -i 'error\|failed'
after=$(stat -c %Y jars/ThreatInc.jar)
[ "$after" -gt "$before" ] || { echo "JAR NOT REBUILT"; exit 1; }
ls -l --time-style=+%H:%M jars/ThreatInc.jar
cd "$S" || exit 1
sed -i -e 's/for n in 215 216;/for n in 217 218;/' -e 's/siege21\.done/siege22.done/g' watch-siege.sh
sed -e 's/hw215/hw217/g' check-hw215.sh > check-hw217.sh
echo "launching hw217 / hw218 $(date +%T)"
powershell -NoProfile -ExecutionPolicy Bypass -File run-siege-22.ps1 > "$S/run-siege-22.out" 2>&1 &
sleep 40
bash watch-siege.sh > "$S/watch-siege-22.out" 2>&1 &
sleep 1100
bash check-hw217.sh
