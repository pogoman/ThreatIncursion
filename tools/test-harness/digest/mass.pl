#!/usr/bin/perl
# mass.pl <tag>... : what the massing (postureMass, "Posture: X massed N FP at Y") did. Fleets massed by year
# with the moves between systems, sieges called off, landings and hives eradicated; how often a world gave
# within 30 days of receiving; and, for the sieges that came down on a hive world ("Daily siege takes over"),
# the FP massed toward their system in the 10 days before and the 10 days after.
use strict; use warnings;
my $d = "$ENV{TEMP}/threatinc-tests";
for my $tag (@ARGV) {
    open(my $h, '<:raw', "$d/ti-$tag.txt") or next;
    my ($war, $month) = (0, 0);
    my (@mass, @siege, %lastRecv, $bounce, $moves, $far, %byYear, %farByYear, %fpByYear);
    my (%off, %landed, %erad);
    ($bounce, $moves, $far) = (0, 0, 0);
    while (my $l = <$h>) {
        if ($l =~ /^Clock: day -?\d+ war (\d+)/) { $war = $1; next; }
        if ($l =~ /^Census: threat/) { $month++; next; }
        my $y = int($month / 12) + 1;
        if ($l =~ /^Posture: (.*?) massed (\d+) FP at (.*?)(, ([\d.]+) ly)? \((\d+) FP short of (\d+)\)/) {
            my ($from, $fp, $to, $ly) = ($1, $2, $3, $5);
            $moves++; $byYear{$y}++; $fpByYear{$y} += $fp;
            if (defined $ly) { $far++; $farByYear{$y}++; }
            $bounce++ if exists $lastRecv{$from} && $war - $lastRecv{$from} <= 30;
            $lastRecv{$to} = $war;
            push @mass, [$war, $to, $fp];
            next;
        }
        if ($l =~ /^Daily siege takes over (.*?) \((\w+)\): \d+ world\(s\), (\d+) FP/) {
            push @siege, [$war, $1, $2, $3];
            next;
        }
        if ($l =~ /^Siege called off over (.*?) \(/) { $off{$y}++; next; }
        if ($l =~ /^Notice: Expedition Landed/) { $landed{$y}++; next; }
        if ($l =~ /^Notice: Hive Eradicated/) { $erad{$y}++; next; }
    }
    close $h;
    print "== $tag: $month months; massed $moves fleets, $far between systems; a world gave within 30 days of receiving $bounce times\n";
    print "  year  massed(FP)      between-systems  called-off  landed  eradicated\n";
    for my $y (sort { $a <=> $b } keys %byYear) {
        printf "  %4d  %4d (%6.1fk)  %15d  %10d  %6d  %10d\n", $y, $byYear{$y}, $fpByYear{$y} / 1000,
            $farByYear{$y} || 0, $off{$y} || 0, $landed{$y} || 0, $erad{$y} || 0;
    }
    # per siege: FP massed toward its system's worlds in the 10 days before it came down, and the 10 after
    my ($n, $pre, $post, $none) = (0, 0, 0, 0);
    my (@preFP, @sizes);
    for my $s (@siege) {
        my ($w, $sys, $who, $fp) = @$s;
        (my $stem = $sys) =~ s/ Star System$//;
        my ($b, $a) = (0, 0);
        for my $m (@mass) {
            next unless index($m->[1], $stem) == 0;
            $b += $m->[2] if $m->[0] >= $w - 10 && $m->[0] < $w;
            $a += $m->[2] if $m->[0] >= $w && $m->[0] <= $w + 10;
        }
        $n++; $pre += $b; $post += $a; $none++ if $b + $a == 0;
        push @preFP, $b; push @sizes, $fp;
    }
    if ($n) {
        my @sp = sort { $a <=> $b } @preFP; my @ss = sort { $a <=> $b } @sizes;
        printf "  sieges come down %d: median %d FP; massed toward their system in the 10 days before a median %d FP (mean %d), in the 10 days after a mean %d; nothing massed for %d\n",
            $n, $ss[$#ss / 2], $sp[$#sp / 2], $pre / $n, $post / $n, $none;
    }
}
