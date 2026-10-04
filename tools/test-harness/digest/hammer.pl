#!/usr/bin/perl
# hammer.pl <tag>... : each HAMMER play from start to end, to month 120
use strict; use warnings;
sub med { my @s = sort { $a <=> $b } @_; return @s ? $s[int($#s/2)] : 0; }
for my $tag (@ARGV) {
  open(my $f, '<', "ti-$tag.txt") or die "ti-$tag.txt: $!";
  my ($day, %p) = (0);
  my (%why, %end, @dur, @hunt, @siege, @landed, @taken, @ratio);
  my ($started, $muster, $strikeSiege, $strikeNone, $exploit, $heldRelief, $stays) = (0) x 7;
  while (<$f>) {
    $day = $1 if /Clock: day -?\d+ war (\d+)/;
    last if $day > 3600;
    if (/Play (\w+#\d+) HAMMER \w+ at .*?: start -> prepare \((.*?)[;)]/) {
      $started++; $p{$1} = 1; my $w = $2; $w =~ s/ on .*//; $w =~ s/invade the starved system.*/follow-on from STARVE/; $why{$w}++;
    }
    if (/Play (\w+#\d+) HAMMER .*?: prepare -> muster \((\d+) FP/) { $muster++; push @hunt, $2; $p{$1} = $2; }
    if (/Play (\w+#\d+) HAMMER .*?: muster -> strike \((.*)\)/) {
      my ($id, $t) = ($1, $2);
      if ($t =~ /no siege paid/) { $strikeNone++; }
      elsif ($t =~ /siege of ([\d.]+) FP/) { $strikeSiege++; push @siege, $1; push @ratio, $p{$id} / $1 if $p{$id} && $p{$id} > 1 && $1 > 0; }
    }
    $exploit++ if /Play \w+#\d+ HAMMER .*?: strike -> exploit/;
    $heldRelief++ if /HAMMER: held in muster \(relief owed\)/;
    if (/Play (\w+#\d+): (success|failure|neutral) \((.*?)[;)].*?landed (\d+), taken (\d+).*?, (\d+) d\)/ && $p{$1}) {
      my ($id, $o, $r, $l, $t, $d) = ($1, $2, $3, $4, $5, $6);
      $r =~ s/\d[\d,.]*/N/g; $end{"$o: $r"}++; push @dur, $d; push @landed, $l; push @taken, $t;
    } elsif (/Play (\w+#\d+): (success|failure|neutral) \((.*?)[;)]/ && $p{$1}) {
      my ($o, $r) = ($2, $3); $r =~ s/\d[\d,.]*/N/g; $end{"$o: $r"}++;
    }
  }
  my $l0 = grep { $_ == 0 } @landed; my $lsum = 0; $lsum += $_ for @landed; my $tsum = 0; $tsum += $_ for @taken;
  print "== $tag HAMMER plays to month 120\n";
  print "  started $started (" . join(', ', map { "$_ $why{$_}" } sort { $why{$b} <=> $why{$a} } keys %why) . ")\n";
  print "  reached muster $muster (median hunting force " . med(@hunt) . " FP); held in muster for relief $heldRelief\n";
  print "  struck with a siege $strikeSiege (median siege " . med(@siege) . " FP; hunts are " . sprintf('%.1f', med(@ratio)) . "x the siege); struck with no siege paid $strikeNone\n";
  print "  ended " . scalar(@dur) . " with a tally: median " . med(@dur) . " days; landings $lsum, worlds taken $tsum, plays that landed nothing $l0\n";
  print "    $end{$_}  $_\n" for sort { $end{$b} <=> $end{$a} } keys %end;
}
