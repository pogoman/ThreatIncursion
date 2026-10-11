# check-bases.sh - the hw155 hypothesis (approaches first + the base one size up, 2026-10-11): the swarm strikes
# the forward bases nearest its hives first, whatever the focus, and sizes a strike at a base under size 4 for the
# base one size up (IncursionManager.sizedDefence). hw154a: 14 base strikes of 49, sized to "defence seen 120-370",
# 4 of 61 bases destroyed, 35 of 56 landings took the hive. Per poll: approach lines, base strikes (count, FP),
# sizing lines, bases founded / destroyed by a strike, sieges on hives, landings / eradicated, hives, fund.
# Flags `nobasekill` when 6+ base strikes have launched and none destroyed a base, and `noapproach` when the
# offensive has launched 3+ campaigns with no approaches line.
check() {
  local g=$1 F=$2
  local approach bstrikes bfp sizing founded destroyed landings erad hives fund launches
  approach=$(grep -c '^Offensive: approaches first' "$F")
  bstrikes=$(grep -o '^Strike launched from [A-Za-z'"'"' -]* at [A-Za-z'"'"' -]*Forward Base' "$F" | wc -l)
  bfp=$(grep -o '^Strike launched from [A-Za-z'"'"' -]* at [A-Za-z'"'"' -]*Forward Base ([a-z_]*, [0-9]* ly, ~[0-9]* days away; [0-9]* swarm(s) mustered in [0-9]* fleet(s), [0-9]* FP + [0-9]* drawn' "$F" \
    | awk '{s+=$(NF-5)+$(NF-1); n++} END{printf "%d", (n?s/n:0)}')
  sizing=$(grep -c '^Strike sizing:' "$F")
  founded=$(grep -c 'Frontline: .* founded' "$F")
  destroyed=$(grep -c 'destroyed by a Threat strike' "$F")
  landings=$(grep -c '^Siege pass (landing)' "$F")
  erad=$(grep -c '^Notice: Hive Eradicated' "$F")
  hives=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'hives [0-9]*' | head -1)
  fund=$(grep '^Offensive launched' "$F" | tail -1 | grep -o 'fund left [0-9]* FP')
  launches=$(grep -c '^Offensive launched' "$F")
  echo "    bases $TAG$g: approach lines $approach, base strikes $bstrikes (mean ${bfp} FP), sizing lines $sizing; bases founded $founded, destroyed by strike $destroyed; landings $landings, eradicated $erad; ${hives:-hives ?}; ${fund:-no launch}"
  grep '^Offensive: approaches first' "$F" | tail -1 | cut -c1-230 | sed 's/^/      /'
  [ "$bstrikes" -ge 6 ] && [ "$destroyed" -eq 0 ] && flag $g nobasekill "$bstrikes base strikes, 0 bases destroyed"
  [ "$launches" -ge 3 ] && [ "$approach" -eq 0 ] && flag $g noapproach "$launches campaigns, no approaches line"
  return 0
}
