# check-chest.sh - the hw152 hypothesis (F1, 2026-10-11): the chest counts a prong fieldable only if the supplies away
# keep it too (ThreatOffensive.cheapestProngCost), so hw151c's fund - 89-137k FP, every prong "out on the supplies
# away" - reads poor, the expansion tithe runs, and ThreatFuel.canFound needs the kit alone (kept by S6) rather than a
# surplus: the 37 held seedings sail. Per poll: the chest's last transition, "out on the supplies" passes, foundings,
# sends held, stock, hives, landings / eradicated. Flags `chestfull` when the chest still reads full with a prong out
# on the supplies, and `nofound` when 20+ kit lines have run with 0 foundings.
check() {
  local g=$1 F=$2
  local founded held stock hives landings erad chest outsup kitlines full flips
  founded=$(grep -c -i '^Colony founded\|^Notice: Hive Founded\|founds a hive' "$F")
  held=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'sends held [0-9]*' | head -1)
  stock=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'supplies [0-9]*' | head -1)
  hives=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'hives [0-9]*' | head -1)
  landings=$(grep -c '^Siege pass (landing)' "$F")
  erad=$(grep -c '^Notice: Hive Eradicated' "$F")
  outsup=$(grep -c 'out on the supplies' "$F")
  kitlines=$(grep -c '^Feed: a seeding is held' "$F")
  chest=$(grep '^Stance: the chest is' "$F" | tail -1 | cut -c1-110)
  flips=$(grep -c '^Stance: the chest is' "$F")
  full=$(grep '^Stance: ' "$F" | grep -o 'chest [0-9]* of [0-9]* FP a horizon, cheapest prong [0-9]* FP[^;]*' | tail -1)
  echo "    chest $TAG$g: founded $founded, ${held:-sends held ?}, $stock; $hives; landings $landings, eradicated $erad; out-on-supplies , kit lines , chest flips "
  echo "      last: ${full:-no stance line}; ${chest:-no chest transition}"
  case "$full" in *"(full)"*) [ "$outsup" -gt 0 ] && flag $g chestfull "chest reads full with $outsup passes out on the supplies";; esac
  [ "$kitlines" -ge 20 ] && [ "$founded" -eq 0 ] && flag $g nofound "$kitlines kit lines, 0 founded"
  [ "$flips" -ge 4 ] && flag $g flap "the chest flipped $flips times"
  return 0
}
