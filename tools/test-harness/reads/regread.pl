#!/usr/bin/perl
# regread.pl <tag>... - the regional relief read (hw121+): per run, the regional sends (count, FP, light-years),
# the worlds they went to and how many of those were eradicated after their first regional relief, the donors
# and how many of them fell after giving, and the hives eradicated with and without any regional relief.
use strict; use warnings;
my $T = "/tmp/threatinc-tests";
for my $tag (@ARGV) {
  open my $F, '<', "$T/ti-$tag.txt" or do { warn "$tag: $!\n"; next };
  my (%firstTo, %gave, %fellAfterRelief, %donorFell, %erad, $n, $fp, $ly, $line);
  while (<$F>) {
    $line++;
    if (/^Posture: (.+?) rallied (\d+) FP to (.+?) from (\d+) ly \(regional relief/) {
      $n++; $fp += $2; $ly += $4;
      $firstTo{$3} //= $line; $gave{$1} //= $line;
    } elsif (/^Colony eradicated: (.+?)\s*$/) {
      my $w = $1; $erad{$w} = $line;
      $fellAfterRelief{$w} = 1 if defined $firstTo{$w} && $firstTo{$w} < $line;
      $donorFell{$w} = 1 if defined $gave{$w} && $gave{$w} < $line;
    }
  }
  close $F;
  my $targets = keys %firstTo; my $fell = keys %fellAfterRelief;
  my $donors = keys %gave; my $dfell = keys %donorFell; my $all = keys %erad;
  printf "%s: regional sends %d (%d FP, mean %.1f ly); %d worlds relieved, %d of them eradicated after; %d donors, %d of them eradicated after giving; hives eradicated %d (%d never relieved)\n",
    $tag, $n // 0, $fp // 0, $n ? $ly / $n : 0, $targets, $fell, $donors, $dfell, $all, $all - $fell;
}
