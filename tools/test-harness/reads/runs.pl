#!/usr/bin/perl
# runs.pl <dump.json> <tag>... - per war month (from the war's opening, day 2231) figures of each run's log,
# written as JSON to stdout: threat census, each human faction's census + hulls, and the month's events.
use strict; use warnings; use JSON::PP;
my $OPEN = 2231;
my $dump = shift;
my %owner;
{ local $/; open my $F, '<', $dump or die; my $j = decode_json(<$F>);
  $owner{$_->{name}} = $_->{faction} for @{$j->{worlds}}; }
my %hullName = ('Hegemony'=>'hegemony','Persean League'=>'persean','Independent'=>'independent',
  'Tri-Tachyon'=>'tritachyon','Luddic Church'=>'luddic_church','Luddic Path'=>'luddic_path',
  'Sindrian Diktat'=>'sindrian_diktat','Pirates'=>'pirates','Your faction'=>'player');
my $D = "/tmp/threatinc-tests";
my %out;
for my $tag (@ARGV) {
  my $f = "$D/ti-$tag-keep.txt"; $f = "$D/ti-$tag.txt" unless -f $f;
  open my $L, '<', $f or die "$f";
  # the war opens with the first strike launched (ck2 day 2231, ck3 day 2133); months count from it
  my $open = $OPEN; { my $cw = 0; open my $P, "<", $f or die; while (<$P>) { if (/^Clock: day -?\d+ war (\d+)/) { $cw = $1 } elsif (/^Strike launched/) { $open = $cw; last } } close $P; }
  my ($w, $m) = (0, 0); my %mo; my $last = 0;
  my $ev = sub { my ($k, $v) = @_; push @{$mo{$m}{ev}{$k}}, $v; };
  my $cnt = sub { $mo{$m}{n}{$_[0]}++; };
  while (<$L>) {
    s/\r?\n$//;
    if (/^Clock: day -?\d+ war (\d+)/) { $w = $1; $m = int(($w - $open) / 30); $m = -1 if $w < $open; $last = $w; next; }
    if (/^Census: threat hives (\d+) \(size (\d+)\), found (\d+), fleets (\d+) FP, income (\d+) FP\/mo, upkeep (\d+) FP\/mo, banked (\d+) FP; fuel (\d+) \(\+(\d+)\/mo, spent (\d+)\); supplies (\d+) \(\+(\d+)\/mo, spent (\d+)\)/) {
      $mo{$m}{t} = {hives=>$1,size=>$2,found=>$3,fleets=>$4,income=>$5,upkeep=>$6,banked=>$7,fuel=>$8,fuelMade=>$9,fuelSpent=>$10,sup=>$11,supMade=>$12,supSpent=>$13};
      next; }
    if (/^Census: (\w+) colonies (\d+) \(size (\d+)\), links (\d+) .*?bases (\d+), reserve marines (\d+), arms (\d+), fuel (\d+), supplies (\d+), stance (.*)$/) {
      $mo{$m}{h}{$1}{c} = {col=>$2,size=>$3,links=>$4,bases=>$5,marines=>$6,fuel=>$8,sup=>$9,stance=>$10}; next; }
    if (/^Hulls: (.+?) hulls (\d+) FP \((\d+) built\): (\d+) out, (\d+) lost, (\d+) free; yards (\d+) FP\/mo/) {
      my $fac = $hullName{$1} // $1; next if $fac eq 'player';
      $mo{$m}{h}{$fac}{hu} = {hulls=>$2,built=>$3,out=>$4,lost=>$5,free=>$6,yards=>$7}; next; }
    if (/^Hulls: (.+?) yards idle/) { $cnt->('yardsIdle:' . ($hullName{$1} // $1)); next; }
    if (/^Notice: Colony Lost \| (.+?) has fallen to the Threat/) { $ev->('worldLost', "$1 (" . ($owner{$1} // '?') . ")"); next; }
    if (/^Colony eradicated: (.+)$/) { $ev->('hiveKilled', $1); next; }
    if (/^Notice: Hive Seeded \| .* ruins of (.+)$/) { $ev->('hiveSeeded', $1); next; }
    if (/^Notice: Threat Landing \| The Threat has landed on (.+?) \|/) { $cnt->('landing'); next; }
    if (/^Notice: Beachhead Overrun/) { $cnt->('overrun'); next; }
    if (/^Notice: Expedition Landed \| (.+?)'s expedition/) { $cnt->('expedition'); next; }
    if (/^Frontline: (\w+) founded /) { $cnt->("baseFounded:$1"); next; }
    if (/^Frontline: (\w+) dismantled .*destroyed by a Threat strike/) { $cnt->("baseLost:$1"); next; }
    if (/^Frontline: (\w+) cannot pay for a link/) { $cnt->("cannotPay:$1"); next; }
    if (/^Frontline: (\w+) founds no link/) { $cnt->("noHulls:$1"); next; }
    if (/^Order lost \(fleet destroyed\): (\w+) relieving/) { $cnt->("reliefLost:$1"); next; }
    if (/^Relief held: (\w+)/) { $cnt->("reliefHeld:$1"); next; }
    if (/^Relief: (\w+) sends (\d+) FP/) { $cnt->("reliefSent:$1"); $mo{$m}{n}{"reliefFP:$1"} += $2; next; }
    if (/^Council (\w+): strategy \w+ -> STARVE/) { $cnt->("starve:$1"); next; }
    if (/^Hive stock: a Seeding Swarm .* held/) { $cnt->('seedHeld'); next; }
    if (/^Hive stock plan: (\w+) .* runs dry/) { $cnt->("runsDry:$1"); next; }
    if (/^Upkeep month: .*navy upkeep (\d+) of (\d+) supplies/ && $1 < $2) { $cnt->('navyShort'); next; }
    if (/^Offensive launched/) { $cnt->('campaign'); next; }
    if (/^Strike launched from .*?, (\d+) FP/) { $cnt->('strike'); $mo{$m}{n}{strikeFP} += $1; next; }
    if (/^Stance: the chest is full/) { $cnt->('chestFull'); next; }
    if (/hulls lost to rebuild/) { $cnt->('hullConvoy'); next; }
  }
  close $L;
  $out{$tag} = {lastWarDay => $last, months => \%mo};
  print STDERR "$tag done (war $last)\n";
}
print JSON::PP->new->canonical->encode(\%out);
