# check-engine.sh - the hw122/hw123 read: is a frozen reinforcement in the list the engine advances?
# (ThreatColonyManager.engineLists, after the 2026-10-09 decompile.) Per poll: overdue lines by `engine list xN`,
# `location held`, the pulse (never ran / frames), and frozen pairs (same fleet id, same position, a span
# apart) whose `moveDest` expiry is identical (not advanced between the reads). Flags `engine` when the
# read cannot find the engine's interface (`engine list unknown`) - the read itself is broken then.
. "$(dirname "$0")/check-regional.sh"
eval "$(declare -f check | sed '1s/^check ()/check_regional ()/')"
check() {
  local g=$1 F=$2
  check_regional "$g" "$F"
  local out
  out=$(perl -ne 'next unless index($_,"Reinforcement overdue:")==0; $n++;
    $e0++ if /engine list x0/; $e1++ if /engine list x1/; $eu++ if /engine list unknown/; $lf++ if /location held false/;
    $pn++ if /pulse never ran/; $pf++ if /pulse \d+ frames/;
    my ($id)=/ id (\w+),/; my ($x,$y)=/ at (-?\d+)\/(-?\d+) \(/; my ($md)=/moveDest ([0-9.]+|none)/; my ($el)=/engine list x(\d+)/; my ($pfr)=/pulse (\d+) frames/;
    if ($id && defined $x) { if (my $p=$l{$id}) { if (abs($x-$p->[0])<1 && abs($y-$p->[1])<1) { $fz++; $fzmd++ if defined $md && $md eq $p->[2]; $fze0++ if defined $el && $el eq "0"; $fzpf++ if defined $pfr && defined $p->[3] && $pfr == $p->[3]; } } $l{$id}=[$x,$y,$md//"",$pfr]; }
    END { printf "%d %d %d %d %d %d %d %d %d %d %d\n", $n, $e0, $e1, $eu, $lf, $pn, $pf, $fz, $fzmd, $fze0, $fzpf }' "$F")
  set -- $out
  echo "    engine $TAG$g: overdue $1 - engine list x0 $2 / x1 $3 / unknown $4, location not held $5, pulse never $6 / ran $7; frozen pairs $8: moveDest unchanged $9, not in engine list ${10}, pulse frames unchanged ${11}"
  [ "$4" -gt 0 ] && flag $g engine "the engine-list read cannot find the CampaignEntity interface ($4 reads)"
  return 0
}
# hw124+: the pulse's frame times - frozen vs moving pairs: mean live calls, mean seconds given, mean step between live calls.
eval "$(declare -f check | sed '1s/^check ()/check_engine ()/')"
check() {
  local g=$1 F=$2
  check_engine "$g" "$F"
  perl -ne 'next unless index($_,"Reinforcement overdue:")==0; my ($id)=/ id (\w+),/; my ($x,$y)=/ at (-?\d+)\/(-?\d+) \(/;
    my ($lv,$sm,$mx,$st)=/\(live (\d+), ([0-9.]+) s, max ([0-9.]+) s, step (\d+)\)/; next unless $id && defined $x && defined $lv;
    if (my $p=$l{$id}) { my $mv=sqrt(($x-$p->[0])**2+($y-$p->[1])**2); my $k=$mv<1?"frozen":"moving"; $n{$k}++; $L{$k}+=$lv-$p->[2]; $S{$k}+=$sm-$p->[3]; $T{$k}+=$st; $M{$k}=$mx if $mx>($M{$k}//0); }
    $l{$id}=[$x,$y,$lv,$sm];
    END { for my $k (qw(frozen moving)) { next unless $n{$k}; printf "    pulse '"$TAG$g"' %s pairs %d: live calls a span %.0f, seconds given a span %.0f, step %.0f units, max frame %.3f s\n", $k, $n{$k}, $L{$k}/$n{$k}, $S{$k}/$n{$k}, $T{$k}/$n{$k}, $M{$k} } }' "$F"
  return 0
}
# hw126+: how many locations list each overdue fleet (`listed in N (...)`), frozen vs moving pairs, and the
# pulse ring's distinct containing locations. Flags `listed2` on the first fleet listed in two or more
# locations (the hypothesis for the two-point flip) - a finding, not a fault: read it, then ignore it.
eval "$(declare -f check | sed '1s/^check ()/check_frames ()/')"
check() {
  local g=$1 F=$2
  check_frames "$g" "$F"
  local out
  out=$(perl -ne 'next unless index($_,"Reinforcement overdue:")==0; my ($id)=/ id (\w+),/; my ($x,$y)=/ at (-?\d+)\/(-?\d+) \(/;
    my ($ls)=/listed in (\d+)/; next unless $id && defined $ls; $n++; $two++ if $ls>=2; $zero++ if $ls==0;
    my ($ring)=/ring \[([^\]]*)\]/; my %d; if (defined $ring) { $d{(split / -?\d+\//,$_)[0]}=1 for split /; /,$ring; $rl++ if keys(%d)>1; }
    if (my $p=$l{$id}) { my $k=(abs($x-$p->[0])<1 && abs($y-$p->[1])<1)?"frozen":"moving"; $pn{$k}++; $p2{$k}++ if $ls>=2; $rr{$k}++ if keys(%d)>1; }
    $l{$id}=[$x,$y];
    END { printf "%d %d %d %d %d %d %d %d %d %d\n", $n, $two, $zero, $rl, $pn{frozen}//0, $p2{frozen}//0, $rr{frozen}//0, $pn{moving}//0, $p2{moving}//0, $rr{moving}//0 }' "$F")
  set -- $out
  echo "    listed $TAG$g: reads $1 - in 2+ locations $2, in none $3, ring with 2+ locations $4; frozen pairs $5 (2+ listed $6, ring 2+ $7), moving $8 (2+ listed $9, ring 2+ ${10})"
  [ "${2:-0}" -gt 0 ] && flag $g listed2 "$(grep -m1 'listed in [2-9]' "$F" | grep -o 'id [0-9a-f]*\|listed in [0-9]* ([^)]*)\|ring \[[^]]*\]' | tr '\n' ' ' | cut -c1-300)"
  [ "${4:-0}" -gt 0 ] && flag $g listed2 "ring spans two locations: $(perl -ne 'next unless /(id \w+).*(ring \[([^\]]*)\])/; my ($i,$r,$in)=($1,$2,$3); my %d; $d{(split / -?\d+\//,$_)[0]}=1 for split /; /,$in; if (keys(%d)>1) { print "$i $r"; exit }' "$F" | cut -c1-300)"
  return 0
}
