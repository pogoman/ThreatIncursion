# check-opening.sh - the hw143 hypothesis (the user, 2026-10-10: strikeStagedOpenFP 40000): the war's first strike
# waits until the strike fund holds 40k FP, so the humans' answer (which lands 190-420 days after the opening salvo
# and decided every game on the OceanPena seed by war day 2,000) meets a navy of 35-70k rather than 15-25k. Per poll:
# the opening (war day, fund, hives, navy at the first strike), the factions that have seen a hive, the human sieges
# sailed, the losing pressure. Flags `earlyopen` when the first strike sails with the fund under 35k, and `found`
# when three or more factions have seen hives before the first strike (the opening is not what woke them).
check() {
  local g=$1 F=$2
  local open fund hives navy seen sieges losing
  open=$(awk '/^Clock: day/ {split($0,a,"war "); split(a[2],b," "); w=b[1]+0} /^Census: threat hives/ {match($0,/hives ([0-9]+)/,h); match($0,/fleets ([0-9]+) FP/,f); hv=h[1]; fl=f[1]} /^Strike launched/ && !s {s=w; match($0,/strike fund left ([0-9]+) FP/,m); match($0,/([0-9]+) FP \+ ([0-9]+) drawn/,d); printf "d%d fund-after %d strike %d hives %d navy %d", w, m[1], d[1]+d[2], hv, fl; exit}' "$F")
  seen=$(grep '^Intel: [a-z_]* sees .* by eyes' "$F" | awk '{print $2}' | sort -u | wc -l)
  sieges=$(grep -c 'sails from' "$F")
  losing=$(grep '^Stance: losing pressure' "$F" | tail -1 | grep -o '\-> [0-9.]*' | cut -c4-); losing=${losing:-0}
  fund=$(grep 'Staged strike waits to open the war' "$F" | tail -1 | grep -o 'holds [0-9]* of [0-9]*' )
  echo "    opening $TAG$g: ${open:-not yet (${fund:-fund unread})}; factions that saw hives $seen; human sieges sailed $sieges; losing $losing"
  if [ -n "$open" ]; then
    local after strike
    after=$(echo "$open" | grep -o 'fund-after [0-9]*' | awk '{print $2}'); strike=$(echo "$open" | grep -o 'strike [0-9]*' | awk '{print $2}')
    [ $(( after + strike )) -lt 35000 ] && flag $g earlyopen "first strike with the fund at $(( after + strike )) FP"
    local before
    before=$(awk '/^Strike launched/ {exit} /^Intel: [a-z_]* sees .* by eyes/ {print $2}' "$F" | sort -u | wc -l)
    [ "$before" -ge 3 ] && flag $g found "$before factions saw hives before the first strike"
  fi
  return 0
}
