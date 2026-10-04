# council.pl <tag>: what the human councils read and chose, month by month (game log)
my $tag = shift; open my $f, '<', "$ENV{TEMP}/threatinc-tests/ti-$tag.txt" or die; my ($day, %st, %lab, %ls, @ratio, %noplay, %n, %byF, %means) = (0);
while (<$f>) { if (/^Clock: day \S+ war (\d+)/) { $day = $1; next } next if $day > 3600;
  if (/^Council (\w+): picture (\d+) hives in (\d+) systems \((\d+) cores, (\d+) frontier, (\d+) stale, (\d+) in reach, (\d+) threatening\), means (\d+) fuel (\d+) supplies (\d+) marines .*?weight (\d+)(?: \+ allies (\d+) \([^)]*\))? vs (\d+): (\w+) ([\d.]+|inf)?.*?strategy (\w+)(.*)$/) {
    my ($fa, $hives, $own, $ally, $vs, $label, $r, $strat, $rest, $mf, $ms, $mm) = ($1, $2, $12, $13 || 0, $14, $15, $16, $17, $18, $9, $10, $11);
    next unless $hives > 0; $n{months}++;
    $ls{"$label -> $strat"}++; $byF{$fa}{$strat}++; push @ratio, $r if $label eq 'outmatched';
    $n{pressed}++ if /; pressed \(/; $noplay{$strat}++ if $rest =~ /plays none/; $n{allied}++ if $ally;
    $means{fuel} += $mf; $means{sup} += $ms; $means{mar} += $mm }
  elsif (/^Council \w+: (no \w+ on) .*? can start \(([^)]*)\)/) { $n{"$1 X can start ($2)"}++ }
  elsif (/^Council \w+: strategy (\w+) -> (\w+)/) { $n{"switch $1->$2"}++ } }
printf "=== %s: %d faction-months with a hive known (to month 120); pressed in %d%%, with an ally in %d%%\n", $tag, $n{months}, 100 * $n{pressed} / $n{months}, 100 * $n{allied} / $n{months};
printf "  %5.1f%%  %s%s\n", 100 * $ls{$_} / $n{months}, $_, '' for sort { $ls{$b} <=> $ls{$a} } keys %ls;
my @s = sort { $a <=> $b } @ratio; printf "outmatched ratio: p10 %.2f, median %.2f, p90 %.2f\n", $s[@s * .1], $s[@s / 2], $s[@s * .9] if @s;
printf "months with no play running, by strategy: %s\n", join(', ', map { "$_ $noplay{$_}" } sort keys %noplay);
printf "mean means a faction-month: %dk fuel, %dk supplies, %dk marines\n", map { $means{$_} / $n{months} / 1000 } qw(fuel sup mar);
print "by faction: ", join(' | ', map { my $fa = $_; "$fa " . join(' ', map { "$_ $byF{$fa}{$_}" } sort keys %{ $byF{$fa} }) } sort keys %byF), "\n";
print "  $_: $n{$_}\n" for grep { /^no |^switch/ } sort keys %n;
