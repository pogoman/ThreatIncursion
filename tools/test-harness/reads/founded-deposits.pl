#!/usr/bin/perl
# founded-deposits.pl <base campaign.xml> <ti-log>... - the deposits of the worlds a run founded, in founding order:
# per log, after every 5th founding, the count of ore / rare ore / volatiles source worlds among them, and the
# sector's own share of colonizable worlds carrying each deposit (the denominator the spread picks from).
use strict; use warnings;
my ($base,@logs)=@ARGV; die "usage: founded-deposits.pl <campaign.xml> <ti-log>...\n" unless @logs;
my %rich=(trace=>-1,sparse=>-1,diffuse=>0,moderate=>0,common=>0,abundant=>1,rich=>2,plentiful=>2,ultrarich=>3);
my %dep; my ($in,$blk)=(0,'');
open my $h,'<',$base or die $!;
while(my $l=<$h>){
  if($l=~/^<market cl="\w*Market" z=/){$in=1;$blk=$l;next}
  next unless $in; $blk.=$l; next unless $l=~/^<\/market>/; $in=0;
  my ($n)=$blk=~/<name>([^<]*)<\/name>/; next unless $n;
  for my $c ('ore','rare_ore','volatiles'){ while($blk=~/(?<![a-z_])\Q$c\E_(trace|sparse|diffuse|moderate|common|abundant|rich|plentiful|ultrarich)\b/g){ my $r=$rich{$1}; $dep{$n}{$c}=$r if !defined $dep{$n}{$c} || $r>$dep{$n}{$c} } }
}
my %tot; my $worlds=0; for my $n (keys %dep){ $worlds++; $tot{$_}++ for keys %{$dep{$n}} }
printf "sector: %d deposit worlds - ore %d, rare ore %d, volatiles %d\n", $worlds, $tot{ore}//0, $tot{rare_ore}//0, $tot{volatiles}//0;
for my $log (@logs){
  open my $g,'<',$log or do { print "$log: no log\n"; next };
  my @f; while(<$g>){ push @f,$1 if /^Colony founded: (.+?)\s*$/ } close $g;
  my %n; my @rows;
  for my $i (0..$#f){ my $d=$dep{$f[$i]}||{}; $n{$_}++ for keys %$d; push @rows, sprintf("%d: ore %d rare %d vol %d", $i+1, $n{ore}//0, $n{rare_ore}//0, $n{volatiles}//0) if ($i+1)%5==0 || $i==$#f }
  (my $t=$log)=~s/.*ti-(\w+)\.txt/$1/; print "$t (".scalar(@f)." founded): ".join('; ',@rows)."\n";
  my @vol=grep { ($dep{$_}{volatiles}//-9)>-9 } @f; print "  volatiles worlds: ".join(', ', map { "$_ ".($dep{$_}{volatiles}>=2?'rich+':$dep{$_}{volatiles}>=1?'abundant':$dep{$_}{volatiles}>=0?'moderate':'sparse') } @vol)."\n";
}
