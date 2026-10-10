# check-consolidate.sh - the hw139 hypothesis (ThreatPosture.consolidate, 2026-10-10): a losing swarm masses its
# garrisons on keystones instead of losing them a hive at a time (hw138b: 33.8k -> 7.9k FP in five months at 27-29
# hives, 0 rallies against 62 siege fights). Per poll: the losing pressure, the consolidation lines, and - while
# consolidating - the fleet FP trend. Flags `bleed` when a consolidating game's fleets fall by half within six
# censuses, and `noconsolidate` when losing reads 0.5+ for three censuses with no consolidating line.
check() {
  local g=$1 F=$2
  local losing cons ends last
  losing=$(grep '^Stance: losing pressure' "$F" | tail -1 | grep -o '\-> [0-9.]*' | cut -c4-); losing=${losing:-0}
  cons=$(grep -c '^Posture: consolidating' "$F"); ends=$(grep -c '^Posture: consolidation ends' "$F")
  last=$(grep '^Posture: consolidating' "$F" | tail -1 | cut -c22-260)
  echo "    consolidate $TAG$g: losing $losing, consolidating lines $cons (ends $ends)${last:+; last: $last}"
  if [ "$cons" -gt "$ends" ]; then
    local trend
    trend=$(grep '^Census: threat' "$F" | tail -6 | grep -o 'fleets [0-9]*' | awk '{print $2}' | tr '\n' ' ')
    echo "    fleets (last 6 censuses): $trend"
    set -- $trend
    if [ $# -ge 6 ] && [ "$1" -gt 0 ] && [ $(( $6 * 2 )) -lt "$1" ]; then flag $g bleed "consolidating, fleets $1 -> $6 FP over six censuses"; fi
  fi
  local high
  high=$(grep '^Stance: losing pressure' "$F" | grep -o '\-> [0-9.]*' | cut -c4- | awk '$1>=0.5' | wc -l)
  [ "$high" -ge 3 ] && [ "$cons" -eq 0 ] && flag $g noconsolidate "losing read 0.5+ $high times, no consolidating line"
  return 0
}
