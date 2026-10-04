# hives.pl <tag> <day> [system]: the hives of one monthly dump (the running game's, else tools/warsim/validation):
# what each holds, in how many fleets, its want and need, its bank and organs. <day> as in the file name (-642871).
use strict; use warnings;
my ($tag, $day, $sys) = @ARGV;
my $f = "C:/Program Files (x86)/Fractal Softworks/Starsector/saves/_sbs/$tag/saves/common/threatinc_simdump_d$day.json.data";
$f = "C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion/tools/warsim/validation/$tag/threatinc_simdump_d$day.json.data" unless -f $f;
open my $h, "<", $f or die "no dump $f";
local $/; my $t = <$h>; close $h;
my ($tot, $n) = (0, 0);
while ($t =~ /\{([^{}]*"garrisonFP"[^{}]*)\}/g) {
  my $o = $1; my %v;
  $v{$1} = $2 while $o =~ /"(\w+)"\s*:\s*"?([^",\n]*)"?,?/g;
  next if defined $sys && index(lc $v{system}, lc $sys) < 0;
  printf "  %-22s %-16s size %s held %5d in %2d fleets, want %5d, need %5d, bank %5d, core %s nexus %s\n", $v{name}, $v{system}, $v{size},
    $v{garrisonFP}, $v{garrisonFleets}, $v{wantFP}, $v{needFP}, $v{bank}, $v{core}, $v{nexus};
  $tot += $v{garrisonFP}; $n++;
}
print "  $n hives, $tot FP held\n";
