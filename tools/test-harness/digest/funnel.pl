# funnel.pl <tag>...: the human offence, from the council's choice to a dead hive (game log, to month 120)
sub med { my @s = sort { $a <=> $b } @_; @s ? $s[@s / 2] : 0 }
for my $tag (@ARGV) { open my $f, '<', "$ENV{TEMP}/threatinc-tests/ti-$tag.txt" or next; my ($day, %n, @raze, @trip, @offR, @defR, @fuelMeans, %ls) = (0);
  while (<$f>) { if (/^Clock: day \S+ war (\d+)/) { $day = $1; next } next if $day > 3600;
    if (/^Council \w+: picture (\d+) hives .*?means (\d+) fuel (\d+) supplies (\d+) marines .*? vs \d+: (\w+) .*?strategy (\w+)(.*)/) { next unless $1 > 0; my ($fu, $lab, $st, $rest) = ($2, $5, $6, $7); $n{months}++; $ls{$st}++; $n{out}++ if $lab eq 'outmatched'; $n{idle}++ if $rest =~ /plays none/; push @fuelMeans, $fu; next }
    if (/^Expedition postponed at .*?: (\d+)\/(\d+) fuel past (\d+) for razing, (\d+)\/(\d+) supplies/) { $n{postponed}++; push @raze, $3; push @trip, $2;
      my ($fs, $ss) = ($1 < $2, $4 < $5); $n{$fs && $ss ? 'pp.both' : $fs ? 'pp.fuel' : $ss ? 'pp.supplies' : 'pp.neither'}++; $n{'pp.nothingPastRazing'}++ if $1 == 0; next }
    if (/^Expedition postponed at .*?: (\d+)\/(\d+) fuel, (\d+)\/(\d+) supplies/) { $n{postponedNoRaze}++; next }
    if (/^Notice: Siege Called Off \|.*\| Defense Swarms ([\d,]+) FP against its ([\d,]+) FP/) { my ($d, $o) = ($1, $2); s/,//g for $d, $o; $n{calledOff}++; push @defR, $d / ($o || 1); next }
    if (/^Play \w+#\d+ (\w+)(?: \w+ at .*?)?: (.*)/) { my ($ty, $m) = ($1, $2);
      $n{"$ty started"}++ if $m =~ /^start -> /;
      if ($ty eq 'STARVE') { $n{'starve.noSaturationPaid'}++ if $m =~ /^no saturation expedition the pools pay/; $n{'starve.noSquadronGuarded'}++ if $m =~ /^no squadron \(every Nexus/; $n{'starve.noSquadronPay'}++ if $m =~ /^no squadron \(no base pays/;
        $n{'starve.saturationSails'}++ if $m =~ /^saturation expedition of/; $n{'starve.squadron'}++ if $m =~ /^squadron of/; $n{'starve.drivenOff'}++ if $m =~ /driven off$/ }
      if ($ty eq 'HAMMER') { $n{'hammer.strike'}++ if $m =~ /^\w+ -> strike/; $n{'hammer.sieges'}++ if $m =~ /^siege of [\d.]+ FP sails/ } next }
    if (/^Play \w+#\d+: (\w+) \(([^;)]*)/) { $n{"end: $2"}++ if $2 =~ /starved, invasion next|no fresh report|picture is fresh|ran its course/; next }
    if (/^Council \w+: no (\w+) on .*? can start/) { $n{"council.no$1"}++; next }
    $n{landed}++ if /^Notice: Expedition Landed/; $n{doomed}++ if /^Siege pass at .*: no landing - /; $n{eradicated}++ if /^Notice: Hive Eradicated/;
    $n{sailed}++ if /^Expedition draw at /; $n{hunts}++ if /^Notice: Hunting Force Musters/ }
  my $m = $n{months} || 1;
  printf "=== %s\n", $tag;
  printf "council: %d faction-months; outmatched %d%%; strategy %s; no play running %d%%\n", $m, 100 * $n{out} / $m, join(' ', map { sprintf "%s %d%%", $_, 100 * $ls{$_} / $m } sort { $ls{$b} <=> $ls{$a} } keys %ls), 100 * $n{idle} / $m;
  printf "plays started: %s | council could not start: HAMMER %d, STARVE %d\n", join(', ', map { /^(\w+) started/; "$1 $n{$_}" } sort grep { / started$/ } keys %n), $n{'council.noHAMMER'} || 0, $n{'council.noSTARVE'} || 0;
  printf "STARVE months: saturation not paid %d, sailed %d | squadron sent %d, none (every Nexus guarded) %d, none (no base pays) %d | raids driven off %d\n", map { $n{$_} || 0 } qw(starve.noSaturationPaid starve.saturationSails starve.squadron starve.noSquadronGuarded starve.noSquadronPay starve.drivenOff);
  printf "postponed %d: fuel short %d, supplies short %d, both %d; nothing left past the razing set-aside in %d; median set-aside %dk against a %dk trip; median faction fuel %dk\n", $n{postponed} || 0, $n{'pp.fuel'} || 0, $n{'pp.supplies'} || 0, $n{'pp.both'} || 0, $n{'pp.nothingPastRazing'} || 0, med(@raze) / 1000, med(@trip) / 1000, med(@fuelMeans) / 1000;
  printf "expeditions sailed %d (hammers that struck %d, their sieges %d), called off in orbit %d (median defenders %.2fx its FP), landed %d, landings refused as doomed %d, hives eradicated %d, hunting forces %d\n", $n{sailed} || 0, $n{'hammer.strike'} || 0, $n{'hammer.sieges'} || 0, $n{calledOff} || 0, med(@defR), $n{landed} || 0, $n{doomed} || 0, $n{eradicated} || 0, $n{hunts} || 0;
  printf "play ends: %s\n", join(', ', map { /^end: (.*)/; "$1 $n{$_}" } sort grep { /^end: / } keys %n) }
