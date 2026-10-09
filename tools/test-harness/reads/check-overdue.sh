# check-overdue.sh - the hw105/hw106 hypothesis: reinforcements out 90+ days are read (where they stand) and ordered on
# again, so no swarm sits a year in its source system while its target falls. Per poll: overdue lines, where they
# stood (same system as target / another system / hyperspace / in battle), how many were ordered on more than once
# (the same fleet logged twice = the kick did not move it). Flags a fleet kicked 3+ times. feed and rowfor run too.
. "$(dirname "$0")/check-feed.sh"
eval "$(declare -f check | sed '1s/^check ()/check_feed ()/')"
check() {
  local g=$1 F=$2
  check_feed "$g" "$F"
  local mon n same other hyper battle again thrice
  eval "$(awk '/^Hulls: Your faction/{m++}
    /^Reinforcement overdue:/ { n++; if ($0 ~ / units from /) same++; else if ($0 ~ /, in .* units from a jump point|no jump point/) other++; else if ($0 ~ /in hyperspace/) hyper++; if ($0 ~ /battle true/) battle++;
      k=$0; sub(/^Reinforcement overdue: /,"",k); sub(/, [0-9]+ d out.*/,"",k); c[k]++; if (c[k]==2) again++; if (c[k]==3) thrice++ }
    END { printf "mon=%d; n=%d; same=%d; other=%d; hyper=%d; battle=%d; again=%d; thrice=%d\n", m, n+0, same+0, other+0, hyper+0, battle+0, again+0, thrice+0 }' "$F")"
  echo "    overdue check $TAG$g: month $mon, overdue $n (same system $same, other system $other, hyperspace $hyper, in battle $battle), logged again $again, 3+ times $thrice"
  [ "$thrice" -ge 1 ] && flag $g overdue "$thrice reinforcement(s) ordered on 3+ times without arriving (month $mon)"
  return 0
}
