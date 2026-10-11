# check-founding.sh - the hw156 hypothesis (founding convoys, 2026-10-11): a forward base's kit sails from the
# paying base in a real convoy (ThreatConvoys.dispatchFounding) and the base is founded when it lands with
# the whole kit (pollFounding -> ThreatFrontlines.foundLanded). hw155: 58-67 bases founded a game on the
# spot, ~6 founded per base the swarm killed. Per poll: convoys dispatched / landed / lost / turned back /
# arrived short, passage refusals, bases founded and destroyed by strike, base strikes, hives, fund.
# Flags `nosail` when the frontline has planned (any "Frontline:" line) but no founding convoy sailed in a
# game past 200 war days of log, `nolanding` when 6+ sailed and none landed, and `allshort` when 3+ arrived
# short (the escort eats the kit, or fights take it every time).
check() {
  local g=$1 F=$2
  local sailed landed lost back short passage founded destroyed bstrikes hives fund frontline
  sailed=$(grep -c '^Founding convoy dispatched' "$F")
  landed=$(grep -c '^Frontline: the kit for .* landed' "$F")
  lost=$(grep -c '^Founding convoy lost' "$F")
  back=$(grep -c '^Founding convoy turned back' "$F")
  short=$(grep -c '^Founding convoy arrived short' "$F")
  passage=$(grep -c 'cannot pay the passage' "$F")
  founded=$(grep -c 'Frontline: .* founded' "$F")
  destroyed=$(grep -c 'destroyed by a Threat strike' "$F")
  bstrikes=$(grep -o '^Strike launched from [A-Za-z'"'"' -]* at [A-Za-z'"'"' -]*Forward Base' "$F" | wc -l)
  hives=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'hives [0-9]*' | head -1)
  fund=$(grep '^Offensive launched' "$F" | tail -1 | grep -o 'fund left [0-9]* FP')
  frontline=$(grep -c '^Frontline:' "$F")
  echo "    founding $TAG$g: convoys sailed $sailed, landed $landed, lost $lost, turned back $back, short $short; passage refused $passage; bases founded $founded, destroyed by strike $destroyed, base strikes $bstrikes; ${hives:-hives ?}; ${fund:-no launch}"
  grep '^Founding convoy dispatched' "$F" | tail -1 | cut -c1-200 | sed 's/^/      /'
  [ "$frontline" -ge 50 ] && [ "$sailed" -eq 0 ] && flag $g nosail "$frontline frontline lines, no founding convoy sailed"
  [ "$sailed" -ge 6 ] && [ "$landed" -eq 0 ] && flag $g nolanding "$sailed founding convoys sailed, none landed"
  [ "$short" -ge 3 ] && flag $g allshort "$short founding convoys arrived short of the kit"
  return 0
}
