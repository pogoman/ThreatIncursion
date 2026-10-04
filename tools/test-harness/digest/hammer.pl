#!/usr/bin/perl
# hammer.pl <tag>... : each HAMMER play from start to end, to month 120
# (the log lines of docs/war-council-invasions.md 7; a log from before 2026-10-04 17:00 has the old muster lines)
use strict; use warnings;
sub med { my @s = sort { $a <=> $b } @_; return @s ? $s[int($#s/2)] : 0; }
for my $tag (@ARGV) {
  my $path = -e "ti-$tag.txt" ? "ti-$tag.txt" : "$ENV{TEMP}/threatinc-tests/ti-$tag.txt";
  open(my $f, '<', $path) or die "$path: $!";
  my ($day, %p) = (0);
  my (%why, %end, @dur, @siege, @marines, @escort, @want, @seen, @ratio, @landed, @taken);
  my ($started, $struck, $sieges, $more, $waits, $noEscort, $partners, $heldRelief) = (0) x 8;
  while (<$f>) {
    $day = $1 if /Clock: day -?\d+ war (\d+)/;
    last if $day > 3600;
    if (/Play (\w+#\d+) HAMMER \w+ at .*?: start -> prepare \((.*?)[;)]/) {
      $started++; $p{$1} = 1; my $w = $2;
      $w = 'invasion line' if $w =~ /^invades /;
      $w =~ s/ on .*//; $w =~ s/invade the starved system.*/follow-on from STARVE/; $why{$w}++;
    }
    if (/Play \w+#\d+ HAMMER: siege of ([\d.]+) FP sails from .*, (\d+) marines aboard/) { $sieges++; push @siege, $1; push @marines, $2; }
    if (/Play \w+#\d+ HAMMER: escort of (\d+) FP built of (\d+) wanted \((\d+) FP reported/) {
      push @escort, $1; push @want, $2; push @seen, $3; $noEscort++ if $1 < 25; push @ratio, $1 / $3 if $3 > 0;
      $partners += $1 if /, (\d+) partners? with it/;
    }
    $struck++ if /Play \w+#\d+ HAMMER .*?: \w+ -> strike \(/;
    $waits++ if /Play \w+#\d+ HAMMER .*?: \w+ -> muster \(.*no siege the pools pay yet/;
    $more += $1 if /Play \w+#\d+ HAMMER: (?:stays, )?(\d+) more sieges? sails?/;
    $heldRelief++ if /HAMMER: held in \w+ \(relief owed\)/;
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
  print "  struck $struck; waited for stock $waits; held for relief $heldRelief\n";
  print "  sieges sailed $sieges (median " . med(@siege) . " FP, " . med(@marines) . " marines), $more of them added after the strike\n";
  print "  escorts " . scalar(@escort) . ": median " . med(@escort) . " FP built of " . med(@want) . " wanted against " . med(@seen)
      . " FP reported (" . sprintf('%.1f', med(@ratio)) . "x); none built $noEscort; partners joined $partners\n";
  print "  ended " . scalar(@dur) . " with a tally: median " . med(@dur) . " days; landings $lsum, worlds taken $tsum, plays that landed nothing $l0\n";
  print "    $end{$_}  $_\n" for sort { $end{$b} <=> $end{$a} } keys %end;
}
