#!/usr/bin/perl
# relief.pl <campaign.xml> - MineableNeed as the game computes it (ThreatColonyManager.mineableNeeds): per input, short today,
# short with the largest source cut, and the units a new source would add at full-grown outputs 4..11
use strict; use warnings;
my ($f)=@ARGV; open my $h,'<',$f or die; my ($in,$blk)=(0,''); my (%seen,@m);
while(my $l=<$h>){
  if($l=~/^<market cl="Market" z=/){$in=1;$blk=$l;next}
  next unless $in; $blk.=$l; next unless $l=~/^<\/market>/; $in=0;
  my ($id)=$blk=~/<id>([^<]*)<\/id>/; next if !$id || $seen{$id}++;
  my ($fa)=$blk=~/<factionId>([^<]*)<\/factionId>/; next unless ($fa//'') eq 'threat';
  my ($n)=$blk=~/<name>([^<]*)<\/name>/; my %c;
  for my $c (qw(ore rare_ore volatiles)){ if($blk=~/<COMkt z="\d+" c="$c" sto="[^"]*" mS="(\d+)" iSL="\w+" mD="(\d+)".*?<available z="\d+" b="[^"]*" m="([\d.\-]+)">/s){ $c{$c}=[$1,$2,$3] } }
  push @m,[$n,\%c];
}
for my $c (qw(ore rare_ore volatiles)){
  my ($top,$next,$largest,$src)=(0,0,'',0);
  for my $x (@m){ my $v=$x->[1]{$c} or next; my $s=$v->[0]; next unless $s>0; $src++; if($s>$top){$next=$top;$top=$s;$largest=$x->[0]} elsif($s>$next){$next=$s} }
  my ($short,$gap,@d)=(0,0);
  for my $x (@m){ my $v=$x->[1]{$c} or next; my ($s,$dm,$a)=@$v; next unless $dm>0;
    my $cut = $x->[0] eq $largest ? $s : ($s>$next?$s:$next); $cut=$a if $a<$cut;
    $short += $dm>$a?$dm-$a:0; $gap += $dm>$cut?$dm-$cut:0; push @d,[$dm,$a,$cut] }
  my @r; for my $p (4..11){ my $u=0; for my $d (@d){ my $base = $short>0 ? $d->[1] : $d->[2]; my $m=$d->[0]<$p?$d->[0]:$p; $u += $m>$base?$m-$base:0 } push @r,"$p:$u" }
  printf "%-9s sources %2d top %2d next %2d | short %3d top cut %3d | relief @ output %s\n",$c,$src,$top,$next,$short,$gap,join(" ",@r);
}
