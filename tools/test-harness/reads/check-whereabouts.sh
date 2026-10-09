# check-whereabouts.sh - the hw107/hw108 hypothesis: the whereabouts read (095cce7a) says WHY a reinforcement
# does not arrive. Per poll: overdue lines by speed (standing = speed 0), heading nowhere, fleeing, with a
# tactical target, in terrain, with a hostile within 2,000 units, carried (kick 3+). Flags nothing itself
# beyond the overdue check (3+ kicks); the read is the finding.
. "$(dirname "$0")/check-overdue.sh"
eval "$(declare -f check | sed '1s/^check ()/check_overdue ()/')"
check() {
  local g=$1 F=$2
  check_overdue "$g" "$F"
  local mon n still nowhere flee tgt terr near carried ai
  eval "$(awk '/^Hulls: Your faction/{m++}
    /^Reinforcement overdue:/ { n++; if ($0 ~ /speed 0,/) still++; if ($0 ~ /heading nowhere/) nowhere++; if ($0 ~ /fleeing true/) flee++;
      if ($0 ~ /tactical target [^n]/) tgt++; if ($0 ~ /terrain [^n]/) terr++;
      if (match($0, /nearest hostile [0-9]+ FP [a-z_]+ at [0-9]+ units/)) { s=substr($0, RSTART, RLENGTH); sub(/.* at /,"",s); sub(/ units/,"",s); if (s+0 < 2000) near++ }
      if ($0 ~ /carried to/) carried++; if ($0 ~ /, ai /) ai++ }
    END { printf "mon=%d; n=%d; still=%d; nowhere=%d; flee=%d; tgt=%d; terr=%d; near=%d; carried=%d; ai=%d\n", m, n+0, still+0, nowhere+0, flee+0, tgt+0, terr+0, near+0, carried+0, ai+0 }' "$F")"
  echo "    whereabouts $TAG$g: month $mon, overdue $n: standing $still, heading nowhere $nowhere, fleeing $flee, tactical target $tgt, in terrain $terr, hostile within 2k $near, non-modular AI $ai, carried $carried"
  return 0
}
