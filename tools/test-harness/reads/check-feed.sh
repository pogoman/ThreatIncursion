# check-feed.sh - the hw99/hw100 hypothesis: a swarm front is fed to the counter-attack line (counterGap), so no
# swarm front stands for years braced and battered. Per poll: the swarm front braced most (hw93a Eldfell: 12
# braces, 45 counter-attacks over 77 months), the one counter-attacked most, counter-attacks repelled anywhere,
# and hull break-ups. Flags a swarm front braced 12+ times, or 30+ counter-attacks on one. rowfor runs too.
. "$(dirname "$0")/check-rowfor.sh"
eval "$(declare -f check | sed '1s/^check ()/check_rowfor ()/')"
check() {
  local g=$1 F=$2
  check_rowfor "$g" "$F"
  local mon bmax bw cmax cw rep k
  eval "$(awk '/^Hulls: Your faction/{m++}
    /^Front deployed at .* \(threat\)/ { w=$0; sub(/^Front deployed at /,"",w); sub(/ \(threat\).*/,"",w); th[w]=1 }
    /^Front at .* braces for a counter-attack/ { w=$0; sub(/^Front at /,"",w); sub(/ braces.*/,"",w); if (!(w in th)) next; b[w]++; if (b[w]>bmax) { bmax=b[w]; bw=w } }
    /^Counter-attack at .* (battered|retook|overran)/ { w=$0; sub(/^Counter-attack at /,"",w); sub(/ (battered|retook|overran).*/,"",w); if (!(w in th)) next; c[w]++; if (c[w]>cmax) { cmax=c[w]; cw=w } }
    /^Counter-attack repelled at/ { rep++ }
    /broke up [0-9]+ FP of hulls into [0-9]+ troops/ { k++ }
    END { gsub(/\047/,"",bw); gsub(/\047/,"",cw); printf "mon=%d; bmax=%d; bw=\047%s\047; cmax=%d; cw=\047%s\047; rep=%d; k=%d\n", m, bmax+0, (bw==""?"-":bw), cmax+0, (cw==""?"-":cw), rep+0, k+0 }' "$F")"
  echo "    feed check $TAG$g: month $mon, swarm front braced most $bw ($bmax), counter-attacked most $cw ($cmax; $rep repelled anywhere), hull break-ups $k"
  [ "$bmax" -ge 12 ] && flag $g feed "a swarm front braced $bmax times at $bw (month $mon)"
  [ "$cmax" -ge 30 ] && flag $g feed "$cmax counter-attacks against the swarm front at $cw (month $mon)"
  return 0
}
