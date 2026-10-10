#!/usr/bin/perl
# blockfrac.pl <ti-tag.txt> <world> [days] - share of days a world sat under a blockade (imports cut > 0), per window
# share of days a world's imports/exports are cut by a blockade, per window (file, world, window days)
my ($f,$w,$win)=@ARGV; $win||=180; my ($d,$cut,%days,%cutdays)=(0,0);
open my $h,'<',$f or die;
while(<$h>){ if(/^Clock: day \S+ war (\d+)/){ my $nd=$1; for my $x ($d+1..$nd){ my $b=int($x/$win); $days{$b}++; $cutdays{$b}++ if $cut } $d=$nd; next }
  if(/^Blockade of \Q$w\E: imports cut (\d+)%/){ $cut=$1>0 } }
print join("  ", map { sprintf "d%d-%d %2d%%", $_*$win, ($_+1)*$win-1, 100*$cutdays{$_}/$days{$_} } grep { $_*$win>=1500 } sort {$a<=>$b} keys %days), "\n";
