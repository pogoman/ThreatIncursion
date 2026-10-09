# check-rowfor.sh - the hw97/hw98 hypothesis: the pass fabricates a swarm sized to the receiver's deficit (rowFor).
# Per poll: swarms fabricated for receivers, their mean FP and the share under 100 FP (hw93a: 3,498, mean 115,
# 48% under 100), and the reinforcements in flight at the last census (hw93a: 151). Flags a share under 100
# above 40% once 100 are built from month 20, or 150+ reinforcements in flight. The muster check runs as well.
. "$(dirname "$0")/check-muster.sh"
eval "$(declare -f check | sed '1s/^check ()/check_muster ()/')"
check() {
  local g=$1 F=$2
  check_muster "$g" "$F"
  local t
  t=$(awk '/^Hulls: Your faction/{m++}
    /^Posture: .* fabricated a [0-9]+ FP swarm for/ { if (match($0, /fabricated a [0-9]+ FP/)) { fp=substr($0, RSTART+13, RLENGTH-16)+0; n++; s+=fp; if (fp<100) sm++ } }
    /^Away fleets:/ { line=$0 }
    END { r=0; if (match(line, /reinforcement [0-9]+/)) r=substr(line, RSTART+14, RLENGTH-14)+0; printf "%d %d %d %d %d", m, n+0, (n>0 ? s/n : 0), sm+0, r }' "$F")
  set -- $t
  local pct=0; [ "$2" -gt 0 ] && pct=$((100 * $4 / $2))
  echo "    rowfor check $TAG$g: month $1, fabricated-for $2 (mean $3 FP, under 100: $4 = $pct%), reinforcements in flight $5"
  [ "$1" -ge 20 ] && [ "$2" -ge 100 ] && [ "$pct" -gt 40 ] && flag $g rowfor "the pass still fabricates small swarms: $4 of $2 under 100 FP ($pct%), mean $3 FP (month $1)"
  [ "$5" -ge 150 ] && flag $g rowfor "$5 reinforcements in flight at once (month $1)"
  return 0
}
