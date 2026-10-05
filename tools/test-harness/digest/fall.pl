use strict; use warnings;
# fall.pl <tags> : how long a hive world takes to fall, from ti-<tag>.txt (cwd, else %TEMP%\threatinc-tests):
# the days each unspawned siege spent over its world by how it ended; and for every hive eradicated, its size,
# the days from the first siege fight over it to the landing that took it, from that landing to the last stratum,
# and the two together. Days are war days (the Clock line).
sub median { my @s = sort { $a <=> $b } @_; return @s ? $s[int($#s / 2)] : "-"; }
sub spread { my @s = sort { $a <=> $b } @_; return @s ? sprintf("median %d, range %d-%d, n %d", $s[int($#s / 2)], $s[0], $s[-1], scalar @s) : "none"; }
my %pool;
for my $tag (@ARGV) {
  my $f = -f "ti-$tag.txt" ? "ti-$tag.txt" : "$ENV{TEMP}/threatinc-tests/ti-$tag.txt";
  open(my $h, "<", $f) or next;
  my $war = 0;
  my (%size, %firstFight, %landed, %landMarines, %firstStratum);
  my (%siegeDays, %fightDays, @rows);
  while (my $l = <$h>) {
    if ($l =~ /^Clock: day -?\d+ war (\d+)/) { $war = $1; next; }
    if ($l =~ /^Colony founded: (.+?)\s*$/) { $size{$1} = 1; next; }
    if ($l =~ /^Colony grew to (\d+): (.+?)\s*$/) { $size{$2} = $1; next; }
    if ($l =~ /^Off-screen fight over (.+?) \(daily siege\)/) { $firstFight{$1} = $war unless exists $firstFight{$1}; next; }
    if ($l =~ /^Daily siege of (.+?), day 1:/) { $firstFight{$1} = $war unless exists $firstFight{$1}; next; }
    if ($l =~ /^Daily siege of (.+?): (\d+) d, \d+ -> \d+ FP, (\d+) fight days, (called off|beaten|landed \(ready\)|landed \(dry\)|landed|front|guns)/) {
      push @{ $siegeDays{$4} }, $2; push @{ $fightDays{$4} }, $3; next;
    }
    if ($l =~ /^Front deployed at (.+?) \((\w+)\): (\d+) marines/) {
      next if $2 eq "threat";
      $landed{$1} = $war; $landMarines{$1} = $3; delete $firstStratum{$1}; next;
    }
    if ($l =~ /^Front took stratum 1\/\d+ at (.+?)\s*$/) { $firstStratum{$1} = $war unless exists $firstStratum{$1}; next; }
    if ($l =~ /^Colony eradicated: (.+?)\s*$/) {
      my $w = $1;
      next unless exists $landed{$w};
      push @rows, [ $size{$w} || 0, $w, exists $firstFight{$w} ? $landed{$w} - $firstFight{$w} : -1,
        $war - $landed{$w}, exists $firstFight{$w} ? $war - $firstFight{$w} : -1, $landMarines{$w} ];
      delete $landed{$w}; delete $firstFight{$w}; delete $firstStratum{$w};
    }
  }
  close $h;
  print "== $tag\n  days a siege spent over its world, by ending:\n";
  for my $k ("called off", "beaten", "landed (ready)", "landed (dry)", "front", "guns") {
    next unless $siegeDays{$k};
    printf "    %-15s %s; fight days median %s\n", $k, spread(@{ $siegeDays{$k} }), median(@{ $fightDays{$k} });
  }
  print "  hives eradicated, by size (days from the first siege fight to the landing | landing to eradicated | first fight to eradicated):\n";
  my %by;
  push @{ $by{ $_->[0] } }, $_ for @rows;
  push @{ $pool{ $_->[0] } }, $_->[3] for @rows;
  for my $s (sort { $a <=> $b } keys %by) {
    my @r = @{ $by{$s} };
    printf "    size %d: n %d | to landing %s | on the ground %s | in all %s | marines landed median %s\n", $s, scalar @r,
      median(grep { $_ >= 0 } map { $_->[2] } @r), spread(map { $_->[3] } @r),
      median(grep { $_ >= 0 } map { $_->[4] } @r), median(map { $_->[5] } @r);
  }
  my @all = map { $_->[4] } grep { $_->[4] >= 0 } @rows;
  my @fast = grep { $_->[4] >= 0 && $_->[4] <= 10 } @rows;
  printf "  all sizes: first fight to eradicated %s; within 10 days: %d (%s)\n", spread(@all), scalar @fast,
    join(", ", map { "$_->[1] size $_->[0] in $_->[4] d" } @fast) || "-";
}
if (@ARGV > 1) {
  print "== all runs: days from the landing to eradicated, by size\n";
  printf "    size %d: %s\n", $_, spread(@{ $pool{$_} }) for sort { $a <=> $b } keys %pool;
}
