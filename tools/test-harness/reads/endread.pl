#!/usr/bin/perl
# endread.pl <tag> - a run's end figures from its log (/tmp/threatinc-tests/ti-<tag>[-keep].txt) and last dump.
use strict; use warnings; use JSON::PP;
my $tag = shift or die "tag";
my $f = "/tmp/threatinc-tests/ti-$tag-keep.txt"; $f = "/tmp/threatinc-tests/ti-$tag.txt" unless -f $f;
open my $L, '<', $f or die "$f: $!";
my ($m, %hiv, $census, $exc, %c, %brace, %ca, %rep, $spare, $held);
my @fabfp;
my %th;
$m = 0;
while (<$L>) {
  if (/^Hulls: Your faction/) { $m++; next }
  if (/^Census: threat hives (\d+)/) { $hiv{$m} = $1; $hiv{first} = $1 unless defined $hiv{first}; $hiv{last} = $1; $census = $_; $held = $1 if /sends held (\d+)/; next }
  $exc++ if /exception/i;
  $c{strikes}++ if /^Strike launched/;
  $c{landings}++ if /Notice: Threat Landing/;
  $c{victories}++ if /^Threat ground victory/;
  $c{conquests}++ if /^Conquest:/;
  $c{eradicated}++ if /^Colony eradicated/;
  $c{repelled}++ if /^Counter-attack repelled/;
  $c{battered}++ if /^Counter-attack at .* battered/;
  $c{overran}++ if /^Counter-attack at .* overran/;
  $c{breakups}++ if /broke up \d+ FP of hulls/;
  if (/fabricated a (\d+) FP swarm for/) { push @fabfp, $1 }
  $c{sent}++ if /^Posture: .* sent \d+ FP to/;
  $c{musters}++ if /^Garrison musters/;
  $c{norally}++ if /^Posture: no rally/;
  $c{rallied}++ if /^Posture: .* rallied /;
  $c{notransfer}++ if /^Posture: no transfer to/;
  $c{arrived}++ if /^Reinforcement arrived/;
  $c{sends}++ if /^Reinforcement: Defense Swarm/;
  $c{disbanded}++ if /^Reinforcement disbanded/;
  if (/^Reinforcement overdue: (\d+) FP -> (.+?), (\d+) d out, (.*?), (GO_TO_LOCATION|ORBIT_AGGRESSIVE|ORBIT_PASSIVE|no order|[A-Z_]+)/) {
    my ($fp, $to, $w) = ($1, $2, $4);
    $c{overdue}++;
    $c{od_same}++ if $w =~ /units from /;
    $c{od_other}++ if $w =~ /^in .*(units from a jump point|no jump point)/;
    $c{od_hyper}++ if $w =~ /hyperspace/;
    $c{od_battle}++ if /battle true/;
    my $k = "$fp|$to";
    $c{od_again}++ if ++$rep{$k} == 2;
    $c{od_thrice}++ if $rep{$k} == 3;
  }
  if (/^Front deployed at (.*?) \(threat\)/) { $th{$1} = 1 }
  if (/^Front at (.*?) braces for a counter-attack/ && $th{$1}) { $brace{$1}++ }
  if (/^Counter-attack at (.*?) (battered|retook|overran)/ && $th{$1}) { $ca{$1}++ }
  if (/^Reach: spare ([-+]?\d+)/) { $spare = $1 }
  if (/sends held (\d+)/) { $held = $1 }
}
close $L;
my $end = $m;
my @mon = grep { $_ > 0 && $_ <= $end } (20, 40, 60);
my $traj = ($hiv{first} // "?") . " -> " . join(" -> ", map { ($hiv{$_} // "?") . " (m$_)" } @mon) . " -> " . ($hiv{last} // "?") . " (end)";
my ($fleets, $income, $banked, $fuel, $fuelIn, $fuelOut, $sup, $supIn, $supOut) = (0) x 9;
if ($census && $census =~ /fleets (\d+) FP, income (\d+) FP\/mo, upkeep \d+ FP\/mo, banked (\d+) FP; fuel (\d+) \(\+(\d+)\/mo, spent (\d+)\); supplies (\d+) \(\+(\d+)\/mo, spent (\d+)\)/) {
  ($fleets, $income, $banked, $fuel, $fuelIn, $fuelOut, $sup, $supIn, $supOut) = ($1, $2, $3, $4, $5, $6, $7, $8, $9);
}
my ($bw) = sort { $brace{$b} <=> $brace{$a} } keys %brace;
my ($cw) = sort { $ca{$b} <=> $ca{$a} } keys %ca;
my $fabn = @fabfp;
my $fabsum = 0; $fabsum += $_ for @fabfp;
my $fabmean = $fabn ? int($fabsum / $fabn) : 0;
my $under = $fabn ? int(100 * (grep { $_ < 100 } @fabfp) / $fabn) : 0;
# humans at the end: the last dump's worlds (pirates aside, forward bases apart); reinforcements in flight
my $D = "/c/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion/tools/warsim/validation/$tag";
my ($hum, $humN, $fb, $inflight, $inflightFP, $odays) = ("?", "?", 0, "?", "?", "");
if (-d $D) {
  opendir my $DH, $D or die "$D: $!";
  my ($last) = map { "$D/$_" } sort { $a cmp $b } grep { /\.json\.data$/ } readdir $DH;
  closedir $DH;
  if ($last) {
    local $/; open my $J, '<', $last or die "$last: $!"; my $j = decode_json(<$J>); close $J;
    my %fac;
    for my $w (@{$j->{worlds}}) {
      next if $w->{faction} eq 'pirates';
      if ($w->{forwardBase}) { $fb++; next }
      $fac{$w->{faction}}++;
    }
    $humN = 0; $humN += $_ for values %fac;
    $hum = join(", ", map { "$_ $fac{$_}" } sort { $fac{$b} <=> $fac{$a} || $a cmp $b } keys %fac);
    my ($n, $fp, $d90, $fp90, $bat, $stamped) = (0, 0, 0, 0, 0, 0);
    for my $r (@{$j->{fleets}}) {
      next unless $r->{kind} eq 'REINFORCEMENT';
      $n++; $fp += $r->{fp};
      if (defined $r->{days}) { $stamped++; if ($r->{days} >= 90) { $d90++; $fp90 += $r->{fp} } }
      $bat++ if $r->{battle};
    }
    $inflight = $n; $inflightFP = int($fp);
    $odays = ", $d90 of them 90+ days out (" . int($fp90) . " FP), $bat in battle" if $stamped;
  }
}
printf "%s: %d months, exceptions %d\n", $tag, $end, $exc // 0;
printf "  hives %s; human colonies %s (%s) + %d forward bases\n", $traj, $humN, $hum, $fb;
printf "  strikes %d, landings %d, ground victories %d, conquests %d, hives eradicated %d; counter-attacks repelled %d / battered %d / overran %d; hull break-ups %d\n",
  map { $c{$_} // 0 } qw(strikes landings victories conquests eradicated repelled battered overran breakups);
printf "  swarm front braced most %s (%d), counter-attacked most %s (%d)\n", $bw // '-', $bw ? $brace{$bw} : 0, $cw // '-', $cw ? $ca{$cw} : 0;
printf "  pressure fabricated %d (mean %d FP, %d%% under 100), sent %d, sends %d / arrived %d / disbanded %d, musters %d, rallied %d / no rally %d, no transfer %d\n",
  $fabn, $fabmean, $under, map { $c{$_} // 0 } qw(sent sends arrived disbanded musters rallied norally notransfer);
printf "  reinforcements in flight at the end %s (%s FP)%s\n", $inflight, $inflightFP, $odays;
printf "  overdue logged %d (same system %d, other system %d, hyperspace %d, in battle %d), logged again %d, 3+ times %d\n",
  map { $c{$_} // 0 } qw(overdue od_same od_other od_hyper od_battle od_again od_thrice);
printf "  end: fleets %dk FP, fund %dk, income %dk/mo, supplies %dk (+%dk / -%dk), fuel %dk (+%dk / -%dk), spare %s, sends held %s\n",
  $fleets / 1000, $banked / 1000, $income / 1000, $sup / 1000, $supIn / 1000, $supOut / 1000, $fuel / 1000, $fuelIn / 1000, $fuelOut / 1000, $spare // '?', $held // '?';
