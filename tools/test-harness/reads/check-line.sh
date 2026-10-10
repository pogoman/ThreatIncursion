# check-line.sh - the hw150 hypothesis (S1, the line, 2026-10-10): an exposed hive needs at least the strongest
# faction's median siege seen x break-off x margin, so sieges turn home instead of landing and the frontier stops
# churning (hw148b: 17 landings, 19 cores destroyed in 300 days with the defender below the siege in 77 of 78
# fights). Per poll: the latest line figure, landings, eradications, sieges called off, the fund's hold payments,
# the supplies stock. Flags `lineleak` when landings keep pace with eradications past 10 of each, and `linehigh`
# when the line reads over 4,000 FP a colony (one big siege lifting every frontier want).
check() {
  local g=$1 F=$2
  local line landings erad off paid supplies
  line=$(grep '^Posture: the line at' "$F" | tail -1 | grep -o '- [0-9]* FP a colony ([^)]*)' | cut -c3-)
  landings=$(grep -c '^Siege pass (landing)' "$F")
  erad=$(grep -c '^Notice: Hive Eradicated' "$F")
  off=$(grep -c -i 'calls off\|called off\|turns home' "$F")
  paid=$(grep '^Offensive: the defence first - [0-9]* FP from the fund' "$F" | grep -o 'first - [0-9]*' | awk '{s+=$3} END{print s+0}')
  supplies=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'supplies [0-9]*' | head -1)
  echo "    line $TAG$g: ${line:-no line yet}; landings $landings, eradicated $erad, called off $off; fund paid $paid FP; $supplies"
  local lfp
  lfp=$(echo "$line" | grep -o '^[0-9]*'); lfp=${lfp:-0}
  [ "$lfp" -gt 4000 ] && flag $g linehigh "the line reads $lfp FP a colony"
  [ "$landings" -ge 10 ] && [ "$erad" -ge 10 ] && [ "$erad" -ge "$landings" ] && flag $g lineleak "$landings landings, $erad eradicated - the line stops nothing"
  return 0
}
