#!/usr/bin/perl
# balance.pl <campaign.xml> - the swarm's mineable pull as ThreatColonyManager.mineableNeeds reads it, per input:
# - cover in full-strength worlds: each live hive's size-8 output (deposit, or today's output grown to size 8) capped at a
#   size-8 consumer's draw (ore 10, rare ore 8, volatiles 8), summed over that draw; how far behind the best-covered input,
#   and what a full-strength world of it scores for that
# - the best size-8 source against the largest consumer's want (consumers grown to size 8): a richer deposit scores its rise
#   over the best, up to that want, once per consumer
# - today's shortfall, for comparison
use strict; use warnings;
my $MAX=8; my %rich=(trace=>-1,sparse=>-1,diffuse=>0,moderate=>0,common=>0,abundant=>1,rich=>2,plentiful=>2,ultrarich=>3);
my %off=(ore=>0,rare_ore=>-2,volatiles=>-2); my %want=(ore=>$MAX+2,rare_ore=>$MAX,volatiles=>$MAX);
my ($f)=@ARGV; open my $h,'<',$f or die "usage: balance.pl <campaign.xml>\n"; my ($in,$blk)=(0,'');
my (%seen,%cover,%src,%short,%best,%top,%con); my $hives=0;
while(my $l=<$h>){
  if($l=~/^<market cl="Market" z=/){$in=1;$blk=$l;next}
  next unless $in; $blk.=$l; next unless $l=~/^<\/market>/; $in=0;
  my ($id)=$blk=~/<id>([^<]*)<\/id>/; next if !$id || $seen{$id}++;
  my ($fa)=$blk=~/<factionId>([^<]*)<\/factionId>/; next unless ($fa//'') eq 'threat'; $hives++;
  my ($sz)=$blk=~/<size>(\d+)<\/size>/; my $full=$sz>$MAX?$sz:$MAX;
  for my $c (keys %off){
    my $dep; while($blk=~/(?<![a-z_])\Q$c\E_(trace|sparse|diffuse|moderate|common|abundant|rich|plentiful|ultrarich)\b/g){ my $r=$rich{$1}; $dep=$r if !defined $dep || $r>$dep }
    my ($s,$d,$a)=(0,0,0); if($blk=~/<COMkt z="\d+" c="$c" sto="[^"]*" mS="(\d+)" iSL="\w+" mD="(\d+)".*?<available z="\d+" b="[^"]*" m="([\d.\-]+)">/s){ ($s,$d,$a)=($1,$2,$3) }
    my $pot = defined $dep ? $full+$off{$c}+$dep : 0; my $g = $s>0 ? $s+$full-$sz : 0; $pot=$g if $g>$pot;
    if($pot>0){ $src{$c}++; $cover{$c}+=($pot<$want{$c}?$pot:$want{$c})/$want{$c}; $best{$c}=$pot if $pot>($best{$c}//0) }
    if($d>0){ $con{$c}++; my $w=$d+($MAX>$sz?$MAX-$sz:0); $top{$c}=$w if $w>($top{$c}//0) }
    $short{$c}+= $d>$a?$d-$a:0;
  }
}
my $lead=0; for (values %cover){ $lead=$_ if $_>$lead }
printf "%d live hives\n",$hives;
for my $c (qw(ore rare_ore volatiles)){
  my $cv=$cover{$c}//0; my $behind=$lead>0?($lead-$cv)/$lead:1;
  my $tw=($top{$c}//0)>$want{$c}?$top{$c}:$want{$c}; my $k=($con{$c}//0)||1;
  printf "%-9s %5.1f worlds, %2d sources | behind %.2f: a full world scores %3d | best %2d of %2d wanted, %2d consumers: each unit above scores %3d | short today %d\n",
    $c,$cv,$src{$c}//0,$behind,int($want{$c}*$behind*30+0.5),$best{$c}//0,$tw,$con{$c}//0,$k*30,$short{$c}//0;
}
