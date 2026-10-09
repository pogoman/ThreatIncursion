# check-enough.sh - the hw87/hw88 hypothesis: the pressure pass no longer feeds attacked hives a swarm at a
# time (hw83a: 4,501 fabricated for receivers a game, 175 in one month, fleets away 37-55k supplies a month).
# Per poll: the last three months' swarms fabricated for receivers, transfers sent, refusals (no transfer ...
# could stand) and the latest Reach line's fleets-away cost; flags 250+ fabricated in three months from
# month 20, or a fleets-away cost above the supplies made. The churn check (check-churn.sh) runs as well.
. "$(dirname "$0")/check-churn.sh"
# the churn check under its own name (a wrapper calling check would recurse into the one below)
eval "$(declare -f check | sed '1s/^check ()/check_churn ()/')"
check() {
  local g=$1 F=$2
  check_churn "$g" "$F"
  local t
  t=$(awk '/^Hulls: Your faction/{m++}
    /^Posture: .* fabricated a [0-9]* FP swarm for/ { f[m]++ }
    /^Posture: .* sent [0-9]* FP to/ { s[m]++ }
    /^Posture: no transfer to .* could stand/ { r[m]++ }
    /^Reach: spare/ { x=$0; sub(/.*fleets away /,"",x); sub(/\/mo.*/,"",x); away=x }
    /^Census: threat hives/ { x=$0; sub(/.*supplies [0-9]* \(\+/,"",x); sub(/\/mo.*/,"",x); made=x }
    END{ ff=0; ss=0; rr=0; for (i=m-2;i<=m;i++) { ff+=f[i]; ss+=s[i]; rr+=r[i] } printf "%d %d %d %d %d %d", m, ff, ss, rr, away+0, made+0 }' "$F")
  set -- $t
  echo "    enough check $TAG$g: month $1, last 3 months fabricated-for $2 sent $3 refused $4; fleets away $5 of $6 supplies made a month"
  [ "$1" -ge 20 ] && [ "$2" -ge 250 ] && flag $g enough "the pass still fabricates for receivers: $2 swarms in the last 3 months (sent $3, refused $4, month $1)"
  [ "$1" -ge 20 ] && [ "$6" -gt 0 ] && [ "$5" -gt "$6" ] && flag $g enough "fleets away cost $5 supplies a month against $6 made (month $1)"
  return 0
}
