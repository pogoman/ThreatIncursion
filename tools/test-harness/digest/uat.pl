# uat.pl <tag> [month=120]: one game run against the two fixes of 2026-10-04 (stance floor 243b4a0, marine
# headroom / no doomed landing dc7274a). Counts to the month given unless marked "end".
use strict; use warnings;
my ($tag, $m) = @ARGV; $m ||= 120; my $upto = $m * 30;
my $d = "$ENV{TEMP}/threatinc-tests";
open my $f, '<', "$d/ti-$tag.txt" or die "no log for $tag\n";
my ($day, %lost, %n, %stance, @enter, %open, @landed, @overrunAfter, $hives, $hivesEnd) = (0);
my ($cur, $since) = ('EXPAND', 0);
while (<$f>) { s/\r//; chomp;
  if (/^Clock: day \S+ war (\d+)/) { $day = $1; next }
  my $in = $day <= $upto;
  if (/^Notice: Colony Lost/) { $lost{end}++; for my $k (108, 120, 126) { $lost{$k}++ if $day / 30 < $k + 1 } next }
  if (/^Census: threat hives (\d+)/) { $hivesEnd = $1; $hives = $1 if $in; next }
  if (/^Stance: (\w+)->(\w+) - pressed (\d+)\/(\d+), attacked (\d+)/) {
    my $t = $day < $upto ? $day : $upto;
    $stance{$cur} += $t - $since if $t > $since; ($cur, $since) = ($2, $t);
    push @enter, sprintf("m%d %d/%d", $day / 30, $3, $4) if $2 eq 'CONSOLIDATE' && $in;
    next }
  next unless $in;
  if (/^Notice: (Threat Landing|Beachhead Overrun|Hive Eradicated|Expedition Landed|Siege Called Off)/) { $n{$1}++; next }
  if (/^Siege pass at .*: no landing - /) { $n{doomed}++; next }
  if (/^Front deployed at (.+?) \((\w+)\): (\d+) marines/) {
    if ($2 ne 'threat') { $open{$1} = $3; push @landed, $3 } else { delete $open{$1} }
    next }
  if (/^Counter-attack at (.+?) overran the beachhead/) { if (exists $open{$1}) { push @overrunAfter, delete $open{$1} } next }
}
my $last = $day < $upto ? $day : $upto; $stance{$cur} += $last - $since if $last > $since;
my @s = sort { $a <=> $b } @landed; my $med = @s ? $s[int(@s / 2)] : 0;
printf "=== %s (ran to month %d)\n", $tag, $day / 30;
printf "worlds lost: m108 %d, m120 %d, m126 %d, end %d\n", map { $lost{$_} || 0 } 108, 120, 126, 'end';
printf "hives at m%d: %s (end %s)\n", $m, $hives // '?', $hivesEnd // '?';
printf "stance months to m%d: EXPAND %d, PRESS %d, CONSOLIDATE %d\n", $m, map { ($stance{$_} || 0) / 30 } qw(EXPAND PRESS CONSOLIDATE);
print "entered CONSOLIDATE at (month pressed/systems): ", (@enter ? join(', ', @enter) : 'never'), "\n";
printf "Threat landings %d, Threat beachheads overrun %d\n", $n{'Threat Landing'} || 0, $n{'Beachhead Overrun'} || 0;
printf "human landings on hives %d (median %d marines), overrun %d, doomed landings refused %d, sieges called off %d\n",
  scalar @landed, $med, scalar @overrunAfter, $n{doomed} || 0, $n{'Siege Called Off'} || 0;
printf "hives eradicated %d\n", $n{'Hive Eradicated'} || 0;
if (open my $e, '<', "$d/exc-$tag.txt") { my @x = grep { !/not found in ship_data\.csv/ && /\S/ } <$e>; printf "exception lines %d%s\n", scalar @x, @x ? ": $x[0]" : '' }
