#!/usr/bin/perl
# supplies.pl <tag>... : where the human factions' supplies and fuel go, to month 120
use strict; use warnings;
sub n { my $x = shift; $x =~ s/,//g; return $x; }
sub k { return sprintf('%dk', $_[0] / 1000); }
for my $tag (@ARGV) {
  open(my $f, '<', "ti-$tag.txt") or die;
  my $day = 0; my (%s, %fu, %c);
  my ($incS, $incF, $stockS, $stockF, $months) = (0, 0, 0, 0, 0);
  my ($owed, $paid) = (0, 0);
  while (<$f>) {
    $day = $1 if /Clock: day -?\d+ war (\d+)/;
    last if $day > 3600;
    if (/^Council \w+: picture .*?means (\d+) fuel (\d+) supplies \d+ marines \(\+(\d+)\/\+(\d+) a month\)/) {
      $stockF += $1; $stockS += $2; $incF += $3; $incS += $4; $months++;
    }
    if (/Fleet upkeep: (\w+) \d+ fleets out, paid (\d+) of (\d+) supplies/) { $s{'fleet upkeep (fleets out)'} += $2; $owed += $3; $paid += $2; }
    if (/Order draw at .*?: ([\d,]+) fuel, ([\d,]+) supplies/) { $fu{'orders (guard, support, raids)'} += n($1); $s{'orders (guard, support, raids)'} += n($2); }
    if (/Hunting fleet from .*? pooled ([\d,]+) fuel, ([\d,]+) supplies/) { $fu{'hunting fleets'} += n($1); $s{'hunting fleets'} += n($2); }
    if (/Expedition draw \(task force\) at .*?: ([\d,]+) fuel, ([\d,]+) supplies/) { $fu{'hunting task forces'} += n($1); $s{'hunting task forces'} += n($2); }
    if (/Expedition draw at .*?: .*? (\d+)\/\d+ fuel \(passage \d+, ordnance \d+, razing (\d+)\), (\d+)\/\d+ supplies/) {
      my $key = $2 > 0 ? 'saturation expeditions' : 'landing sieges'; $fu{$key} += $1; $s{$key} += $3; $c{$key}++;
    }
    if (/Frontline: paid (\d+) supplies and (\d+) fuel/) { $s{'forward base structures'} += $1; $fu{'forward base structures'} += $2; }
    if (/Return settled at .*?: \d+ marines, \d+ armaments, (\d+) fuel, (\d+) supplies/) { $fu{'(returned)'} -= $1; $s{'(returned)'} -= $2; }
    if (/Expedition return to .*?: .*?(\d+) fuel.*?(\d+) supplies/) { $fu{'(returned)'} -= $1; $s{'(returned)'} -= $2; }
    $c{'orders stood down out of supplies'}++ if /Order stood down \(out of supplies/;
    if (/Reserve cover: .*? issues (\d+) (fuel|supplies) /) { if ($2 eq 'fuel') { $fu{'shortage cover'} += $1 } else { $s{'shortage cover'} += $1 } }
  }
  print "== $tag to month 120 (council factions)\n";
  printf "  income over the run: %s supplies, %s fuel (sum of the monthly accruals in the council pictures, %d faction-months)\n", k($incS), k($incF), $months;
  printf "  mean stock in hand per faction: %s supplies, %s fuel\n", k($stockS / $months), k($stockF / $months) if $months;
  my %all = (%s, %fu);
  for my $key (sort { ($s{$b} || 0) <=> ($s{$a} || 0) } keys %all) {
    printf "  %-32s %8s supplies  %8s fuel%s\n", $key, k($s{$key} || 0), k($fu{$key} || 0), $c{$key} ? "  ($c{$key} sailed)" : '';
  }
  printf "  fleet upkeep owed %s, paid %s; orders stood down out of supplies: %d\n", k($owed), k($paid), $c{'orders stood down out of supplies'} || 0;
}
