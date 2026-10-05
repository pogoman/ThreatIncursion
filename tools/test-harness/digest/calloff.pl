use strict; use warnings;
# calloff.pl <tags> : how the unspawned sieges over hive worlds ended, from ti-<tag>.txt (cwd, else
# %TEMP%\threatinc-tests): by ending, the FP that came down and the FP left; the fight days and what each side
# lost in them; and what one day of exchange would have cost each side had every called-off siege paid it on the
# way out (the off-screen rule: attacker loses min(0.75, 0.5 x enemy / mine), defender min(0.75, 0.5 x mine / enemy)).
for my $tag (@ARGV) {
  my $f = -f "ti-$tag.txt" ? "ti-$tag.txt" : "$ENV{TEMP}/threatinc-tests/ti-$tag.txt";
  open(my $h, "<", $f) or next;
  my (%n, %start, %end, %days0);
  my ($offN, $offEnemy, $offOurs, $pursuitHuman, $pursuitThreat) = (0, 0, 0, 0, 0);
  my ($fightT, $fightH, $fightDays, $fightOut) = (0, 0, 0, 0);
  while (my $l = <$h>) {
    if ($l =~ /^Daily siege of .+? called off: (\d+) FP against (\d+)/) {
      my ($e, $o) = ($1, $2);
      $offN++; $offEnemy += $e; $offOurs += $o;
      my $a = 0.5 * $e / ($o || 1); $a = 0.75 if $a > 0.75;
      my $d = 0.5 * $o / ($e || 1); $d = 0.75 if $d > 0.75;
      $pursuitHuman += $a * $o; $pursuitThreat += $d * $e;
      next;
    }
    if ($l =~ /^Daily siege of .+?: (\d+) d, (\d+) -> (\d+) FP, (\d+) fight days, (called off|beaten|landed|front|guns)/) {
      my $k = $5;
      $n{$k}++; $start{$k} += $2; $end{$k} += $3; $days0{$k}++ if $4 == 0;
      next;
    }
    if ($l =~ /^Off-screen fight over .+? \(daily siege\): \S+ (\d+) FP(?: with \d+ hunting)? vs (\d+) FP; attacker lost (\d+) FP.*?: (\d+) Threat FP/) {
      $fightDays++; $fightH += $3; $fightT += $4; $fightOut++ if $2 >= $1;
    }
  }
  close $h;
  print "== $tag\n";
  for my $k ("called off", "beaten", "landed", "front", "guns") {
    next unless $n{$k};
    printf "  %-10s %3d sieges, %7d FP down, %7d FP left (%.0f%%), %d with no fight day\n",
      $k, $n{$k}, $start{$k}, $end{$k}, 100 * $end{$k} / ($start{$k} || 1), $days0{$k} || 0;
  }
  printf "  fight days %d: Threat lost %d FP, humans %d FP; days the defence was the heavier side %d\n",
    $fightDays, $fightT, $fightH, $fightOut;
  printf "  call-off lines %d: %d FP of swarms against %d FP of siege (%.2f to 1)\n",
    $offN, $offEnemy, $offOurs, $offEnemy / ($offOurs || 1);
  printf "  one day of exchange on the way out: humans lose %d FP, Threat %d FP -> over the run %.2f Threat FP a human FP\n",
    $pursuitHuman, $pursuitThreat, ($fightT + $pursuitThreat) / (($fightH + $pursuitHuman) || 1);
}
