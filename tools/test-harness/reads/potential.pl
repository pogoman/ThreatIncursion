#!/usr/bin/perl
# potential.pl <campaign.xml> - the swarm's mineable economy at full growth, as ThreatColonyManager.mineableNeeds reads it:
# per input the best output any live hive would make at size 8 (its deposits, or today's output scaled to size 8 for relics),
# what a size-8 consumer wants (Refining ore 10, rare ore 8; Fuel Production volatiles 8), consumers and mining worlds,
# today's shortfall for comparison, and the units a new full-grown source of output 4..11 would add
use strict; use warnings;
my $MAX=8; my %rich=(trace=>-1,sparse=>-1,diffuse=>0,moderate=>0,common=>0,abundant=>1,rich=>2,plentiful=>2,ultrarich=>3);
my %off=(ore=>0,rare_ore=>-2,volatiles=>-2); my %want=(ore=>$MAX+2,rare_ore=>$MAX,volatiles=>$MAX);
my ($f)=@ARGV; open my $h,'<',$f or die "usage: potential.pl <campaign.xml>\n"; my ($in,$blk)=(0,''); my (%seen,@m);
while(my $l=<$h>){
  if($l=~/^<market cl="Market" z=/){$in=1;$blk=$l;next}
  next unless $in; $blk.=$l; next unless $l=~/^<\/market>/; $in=0;
  my ($id)=$blk=~/<id>([^<]*)<\/id>/; next if !$id || $seen{$id}++;
  my ($fa)=$blk=~/<factionId>([^<]*)<\/factionId>/; next unless ($fa//'') eq 'threat';
  my ($n)=$blk=~/<name>([^<]*)<\/name>/; my ($sz)=$blk=~/<size>(\d+)<\/size>/; my %c;
  for my $c (keys %off){
    my $dep; while($blk=~/(?<![a-z_])\Q$c\E_(trace|sparse|diffuse|moderate|common|abundant|rich|plentiful|ultrarich)\b/g){ my $r=$rich{$1}; $dep=$r if !defined $dep || $r>$dep }
    my ($s,$d,$a)=(0,0,0); if($blk=~/<COMkt z="\d+" c="$c" sto="[^"]*" mS="(\d+)" iSL="\w+" mD="(\d+)".*?<available z="\d+" b="[^"]*" m="([\d.\-]+)">/s){ ($s,$d,$a)=($1,$2,$3) }
    my $pot = defined $dep ? $MAX+$off{$c}+$dep : 0; my $scaled = $s>0 ? $s-$sz+$MAX : 0; $pot=$scaled if $scaled>$pot;
    $c{$c}=[$pot,$s,$d,$a];
  }
  push @m,[$n,$sz,\%c];
}
printf "%d live hives\n", scalar @m;
for my $c (qw(ore rare_ore volatiles)){
  my ($best,$bn,$src,$cons,$short)=(0,'',0,0,0);
  for my $x (@m){ my ($pot,$s,$d,$a)=@{$x->[2]{$c}}; if($pot>0){$src++; if($pot>$best){$best=$pot;$bn=$x->[0]}} if($d>0){$cons++; $short+=$d>$a?$d-$a:0} }
  my $k=$cons>0?$cons:1; my @r; for my $p (4..11){ my $u=$p<$want{$c}?$p:$want{$c}; $u-=$best; $u=0 if $u<0; push @r,"$p:".($u*$k) }
  printf "%-9s best %2d (%s) of %2d wanted | sources %2d consumers %2d | short today %3d | relief @ output %s\n",$c,$best,$bn,$want{$c},$src,$cons,$short,join(" ",@r);
}
