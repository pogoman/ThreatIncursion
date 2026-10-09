# check-churn.sh - the hw79-hw82 hypothesis: nothing rebuilds what the fit recycles.
# Churn is a swarm built for a hive and recycled at that hive: over the last three censused months, per hive,
# min(built, recycled) summed, where "built" is the nexus's builds AT the hive (Garrison fleet fabricated /
# swarm fabricated into) and the pressure pass's fabrication FOR the hive (Posture: X fabricated a ... for Y,
# paired on Y - pairing on the fabricator X false-flagged hw81a's hub Bascule, which recycled its own garrison
# and fabricated for attacked Culann). 30+ flags (hw79a: 27 of 62 windows). Prints the pair each poll.
check() {
  local g=$1 F=$2
  local t
  t=$(awk '/^Hulls: Your faction/{m++}
    /^Garrison fleet fabricated at|^Garrison swarm fabricated into/ { x=$0; sub(/^Garrison (fleet fabricated at|swarm fabricated into a standing fleet at) /,"",x); sub(/ \(.*/,"",x); b[m, x]++; r[m, x]+=0 }
    /^Posture: .* fabricated a [0-9]* FP swarm for/ { x=$0; sub(/.* swarm for /,"",x); sub(/ \(.*/,"",x); b[m, x]++; r[m, x]+=0 }
    /^Navy: .* recycled/ { x=$0; sub(/^Navy: /,"",x); sub(/ recycled.*/,"",x); r[m, x]++; b[m, x]+=0 }
    END{ rs=0; bs=0; ch=0;
      for (k in b) { split(k, p, SUBSEP); if (p[1]+0 >= m-2) { bs += b[k]; rs += r[k]; ch += (b[k] < r[k] ? b[k] : r[k]) } }
      printf "%d %d %d %d", m, rs, bs, ch }' "$F")
  set -- $t
  echo "    churn check $TAG$g: month $1, last 3 months recycled $2 built+fabricated $3, same-hive churned $4"
  [ "$1" -ge 20 ] && [ "$4" -ge 30 ] && flag $g churn "same-hive build-and-recycle: $4 swarms churned in the last 3 months (recycled $2, built $3, month $1)"
  return 0
}
