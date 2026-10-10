#!/usr/bin/perl
# volsys.pl <campaign.xml> <threatinc_simmap.json.data> [commodity] - systems with a deposit of the commodity (default volatiles):
# who is there (THREAT = a hive in it, HUMAN = a faction colony - claims skip it, empty = claimable), the deposit planets with
# owner and richness, and light-years to the nearest hive system (sim map coordinates)
use strict; use warnings;
my ($f,$map,$want)=@ARGV; $want||='volatiles';
my %xy; { local $/; open my $m,'<',$map or die; my $s=<$m>; while($s=~/"name": "([^"]*)",\s*"planets": \d+,\s*"x": ([\d.\-E]+),\s*"y": ([\d.\-E]+)/g){ $xy{$1}=[$2,$3] } }
open my $h,'<',$f or die; my ($in,$blk,$sys)=(0,'',''); my (%sysname,%seen,%dep,%human,%threat);
my $dep=qr/\b(\Q$want\E_(?:trace|sparse|diffuse|moderate|common|abundant|rich|plentiful|ultrarich))\b/;
while(my $l=<$h>){
  while($l=~/(?:cl="Sstm"|<Sstm) z="(\d+)" dN="([^"]*)"/g){ $sysname{$1}=$2 }
  if(!$in && $l=~/<cL cl="Sstm" ref="(\d+)">/){ $sys=$1 }
  if($l=~/^<market cl="(?:PC)?Market" z=/){$in=1;$blk=$l;next}
  next unless $in; $blk.=$l;
  next unless $l=~/^<\/market>/; $in=0;
  my ($id)=$blk=~/<id>([^<]*)<\/id>/; next if !$id || $seen{$id}++;
  my ($n)=$blk=~/<name>([^<]*)<\/name>/; my ($fa)=$blk=~/<factionId>([^<]*)<\/factionId>/; my ($sz)=$blk=~/<size>(\d+)<\/size>/;
  my $colony = ($blk=~/^<market cl="Market"/ && $fa && $fa ne 'neutral') ? 1 : 0;
  if($colony){ if($fa eq 'threat'){$threat{$sys}=1} else {$human{$sys}{$fa}=1} }
  if($blk=~$dep){ my $r=$1; $r=~s/^\Q$want\E_//; push @{$dep{$sys}}, "$n(".($colony?"$fa ".($sz//''):"free")." $r)" }
}
my @ts=grep{ defined $sysname{$_} && $xy{$sysname{$_}} } keys %threat;
my %L;
for my $s (keys %dep){ my $p=$xy{$sysname{$s}//''}; my $bd=99; if($p){ for my $t (@ts){ my $q=$xy{$sysname{$t}}; my $d=sqrt(($p->[0]-$q->[0])**2+($p->[1]-$q->[1])**2); $bd=$d if $d<$bd } } $L{$s}=$bd }
for my $s (sort { $L{$a} <=> $L{$b} } keys %dep){
  my $st = $threat{$s} ? 'THREAT' : $human{$s} ? 'HUMAN('.join('/',sort keys %{$human{$s}}).')' : 'empty';
  printf "%5.1f ly  %-30s %-24s %s\n", $L{$s}, $sysname{$s}//"?$s", $st, join(", ",@{$dep{$s}});
}
