# check-staged.sh - the hw157 hypothesis (staged launches, 2026-10-11 14:30): an NPC task force, hunt or raid
# sails only with its full fuel, deposit and hold from the base it launches at (ThreatFleetOrders.buildTaskForce,
# "Order held at"), nobody pays toward it remotely (ThreatSoftening.huntDonors empty), so no fleet launches
# with an empty hold and stand-downs "supplies low" with 0 aboard vanish (gs1a: 105 of 838 draws hold 0,
# 105 of 207 stand-downs 0 aboard). Raider margin 3 (was 1.5): founding convoys should die sometimes
# (hw156: 0 of 135). Per poll: order draws / zero-hold draws / orders held / stand-downs (0 aboard) /
# restocks / founding convoys sailed, landed, lost / bases founded, destroyed / hives / fund.
# Flags `zerohold` when any draw launched with hold 0 of N>0 (the rule is broken), `allheld` when 30+
# orders were held and no order drew in a game past 200 war days (bases never staged - the planner does
# not fill them), `nolaunch0` is informational only.
check() {
  local g=$1 F=$2
  local draws zero held trimmed stood stood0 restock sailed landed lost founded destroyed hives fund frontline
  draws=$(grep -c '^Order draw at' "$F")
  zero=$(grep '^Order draw at' "$F" | grep -c 'hold 0 of [1-9]')
  held=$(grep -c "^Order held at" "$F"); trimmed=$(grep -c "^Order trimmed at" "$F")
  stood=$(grep -c 'stood down (supplies low' "$F")
  stood0=$(grep 'stood down (supplies low' "$F" | grep -c ', 0 aboard')
  restock=$(grep -c '^Restocked at' "$F")
  sailed=$(grep -c '^Founding convoy dispatched' "$F")
  landed=$(grep -c '^Frontline: the kit for .* landed' "$F")
  lost=$(grep -c '^Founding convoy lost' "$F")
  founded=$(grep -c 'Frontline: .* founded' "$F")
  destroyed=$(grep -c 'destroyed by a Threat strike' "$F")
  hives=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'hives [0-9]*' | head -1)
  fund=$(grep '^Offensive launched' "$F" | tail -1 | grep -o 'fund left [0-9]* FP')
  frontline=$(grep -c '^Frontline:' "$F")
  echo "    staged $TAG$g: order draws $draws (hold 0: $zero), held $held, trimmed $trimmed; stood down supplies low $stood (0 aboard $stood0), restocked $restock; founding sailed $sailed, landed $landed, lost $lost; bases founded $founded, destroyed $destroyed; ${hives:-hives ?}; ${fund:-no launch}"
  grep '^Order held at' "$F" | tail -1 | cut -c1-200 | sed 's/^/      /'
  [ "$zero" -ge 1 ] && flag $g zerohold "$zero order draws launched with an empty hold"
  [ "$frontline" -ge 50 ] && [ "$held" -ge 30 ] && [ "$draws" -eq 0 ] && flag $g allheld "$held orders held, none drew - bases never staged"
  return 0
}
