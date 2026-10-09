# check-muster.sh - the hw91/hw92 hypothesis: the garrison fights as one (ThreatGarrisonMuster). Per poll: musters
# logged, hunt battles and the share met by a single swarm over the whole run so far (hw89a: 89%); flags a
# single-swarm share above 60% once 40 hunt battles are in, or no muster logged by month 20 with hunts fought.
# The enough check (check-enough.sh, with the churn check inside it) runs as well.
. "$(dirname "$0")/check-enough.sh"
eval "$(declare -f check | sed '1s/^check ()/check_enough ()/')"
check() {
  local g=$1 F=$2
  check_enough "$g" "$F"
  local t
  t=$(awk '/^Hulls: Your faction/{m++}
    /^Garrison musters at/ { mu++ }
    /^Hunt battle near/ { n++; s=$0; sub(/.*\): /,"",s); k=split(s, a, "; "); if (k==1) one++ }
    END{ printf "%d %d %d %d", m, mu+0, n+0, one+0 }' "$F")
  set -- $t
  local pct=0; [ "$3" -gt 0 ] && pct=$((100 * $4 / $3))
  echo "    muster check $TAG$g: month $1, musters $2, hunt battles $3, single-swarm $4 ($pct%)"
  [ "$3" -ge 40 ] && [ "$pct" -gt 60 ] && flag $g muster "hunts still meet one swarm: $4 of $3 battles ($pct%), $2 musters (month $1)"
  [ "$1" -ge 20 ] && [ "$3" -ge 10 ] && [ "$2" -eq 0 ] && flag $g muster "no garrison muster logged by month $1 with $3 hunt battles fought"
  return 0
}
