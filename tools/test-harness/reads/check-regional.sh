# check-regional.sh - the hw121 hypothesis: regional relief (ThreatPosture.regionalPool) sends the spare of
# hives in other systems to a besieged hive its own system cannot defend, so fewer hives fall in multi-hive
# systems. Per poll: regional rallies (count, FP, mean light-years), own-system rallies, no-rally lines
# (those that counted a regional pool), hives eradicated. Flags `regional0` when the feature never fires:
# war day 1000+, 20+ no-rally lines and no regional send.
. "$(dirname "$0")/check-overdue.sh"
eval "$(declare -f check | sed '1s/^check ()/check_overdue ()/')"
check() {
  local g=$1 F=$2
  check_overdue "$g" "$F"
  local reg regfp regly own norally noreg erad war
  eval "$(awk '/^Posture: .* rallied .*regional relief/ { reg++; if (match($0, /rallied [0-9]+ FP/)) { s=substr($0,RSTART+8,RLENGTH-11); fp+=s }
      if (match($0, /from [0-9]+ ly/)) { s=substr($0,RSTART+5,RLENGTH-8); ly+=s } next }
    /^Posture: .* rallied / { own++ }
    /^Posture: no rally/ { nr++; if ($0 ~ / regional\)/) nrr++ }
    END { printf "reg=%d; regfp=%d; regly=%d; own=%d; norally=%d; noreg=%d\n", reg+0, fp+0, (reg>0? ly/reg : 0), own+0, nr+0, nrr+0 }' "$F")"
  erad=$(grep -ci 'eradicated' "$F"); war=$(grep -o 'war [0-9]*' "$F" | tail -1 | cut -d' ' -f2); war=${war:-0}
  echo "    regional $TAG$g: regional rallies $reg ($regfp FP, mean $regly ly), own-system rallies $own, no rally $norally ($noreg counting a regional pool), eradicated lines $erad"
  [ "$war" -gt 1000 ] && [ "$norally" -ge 20 ] && [ "$reg" -eq 0 ] && flag $g regional0 "no regional send by war day $war with $norally no-rally lines"
  return 0
}
