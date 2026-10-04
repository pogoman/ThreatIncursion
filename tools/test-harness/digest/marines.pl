# marines.pl <tag>...: the human factions' reserve marines (Census lines, summed) and Threat landings / overruns, by 24 months
for my $tag (@ARGV) { open my $f, '<', "$ENV{TEMP}/threatinc-tests/ti-$tag.txt" or next; my ($day, %m, %land, %over, %hl) = (0); my %cur;
  while (<$f>) { if (/^Clock: day \S+ war (\d+)/) { $day = $1; next } my $b = int($day / 720) * 24;
    if (/^Census: (\w+) colonies .*? reserve marines (\d+)/) { $cur{$1} = $2; my $s = 0; $s += $_ for values %cur; $m{$b} = $s; next }
    $land{$b}++ if /^Notice: Threat Landing/; $over{$b}++ if /^Notice: Beachhead Overrun/;
    if (/^Front deployed at .+? \((\w+)\): (\d+) marines/ && $1 ne 'threat') { $hl{$b} += $2 } }
  printf "%-5s", $tag; for my $b (sort { $a <=> $b } keys %m) { next if $b > 120; printf " | m%d: %dk marines, %d landings, %d overrun, %dk landed on hives", $b, $m{$b} / 1000, $land{$b} || 0, $over{$b} || 0, ($hl{$b} || 0) / 1000 } print "\n" }
