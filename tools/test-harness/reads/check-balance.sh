# check-balance.sh - the hw138 hypothesis (0c463b6b, 8eb8fc59): the economy plans for redundancy. Per poll, the
# latest `Mineable balance:` census line - each input's cover in full-strength worlds and how far behind the
# leader. Flags `lopsided` when, after 20 hives, volatiles or rare ore still trail the leader by 0.6 or more
# (hw137c ended at 4.5 volatiles worlds against ore's 20.4 - behind 0.78), and `onesource` when an input with
# 8+ consumers has 2 or fewer sources after war day 1500.
check() {
  local g=$1 F=$2
  local line
  line=$(grep '^Mineable balance:' "$F" | tail -1)
  [ -z "$line" ] && { echo "    balance $TAG$g: no census line yet"; return 0; }
  local hives war
  hives=$(grep -o '^Census: threat hives [0-9]*' "$F" | tail -1 | grep -o '[0-9]*$'); hives=${hives:-0}
  war=$(grep -o 'war [0-9]*' "$F" | tail -1 | cut -d' ' -f2); war=${war:-0}
  echo "    balance $TAG$g (hives $hives): $(echo "$line" | sed 's/^Mineable balance: //' | cut -c1-300)"
  while read -r c cov beh src cons; do
    [ -z "$c" ] && continue
    if [ "$hives" -ge 20 ] && awk "BEGIN{exit !($beh >= 0.6)}"; then flag $g lopsided "$c $cov worlds, behind $beh at $hives hives"; fi
    if [ "$war" -gt 1500 ] && [ "$cons" -ge 8 ] && [ "$src" -le 2 ]; then flag $g onesource "$c $src sources for $cons consumers at war day $war"; fi
  done <<< "$(echo "$line" | perl -ne 'while (/(\w+) ([\d.]+) worlds \(behind ([\d.]+), sources (\d+), best (\d+) of (\d+) for (\d+)/g) { print "$1 $2 $3 $4 $7\n" }' )"
  return 0
}
