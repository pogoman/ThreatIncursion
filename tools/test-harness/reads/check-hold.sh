# check-hold.sh - the hw144 hypothesis (ThreatOffensive.pass, the user's D1, 2026-10-10): from losing pressure 0.3
# the campaign prices only a month's fund and the rest pays the pressed hives, so the navy per hive holds its line
# against the strongest attacker's median siege (hw143a fell from 2.0k to 1.0k a hive while 39k FP sailed). Per
# poll: the hold passes, the FP paid to hives, strikes launched while holding, navy per hive, losing. Flags
# `holdleak` when 5+ strikes over 6k FP launched after the first hold pass, and `holdfall` when the navy per
# hive halves from its peak with 10+ hold passes logged.
check() {
  local g=$1 F=$2
  local holds paid strikes nph peak losing hl
  holds=$(grep -c 'holds the navy' "$F")
  paid=$(grep '^Offensive: the defence first - [0-9]* FP from the fund' "$F" | grep -o 'first - [0-9]*' | awk '{s+=$3} END{print s+0}')
  hl=$(grep -n 'holds the navy' "$F" | head -1 | cut -d: -f1)
  if [ -n "$hl" ]; then strikes=$(awk -v f="$hl" 'NR>f && /^Strike launched/' "$F" | wc -l); else strikes="(no hold yet)"; fi
  nph=$(grep '^Census: threat hives' "$F" | tail -1 | awk '{match($0,/hives ([0-9]+)/,h); match($0,/fleets ([0-9]+) FP/,f); if (h[1]>0) print int(f[1]/h[1]); else print 0}')
  peak=$(grep '^Census: threat hives' "$F" | awk '{match($0,/hives ([0-9]+)/,h); match($0,/fleets ([0-9]+) FP/,f); if (h[1]>0) {v=int(f[1]/h[1]); if (v>m) m=v}} END{print m+0}')
  losing=$(grep '^Stance: losing pressure' "$F" | tail -1 | grep -o '\-> [0-9.]*' | cut -c4-); losing=${losing:-0}
  echo "    hold $TAG$g: hold passes $holds, paid $paid FP to hives; strikes after the first hold $strikes; navy/hive ${nph:-0} (peak $peak); losing $losing"
  if [ -n "$hl" ]; then
    local big
    big=$(awk -v f="$hl" 'NR>f && /^Strike launched/ {match($0,/([0-9]+) FP \+ ([0-9]+) drawn/,d); if (d[1]+d[2] > 6000) n++} END{print n+0}' "$F")
    [ "$big" -ge 5 ] && flag $g holdleak "$big strikes over 6k FP launched after the first hold pass"
    [ "$holds" -ge 10 ] && [ "$peak" -gt 0 ] && [ $(( ${nph:-0} * 2 )) -lt "$peak" ] && flag $g holdfall "navy/hive $nph of a $peak peak with $holds hold passes"
  fi
  return 0
}
