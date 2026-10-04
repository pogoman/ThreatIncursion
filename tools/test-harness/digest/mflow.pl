# mflow.pl <tag>...: where the human factions' marines went, to month 120 (thousands)
for my $tag (@ARGV) { open my $f, '<', "$ENV{TEMP}/threatinc-tests/ti-$tag.txt" or next; my ($day, %s, %n) = (0);
  while (<$f>) { if (/^Clock: day \S+ war (\d+)/) { $day = $1; last if $day > 3600; next }
    if (/^Expedition draw at .*?: (\d+)\/(\d+) marines/) { $s{drawn} += $1; $n{drawn}++ }
    elsif (/^Expedition return to .*?, (\d+) marines/) { $s{returned} += $1; $n{returned}++ }
    elsif (/^Front deployed at .+? \((\w+)\): (\d+) marines/) { if ($1 ne 'threat') { $s{landed} += $2; $n{landed}++ } }
    elsif (/^Reserve cover: .*? issues (\d+) marines/) { $s{cover} += $1; $n{cover}++ }
    elsif (/^Counter-attack at .*?: (?:\d+ held, )?(\d+) marines lost/) { $s{counter} += $1; $n{counter}++ }
    elsif (/^Landing from cargo at .*?: (\d+) marines/) { $s{cargo} += $1; $n{cargo}++ } }
  printf "%-5s drawn %dk in %d sieges, returned %dk, landed on hives %dk in %d, lost in counter-attacks %dk in %d, shortage cover %dk\n", $tag,
    $s{drawn} / 1000, $n{drawn} || 0, $s{returned} / 1000, $s{landed} / 1000, $n{landed} || 0, $s{counter} / 1000, $n{counter} || 0, $s{cover} / 1000 }
