#!/bin/bash
# launch-hw119.sh - swap in chk.jar (velocity vector read, carry at the second read) and launch hw119a (ck2) + hw120a/b (ck5).
set -e
M="/c/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion"
if tasklist 2>/dev/null | grep -qi 'java.exe'; then echo "java.exe still running - not swapping the jar"; exit 1; fi
cp "/c/Users/zuzam/AppData/Local/Temp/claude/chk.jar" "$M/jars/ThreatInc.jar"
ls -la --time-style=+%H:%M "$M/jars/ThreatInc.jar" | awk '{print "jar", $5, $6}'
for g in hw117a hw118a hw118b; do cp -n /tmp/threatinc-tests/ti-$g.txt /tmp/threatinc-tests/ti-$g-keep.txt 2>/dev/null || true; done
rm -f /tmp/threatinc-tests/sbs-go.done
cd "$M/tools/test-harness/fastforward"
powershell -NoProfile -Command "Start-Process -FilePath powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','sbs.ps1','-Tags','hw119a,hw120a,hw120b','-Bases','\"hw119a=save_AphelionDysnomia_4103534775338436064ck2;hw120a=save_AmaruDugas_2921423183749615243ck5;hw120b=save_AmaruDugas_2921423183749615243ck5\"','-Days','2400','-MaxMinutes','70' -WindowStyle Hidden"
echo "launched $(date +%H:%M)"
