use strict; use warnings;
# months.pl <tag> [from] [to] : one run by month, from ti-<tag>.txt (cwd, else %TEMP%\threatinc-tests): the swarm's
# census line (hives, hives found, fleets, income, fuel), what each side lost in the off-screen fights over its
# worlds, sieges arrived / called off / beaten / landed, fleets rallied by the system defence, strikes launched
# and recalled. Month N is the Nth Census line; what is logged after it counts toward month N + 1.
my ($tag, $from, $to) = @ARGV;
die "months.pl <tag> [from] [to]\n" unless $tag;
$from ||= 1; $to ||= 999;
my $f = -f "ti-$tag.txt" ? "ti-$tag.txt" : "$ENV{TEMP}/threatinc-tests/ti-$tag.txt";
open(my $h, "<", $f) or die "$f: $!";
my $m = 0;
my %r;
my @keys = qw(siegeLost humanLost otherLost arrived arrivedFP off landed beaten rallied ralliedFP strikes recalled recallFP);
while (my $l = <$h>) {
  $l =~ s/\r?\n$//;
  if ($l =~ /^Census: threat (.*)/) {
    $m++;
    $r{$m}{census} = $1;
    next;
  }
  my $k = $m + 1;
  if ($l =~ /^Off-screen fight over .+? \(daily siege\): .*?attacker lost (\d+) FP.*?: (\d+) Threat FP/) {
    $r{$k}{humanLost} += $1; $r{$k}{siegeLost} += $2; next;
  }
  if ($l =~ /^Off-screen fight .*?(\d+) Threat FP/) { $r{$k}{otherLost} += $1; next; }
  if ($l =~ /^Daily siege takes over .*?, (\d+) FP/) { $r{$k}{arrived}++; $r{$k}{arrivedFP} += $1; next; }
  if ($l =~ /^Daily siege of .+?: \d+ d, .*fight days, (called off|beaten|landed)/) {
    $r{$k}{ $1 eq "called off" ? "off" : $1 }++; next;
  }
  if ($l =~ /^Posture: .*? rallied (\d+) FP to /) { $r{$k}{rallied}++; $r{$k}{ralliedFP} += $1; next; }
  if ($l =~ /^Posture: strike recalled to .*? - (\d+) FP/) { $r{$k}{recalled}++; $r{$k}{recallFP} += $1; next; }
  if ($l =~ /^Strike launched/) { $r{$k}{strikes}++; next; }
}
close $h;
print "month, census | siege fights: Threat lost / humans lost; other off-screen Threat lost; sieges arrived (FP), called off, beaten, landed; rallied (FP); strikes, recalled (FP)\n";
for my $i (sort { $a <=> $b } keys %r) {
  next if $i < $from || $i > $to;
  my %v = map { $_ => ($r{$i}{$_} || 0) } @keys;
  printf "m%-3d %s\n      siege fights %d / %d; other %d; sieges %d (%d FP) off %d beaten %d landed %d; rallied %d (%d FP); strikes %d, recalled %d (%d FP)\n",
    $i, substr($r{$i}{census} || "", 0, 150), $v{siegeLost}, $v{humanLost}, $v{otherLost}, $v{arrived}, $v{arrivedFP},
    $v{off}, $v{beaten}, $v{landed}, $v{rallied}, $v{ralliedFP}, $v{strikes}, $v{recalled}, $v{recallFP};
}
