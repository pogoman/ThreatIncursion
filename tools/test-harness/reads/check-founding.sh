# check-founding.sh - the hw151 hypothesis (S6, a founding budget before growth, 2026-10-10): while a seeding is
# held on supplies, growth leaves one founding kit in stock, so the held waves sail (hw149c: 43 seedings held 1,200
# days on a stock of 0-2,600, 0 founded; hw149b founded 43 on a 84-167k stock). Per poll: foundings, sends held,
# the supplies stock, the feed's kit lines, hives, landings / eradicated. Flags `nofound` when 20+ budget lines
# have run with 0 foundings, and `starveout` when sustenance is short (the kit starving a colony).
check() {
  local g=$1 F=$2
  local founded held stock kitlines hives landings erad short
  founded=$(grep -c -i '^Colony founded\|^Notice: Hive Founded\|founds a hive' "$F")
  held=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'sends held [0-9]*' | head -1)
  stock=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'supplies [0-9]*' | head -1)
  kitlines=$(grep -c '^Feed: a seeding is held' "$F")
  hives=$(grep '^Census: threat hives' "$F" | tail -1 | grep -o 'hives [0-9]*' | head -1)
  landings=$(grep -c '^Siege pass (landing)' "$F")
  erad=$(grep -c '^Notice: Hive Eradicated' "$F")
  short=$(grep -c -i 'sustenance short\|starv' "$F")
  echo "    founding $TAG$g: founded $founded, ${held:-sends held ?}, $stock, kit lines $kitlines; $hives; landings $landings, eradicated $erad; starve lines $short"
  grep '^Feed: a seeding is held' "$F" | tail -1 | cut -c1-160 | sed 's/^/      /'
  [ "$kitlines" -ge 20 ] && [ "$founded" -eq 0 ] && flag $g nofound "$kitlines kit lines, 0 founded"
  return 0
}
