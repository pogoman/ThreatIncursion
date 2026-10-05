use strict; use warnings; use JSON::PP;
# organs.pl <tag> (run from tools/warsim/validation): per hive, the war day its nexus / core first read disrupted in a monthly dump, its first siege
# bombardment, its landing and its last dump; the ordnance its sieges burned; and by month the hives able to earn.
my $tag = shift; my $T = "$ENV{TEMP}/threatinc-tests/ti-$tag.txt";
my (%h, %earn, %tot, %dmax);
for my $f (glob("$tag/*.json.data")) {
  local $/; open my $fh, "<", $f or next; my $d = eval { decode_json(<$fh>) } or next; my $w = $d->{warDay} // next; my $m = int($w / 30);
  for my $v (@{$d->{hives}}) {
    my $e = $h{$v->{name}} ||= { first => $w };
    $e->{last} = $w if !defined $e->{last} || $w > $e->{last}; $e->{first} = $w if $w < $e->{first};
    $e->{size} = $v->{size} if ($v->{size} // 0) > ($e->{size} // 0);
    for my $k (qw(nexus core forge)) { my $s = $v->{$k} // "none"; next if $s eq "up" || $s eq "none";
      $e->{$k} = $w if !defined $e->{$k} || $w < $e->{$k}; $e->{"${k}Months"}++; $dmax{$k} = $s if $s > ($dmax{$k} // 0); }
    $tot{$m}++; $earn{$m}++ if ($v->{nexus} // "") eq "up" && ($v->{core} // "") eq "up";
  }
}
open my $l, "<", $T or die; my $w = 0; my (%cur);
while (<$l>) {
  if (/^Clock: day -?\d+ war (\d+)/) { $w = $1; next; }
  if (/^Daily siege of (.+?), day (\d+): bombarded, \d+ -> (\d+) FP, (\d+) ordnance left/) { my $e = $h{$1} or next;
    $e->{bomb} //= $w; $e->{bombDays}{$w} = 1;
    my $c = $cur{$1}; if (!$c || $2 < $c->{day}) { $e->{ord} += $c->{hi} - $c->{lo} if $c; $c = $cur{$1} = { hi => $4, lo => $4 }; }
    $c->{day} = $2; $c->{lo} = $4 if $4 < $c->{lo}; $e->{fpdays} += $3; next; }
  if (/^Orbit cover over (.+?): the/) { my $e = $h{$1} or next; $e->{land} //= $w; }
}
for my $n (keys %cur) { $h{$n}{ord} += $cur{$n}{hi} - $cur{$n}{lo}; }
print "== $tag (war days; '-' never)\n";
printf "%-22s %4s %6s %6s %6s %6s %6s | %s\n", "hive", "size", "bombed", "nexus", "core", "landed", "gone", "months nexus/core down in dumps; ordnance burned in logged siege days";
my (@lag, @down);
for my $n (sort { ($h{$a}{bomb} // 9e9) <=> ($h{$b}{bomb} // 9e9) } keys %h) { my $e = $h{$n};
  printf "%-22s %4d %6s %6s %6s %6s %6s | %d / %d; %s\n", substr($n, 0, 22), $e->{size} // 0, map({ $e->{$_} // "-" } qw(bomb nexus core land)), $e->{last},
    $e->{nexusMonths} // 0, $e->{coreMonths} // 0, $e->{ord} // "-";
  push @down, $e->{last} - $e->{nexus} if defined $e->{nexus};
}
my @s = sort { $a <=> $b } @down; printf "days from the first dump with the nexus down to the hive's last dump: median %s, range %s-%s, n %d\n", $s[int($#s/2)], $s[0], $s[-1], scalar @s if @s;
print "longest disruption read in a dump: ", join(", ", map { "$_ " . int($dmax{$_}) . " d" } sort keys %dmax), "\n";
print "hives able to earn (nexus and core up) of hives, by month: ", join("  ", map { "m$_ " . ($earn{$_} // 0) . "/" . $tot{$_} } grep { $_ % 4 == 0 && $_ >= 40 } sort { $a <=> $b } keys %tot), "\n";
