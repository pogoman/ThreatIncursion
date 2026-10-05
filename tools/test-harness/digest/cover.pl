use strict; use warnings;
# cover.pl <tags> : the orbit cover an unspawned landing leaves over its army on a hive world (GroundFront.coverFP),
# from ti-<tag>.txt (cwd, else %TEMP%\threatinc-tests): how many landings left one and how heavy, how many the swarm
# outweighed and how long after the landing, the swarms rallied or sent to the world while the cover stood, and how
# each landing ended (hive eradicated, army overrun, still up at the end of the log). Days are war days.
sub med { my @s = sort { $a <=> $b } @_; return @s ? $s[int($#s / 2)] : "-"; }
for my $tag (@ARGV) {
  my $f = -f "ti-$tag.txt" ? "ti-$tag.txt" : "$ENV{TEMP}/threatinc-tests/ti-$tag.txt";
  open(my $h, "<", $f) or next;
  my $war = 0;
  my (%c, @done);
  my $close = sub { my ($w, $how) = @_; my $e = delete $c{$w} or return; $e->{how} = $how; $e->{end} = $war; push @done, $e; };
  while (my $l = <$h>) {
    if ($l =~ /^Clock: day -?\d+ war (\d+)/) { $war = $1; next; }
    if ($l =~ /^Orbit cover over (.+?): the (\w+) flotilla holds it with (\d+) FP/) {
      $c{$1} ||= { world => $1, start => $war, fp => 0, rallied => 0, ralliedFP => 0, sent => 0, sentFP => 0 };
      $c{$1}{fp} = $3 if $3 > $c{$1}{fp};
      next;
    }
    if ($l =~ /^Orbit cover over (.+?) lost: (\d+) FP of swarms/) {
      if ($c{$1} && !exists $c{$1}{lost}) { $c{$1}{lost} = $war; $c{$1}{swarm} = $2; }
      next;
    }
    if ($l =~ /^Posture: .+? rallied (\d+) FP to (.+?) \(/) {
      if ($c{$2} && !exists $c{$2}{lost}) { $c{$2}{rallied}++; $c{$2}{ralliedFP} += $1; }
      next;
    }
    if ($l =~ /^Posture: .+? sent (\d+) FP to (.+?) \(/) {
      if ($c{$2} && !exists $c{$2}{lost}) { $c{$2}{sent}++; $c{$2}{sentFP} += $1; }
      next;
    }
    if ($l =~ /^Colony eradicated: (.+?)\s*$/) { $close->($1, "eradicated"); next; }
    if ($l =~ /^Notice: Beachhead Overrun \| A hive counter-attack on (.+?) has overrun/) { $close->($1, "overrun"); next; }
  }
  close $h;
  $close->($_, "up") for keys %c;
  my @lost = grep { exists $_->{lost} } @done;
  my %how; $how{ $_->{how} }++ for @done;
  my %howLost; $howLost{ $_->{how} }++ for @lost;
  my ($r, $rfp, $s, $sfp) = (0, 0, 0, 0);
  for (@done) { $r += $_->{rallied}; $rfp += $_->{ralliedFP}; $s += $_->{sent}; $sfp += $_->{sentFP}; }
  printf "== %s: landings left under cover %d (median %s FP)\n", $tag, scalar @done, med(map { $_->{fp} } @done);
  printf "  cover outweighed by the swarm %d (a median %s days after the landing, by %s FP of swarms)\n",
    scalar @lost, med(map { $_->{lost} - $_->{start} } @lost), med(map { $_->{swarm} } @lost);
  printf "  to the world while its cover stood: rallied %d fleets (%d FP), other transfers %d (%d FP)\n", $r, $rfp, $s, $sfp;
  printf "  ended: hive eradicated %d, army overrun %d, still up %d; of the %d whose cover was lost: eradicated %d, overrun %d, up %d\n",
    $how{eradicated} || 0, $how{overrun} || 0, $how{up} || 0, scalar @lost,
    $howLost{eradicated} || 0, $howLost{overrun} || 0, $howLost{up} || 0;
  printf "  days under cover to the end, median: eradicated %s, overrun %s\n",
    med(map { $_->{end} - $_->{start} } grep { $_->{how} eq "eradicated" } @done),
    med(map { $_->{end} - $_->{start} } grep { $_->{how} eq "overrun" } @done);
}
