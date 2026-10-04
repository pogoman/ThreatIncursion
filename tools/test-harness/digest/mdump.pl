# mdump.pl <tag>...: human worlds' marines stock and accrual a month, from the monthly dumps (every 24th month)
for my $tag (@ARGV) { my @f = sort { ($b =~ /d-(\d+)/)[0] <=> ($a =~ /d-(\d+)/)[0] } glob("$tag/threatinc_simdump_d-*.json.data"); printf "%-5s", $tag;
  for my $i (48, 72, 96, 120) { next if $i >= @f; local $/; open my $h, '<', $f[$i] or next; my $t = <$h>; my ($acc, $n, $nz) = (0, 0, 0);
    while ($t =~ /"accrualPer30": \{\s*"fuel": [\d.]+,\s*"hand_weapons": [\d.]+,\s*"marines": ([\d.]+),\s*"supplies": [\d.]+\s*\},.*?"faction": "([a-z_]+)"/sg) { next if $2 eq 'threat'; $acc += $1; $n++; $nz++ if $1 > 0 }
    printf " | m%d: %d worlds (%d accruing) +%.1fk marines a month", $i, $n, $nz, $acc / 1000 } print "\n" }
