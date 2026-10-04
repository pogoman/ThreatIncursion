#!/usr/bin/perl
# swarm.pl <tags> : the swarm's side of a run, from ti-<tag>.txt (cwd, else %TEMP%\threatinc-tests):
# how long it lives, what it holds, whether it defends the world a siege comes down on, whether it keeps
# growing under attack, and what its strikes leave over their landings (docs/game-runs.md 4).
use strict; use warnings;

sub median { my @v = sort { $a <=> $b } @_; return @v ? $v[int($#v / 2)] : 0; }
sub k { my $v = shift; return $v >= 1000 ? sprintf("%.1fk", $v / 1000) : int($v); }

for my $tag (@ARGV) {
  my $f = -f "ti-$tag.txt" ? "ti-$tag.txt" : "$ENV{TEMP}/threatinc-tests/ti-$tag.txt";
  open my $h, "<", $f or do { print "== $tag: no log ($f)\n"; next; };
  my $month = 0;
  my ($found, $extinct, $peak, $peakMonth, $peakFP) = (0, 0, 0, 0, 0);
  my (%hivesAt, %fleetsAt, %stance);
  my ($written, $sent, $sentFP, $sentAfter, $sentAfterFP, $fabFor) = (0, 0, 0, 0, 0, 0);
  my ($seedBefore, $seedAfter, $strikes, $strikeFP, $strikesAfter, $strikeAfterFP) = (0, 0, 0, 0, 0, 0);
  my (%guardFP, %guardFleets, @guardOrder);
  my ($landings, $overrun, $destroyed, $lost, $humanLand, $humanOverrun, $erad) = (0, 0, 0, 0, 0, 0, 0);
  my (%firstFight, @att, @def);
  my ($fights, $held, $humanLostFP, $threatLostFP) = (0, 0, 0, 0);
  my ($atFound, $incFound, $incAfter, $built, $builtFP) = (0, 0, 0, 0, 0);
  my ($arrived, $arrivedFP, $escorts, $escortFP) = (0, 0, 0, 0);
  my (%seen, $recalls, $recallFP);
  ($recalls, $recallFP) = (0, 0);
  my ($war, %launchDay, @recallDays) = (0);
  my ($massed, $massedFP, $massedFar, $massedFarFP, %heldHome) = (0, 0, 0, 0);
  while (my $l = <$h>) {
    if ($l =~ /^Clock: day -?\d+ war (\d+)/) { $war = $1; next; }
    if ($l =~ /^Census: threat hives (\d+).*?fleets (\d+) FP, income (\d+) FP/) {
      $month++;
      if ($found && !$atFound) { $atFound = $2; $incFound = $3; }
      $incAfter += $3 if $found;
      $hivesAt{$month} = $1; $fleetsAt{$month} = $2;
      if ($1 > $peak) { $peak = $1; $peakMonth = $month; }
      $peakFP = $2 if $2 > $peakFP;
      $extinct = $month if !$extinct && $found && $1 == 0;
      next;
    }
    if ($l =~ /^Hive found in /) { $found ||= $month; next; }
    if ($l =~ /^Posture sector: .*stance (\w+)/) { $stance{$1}++ if $found && !$extinct; next; }
    if ($l =~ /^Posture: .* written off/) { $written++; next; }
    if ($l =~ /^Posture: .* sent (\d+) FP to /) {
      $sent++; $sentFP += $1;
      if ($found) { $sentAfter++; $sentAfterFP += $1; }
      next;
    }
    if ($l =~ /^Posture: .* fabricated a (\d+) FP swarm for /) {
      $fabFor++;
      if ($found) { $built++; $builtFP += $1; }
      next;
    }
    if ($l =~ /^Garrison fleet fabricated at .*?, (\d+) FP,/) { if ($found) { $built++; $builtFP += $1; } next; }
    # the humans' side: sieges that reached a hive system, and the escorts built for them
    if ($l =~ /^Daily siege takes over .*?, (\d+) FP/) { $arrived++; $arrivedFP += $1; next; }
    if ($l =~ /: escort of (\d+) FP built/) { $escorts++; $escortFP += $1; next; }
    if ($l =~ /^Seeding Swarm from /) { $found ? $seedAfter++ : $seedBefore++; next; }
    if ($l =~ /^Strike launched from .*? at (.*?) \(/) { $launchDay{$1} = $war; }
    if ($l =~ /^Strike launched from .*?(\d+) FP \+ (\d+) drawn/) {
      $strikes++; $strikeFP += $1 + $2;
      if ($found) { $strikesAfter++; $strikeAfterFP += $1 + $2; }
      next;
    }
    if ($l =~ /^Strike guard over (.+?): the unspawned strike leaves (\d+) fleet\(s\), (\d+) FP/) {
      push @guardOrder, $1 unless exists $guardFP{$1};
      $guardFleets{$1} += $2; $guardFP{$1} += $3;
      next;
    }
    # what it saw coming (the hive picket) and the strikes it called home for it
    if ($l =~ /^Swarm intel: sees \S+ siege of \d+ FP bound for .* by (\w+)/) { $seen{$1}++; next; }
    if ($l =~ /^Posture: strike recalled to .*? - (\d+) FP in (\d+) fleet.*? ly from (.*?); the system/) {
      $recalls++; $recallFP += $1;
      push @recallDays, $war - $launchDay{$3} if exists $launchDay{$3};
      next;
    }
    # the defence massed (postureMass): spare swarms sent to a short world, from its system or a neighbour's
    if ($l =~ /^Posture: .*? massed (\d+) FP at .*?(, [\d.]+ ly)? \(/) {
      if ($2) { $massedFar++; $massedFarFP += $1; } else { $massed++; $massedFP += $1; }
      next;
    }
    # a strike that stayed home (logQuiet: one line a colony each time the reason changes, or a month)
    if ($l =~ /^Strike from (.*?) held: /) { $heldHome{$1}++; next; }
    if ($l =~ /^Front deployed at .* \(threat\)/) { $landings++; next; }
    if ($l =~ /^Notice: Beachhead Overrun \| A hive counter-attack/) { $humanOverrun++; next; }
    if ($l =~ /^Notice: Beachhead Overrun/) { $overrun++; next; }
    if ($l =~ /^Notice: Threat Landing Destroyed/) { $destroyed++; next; }
    if ($l =~ /^Notice: Colony Lost/) { $lost++; next; }
    if ($l =~ /^Notice: Expedition Landed/) { $humanLand++; next; }
    if ($l =~ /^Notice: Hive Eradicated/) { $erad++; next; }
    # the first day of each siege's fight over a hive world: what the swarm had there
    if ($l =~ /^Off-screen fight over (.+?) \(daily siege\): (\S+) (\d+) FP.*? vs (\d+) FP; attacker lost (\d+) FP.*?defenders \d+%: (\d+) Threat FP/) {
      $fights++; $humanLostFP += $5; $threatLostFP += $6;
      my $key = "$1|$2";
      next if $firstFight{$key} && $month - $firstFight{$key} < 3;
      $firstFight{$key} = $month || 1;
      push @att, $3; push @def, $4;
      $held++ if $4 >= $3;
      next;
    }
  }
  close $h;
  my $exc = -s (-f "exc-$tag.txt" ? "exc-$tag.txt" : "$ENV{TEMP}/threatinc-tests/exc-$tag.txt") || 0;
  print "== $tag: $month months\n";
  printf "  found m%s, extinct %s; peak %d hives (m%d), %s FP\n", $found || "-", $extinct ? "m$extinct" : "never",
    $peak, $peakMonth, k($peakFP);
  my @marks = grep { $_ <= $month } (48, 60, 72, 84, 96, 108, 120);
  print "  hives / fleets FP: ", join("  ", map { "m$_ $hivesAt{$_} / " . k($fleetsAt{$_}) } @marks), "\n" if @marks;
  print "  stance months since found: ", join(", ", map { "$_ $stance{$_}" } sort keys %stance), "\n" if %stance;
  printf "  defence: written off %d; transfers %d (%s FP), since found %d (%s FP); swarms built for another colony %d\n",
    $written, $sent, k($sentFP), $sentAfter, k($sentAfterFP), $fabFor;
  printf "  sieges met: %d (fight days %d); first day, median %s FP against %s FP over the world; met at parity or better %d\n",
    scalar(@att), $fights, k(median(@att)), k(median(@def)), $held;
  printf "  lost over hive worlds: Threat %s FP, humans %s FP (%.2f Threat FP a human FP)\n", k($threatLostFP),
    k($humanLostFP), $humanLostFP > 0 ? $threatLostFP / $humanLostFP : 0;
  printf "  means: %s FP of fleets at first contact, income %s FP/mo; since: income %s FP, %d swarms built (%s FP)\n",
    k($atFound), k($incFound), k($incAfter), $built, k($builtFP);
  printf "  against it: %d sieges arrived (%s FP), %d escorts (%s FP)\n", $arrived, k($arrivedFP), $escorts, k($escortFP);
  printf "  growth: seeding swarms %d before found, %d since\n", $seedBefore, $seedAfter;
  printf "  strikes: %d (%s FP), since found %d (%s FP)\n", $strikes, k($strikeFP), $strikesAfter, k($strikeAfterFP);
  printf "  sieges first seen: %s; strikes recalled %d (%s FP), a median %s days after launch\n",
    join(", ", map { "$_ $seen{$_}" } sort keys %seen) || "none", $recalls, k($recallFP),
    @recallDays ? median(@recallDays) : "-";
  my $heldDays = 0;
  $heldDays += $_ for values %heldHome;
  printf "  defence massed: %d fleets (%s FP) within a system, %d (%s FP) from neighbours; strikes held home %d times at %d colonies\n",
    $massed, k($massedFP), $massedFar, k($massedFarFP), $heldDays, scalar(keys %heldHome)
    if $massed || $massedFar || $heldDays;
  my @gfp = map { $guardFP{$_} } @guardOrder; my @gfl = map { $guardFleets{$_} } @guardOrder;
  printf "  strike guards: %d worlds, median %s FP in %d fleet(s) a world, most %s FP\n", scalar(@guardOrder),
    k(median(@gfp)), median(@gfl), k((sort { $b <=> $a } @gfp)[0] || 0);
  printf "  Threat landings %d, overrun %d, destroyed %d; human worlds lost %d\n", $landings, $overrun, $destroyed, $lost;
  printf "  human landings %d, overrun by the hive %d, hives eradicated %d; exceptions file %d bytes\n", $humanLand,
    $humanOverrun, $erad, $exc;
}
