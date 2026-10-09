# check-belt.sh - the hw128/hw129 read: the go-slow belt guard (ThreatFleetComposer.beltGuard). Per poll:
# frozen pairs (same id, same position a span apart) - the hypothesis is ~0 of them; reads whose fleet has no
# guard (`belt guard none` - every reinforcement should carry one); reads whose guard has tripped; carries
# (`carried to`). Flags `frozen` on the first frozen pair (with its terrain, ring and guard count) and
# `noguard` on the first reinforcement without a guard.
. "$(dirname "$0")/check-engine.sh"
eval "$(declare -f check | sed '1s/^check ()/check_listed ()/')"
check() {
  local g=$1 F=$2
  check_regional "$g" "$F"
  local out
  out=$(perl -ne 'next unless index($_,"Reinforcement overdue:")==0; $n++; $carry++ if /carried to/; my ($gd)=/belt guard (\w+)/; $none++ if defined $gd && $gd eq "none"; $trip++ if defined $gd && $gd =~ /^\d+$/ && $gd > 0;
    my ($id)=/ id (\w+),/; my ($x,$y)=/ at (-?\d+)\/(-?\d+) \(/; next unless $id && defined $x;
    if (my $p=$l{$id}) { if (abs($x-$p->[0])<1 && abs($y-$p->[1])<1) { $fz++; } else { $mv++ } } $l{$id}=[$x,$y];
    END { printf "%d %d %d %d %d %d\n", $n, $fz, $mv, $none, $trip, $carry }' "$F")
  set -- $out
  echo "    belt $TAG$g: overdue reads $1 - frozen pairs $2, moving pairs $3; no guard $4, guard tripped $5; carried $6"
  [ "${2:-0}" -gt 0 ] && flag $g frozen "$(perl -ne 'next unless index($_,"Reinforcement overdue:")==0; my ($id)=/ id (\w+),/; my ($x,$y)=/ at (-?\d+)\/(-?\d+) \(/; next unless $id && defined $x; if (my $p=$l{$id}) { if (abs($x-$p->[0])<1 && abs($y-$p->[1])<1) { my ($t)=/(terrain [^,]*)/; my ($r)=/(ring \[[^\]]*\])/; my ($gd)=/(belt guard \w+, go slow \w+)/; print "id $id $t $gd $r"; exit } } $l{$id}=[$x,$y];' "$F" | cut -c1-320)"
  [ "${4:-0}" -gt 0 ] && flag $g noguard "$(grep -m1 'belt guard none' "$F" | cut -c1-200)"
  return 0
}
