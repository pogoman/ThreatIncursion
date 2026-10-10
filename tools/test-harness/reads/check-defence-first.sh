# check-defence-first.sh - the hw142 hypothesis (ThreatOffensive.payDefence, the user's C1, 2026-10-10): while any
# hive is short of what today's pressure needs held, the strike fund pays that defence and no prong is priced or
# sails - the navy stays home through the human campaign instead of leaving on strikes (hw140c: 50 strikes, 85k FP,
# while 41 sieges took 21 hives). Per poll: the defence-first lines (paid, blocked passes, held prongs), the FP paid,
# the strikes launched after the first human siege, and the losing pressure. Flags `leak` when strikes launched
# after the first human siege while a defence-first block was logged in the same window exceed 10, and
# `starvedfund` when the fund paid the defence 30+ times and the hives still fell by half from their peak.
check() {
  local g=$1 F=$2
  local paid paidfp blocked held losing hs strikes hives peak
  paid=$(grep -c '^Offensive: the defence first - [0-9]* FP from the fund' "$F")
  paidfp=$(grep '^Offensive: the defence first - [0-9]* FP from the fund' "$F" | grep -o 'first - [0-9]*' | awk '{s+=$3} END{print s+0}')
  blocked=$(grep -c 'no prong priced' "$F"); held=$(grep -c 'cannot sail today - the defence first' "$F")
  losing=$(grep '^Stance: losing pressure' "$F" | tail -1 | grep -o '\-> [0-9.]*' | cut -c4-); losing=${losing:-0}
  hs=$(grep -n 'sails from' "$F" | head -1 | cut -d: -f1)
  if [ -n "$hs" ]; then strikes=$(awk -v f="$hs" 'NR>f && /^Strike launched/' "$F" | wc -l); else strikes="(no human siege yet)"; fi
  hives=$(grep '^Census: threat' "$F" | tail -1 | grep -o 'hives [0-9]*' | awk '{print $2}'); hives=${hives:-0}
  peak=$(grep '^Census: threat' "$F" | grep -o 'hives [0-9]*' | awk '{if($2>m)m=$2} END{print m+0}')
  echo "    defence-first $TAG$g: paid $paid times ($paidfp FP), passes blocked $blocked, prongs held $held; strikes after the first human siege $strikes; losing $losing; hives $hives (peak $peak)"
  if [ -n "$hs" ] && [ "$blocked" -gt 0 ] && [ "$strikes" -gt 10 ]; then
    local after
    after=$(awk -v f="$hs" 'NR>f && /no prong priced/' "$F" | wc -l)
    [ "$after" -gt 0 ] && [ "$strikes" -gt 10 ] && flag $g leak "$strikes strikes launched after the first human siege with $after defence-first blocks"
  fi
  [ "$paid" -ge 30 ] && [ "$peak" -gt 0 ] && [ $(( hives * 2 )) -lt "$peak" ] && flag $g starvedfund "fund paid the defence $paid times, hives $hives of a $peak peak"
  return 0
}
