#!/usr/bin/perl
# data.pl all.json > data.js - runs.pl's output as month arrays for the page.
use strict; use warnings; use JSON::PP;
local $/; my $j = decode_json(<>);
my @F = qw(hegemony persean independent tritachyon luddic_church luddic_path sindrian_diktat);
my %start = (hegemony=>12,persean=>12,independent=>12,tritachyon=>5,luddic_church=>4,luddic_path=>2,sindrian_diktat=>3,pirates=>9);
my @TK = qw(hives fleets income upkeep banked sup supMade supSpent fuel fuelMade fuelSpent);
my @HK = qw(hulls free built lost yards);
my @NK = qw(landing overrun expedition campaign strike strikeFP seedHeld navyShort chestFull hullConvoy);
my @FK = qw(baseFounded baseLost cannotPay noHulls yardsIdle reliefSent reliefFP reliefHeld reliefLost starve);
my @out;
for my $tag (sort keys %$j) {
  my $M = $j->{$tag}{months};
  my ($last) = sort { $b <=> $a } grep { $_ >= 0 } keys %$M;
  my %r = (tag => $tag, endDay => $j->{$tag}{lastWarDay}, n => $last + 1);
  my (%lostCum, @lost, @killed, @seeded);
  for my $m (0..$last) {
    my $x = $M->{$m} // {};
    my $t = $x->{t};
    push @{$r{t}{$_}}, ($t ? $t->{$_} + 0 : undef) for @TK;
    my $e = $x->{ev} // {};
    for (@{$e->{worldLost} // []}) { my ($w, $f) = /^(.*) \((\w+)\)$/; push @lost, [$m, $w, $f]; $lostCum{$f}++; }
    push @killed, [$m, $_] for @{$e->{hiveKilled} // []};
    push @seeded, [$m, $_] for @{$e->{hiveSeeded} // []};
    my $c = $x->{n} // {};
    push @{$r{nm}{$_}}, ($c->{$_} // 0) for @NK;
    for my $f (@F, 'pirates') { push @{$r{col}{$f}}, $start{$f} - ($lostCum{$f} // 0); }
    for my $f (@F) {
      my $h = $x->{h}{$f} // {};
      push @{$r{f}{$f}{$_}}, (defined $h->{hu} ? $h->{hu}{$_} + 0 : undef) for @HK;
      push @{$r{f}{$f}{sup}}, (defined $h->{c} ? $h->{c}{sup} + 0 : undef);
      push @{$r{f}{$f}{fuel}}, (defined $h->{c} ? $h->{c}{fuel} + 0 : undef);
      push @{$r{f}{$f}{stance}}, (defined $h->{c} ? $h->{c}{stance} : undef);
      push @{$r{f}{$f}{"m_$_"}}, ($c->{"$_:$f"} // 0) for @FK;
    }
  }
  $r{lost} = \@lost; $r{killed} = \@killed; $r{seeded} = \@seeded;
  push @out, \%r;
}
print "const RUNS = ", JSON::PP->new->canonical->encode(\@out), ";\n";
