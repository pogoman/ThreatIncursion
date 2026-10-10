#!/usr/bin/perl
# savecom.pl <campaign.xml> [faction] - every market of the faction (default threat) in a save: size, and for fuel (F),
# volatiles (V), heavy machinery (M) max supply S, max demand D, available A and why it falls short (vanilla modifiers).
# A broadcast source capped by its port reads "In-faction shortage (max in-faction production source has N)" at every importer.
# Threat markets in a campaign.xml: per market size, and for fuel/volatiles/heavy_machinery: max supply, max demand, available (+ why)
my ($f,$fac)=@ARGV; $fac||='threat';
open my $h,'<',$f or die; my ($in,$b)=(0,'');
while(<$h>){
  if(/^<market cl="Market" z=/){$in=1;$b=$_;next}
  next unless $in; $b.=$_;
  if(/^<\/market>/){$in=0; analyse($b); $b=''}
}
sub analyse{ my $b=shift; my ($fa)=$b=~/<factionId>([^<]*)<\/factionId>/; return unless $fa && $fa eq $fac;
  my ($n)=$b=~/<name>([^<]*)<\/name>/; my ($sz)=$b=~/<size>(\d+)<\/size>/;
  my @ind = $b=~/<(?:Industry|ind)[^>]*>\s*<id>([a-z_]+)<\/id>/g;
  my $out=sprintf "%-20s sz%s",$n,$sz//'?';
  for my $c (qw(fuel volatiles heavy_machinery)){
    if($b=~/<COMkt z="\d+" c="$c" sto="[^"]*" mS="(\d+)" iSL="\w+" mD="(\d+)".*?<available z="\d+" b="[^"]*" m="([\d.\-]+)">(.*?)<\/available>/s){
      my ($ms,$md,$av,$why)=($1,$2,$3,$4); my @w; while($why=~/s="([^"]*)" d="([^"]*)" v="([\d.\-]+)"/g){ push @w,"$2 $3" if $1 ne 'core_local' } 
      my $c2={fuel=>'F',volatiles=>'V',heavy_machinery=>'M'}->{$c};
      $out.=sprintf " | %s S%d D%d A%s%s",$c2,$ms,$md,$av,(@w&&$md>0?" [".join("; ",@w)."]":"");
    }
  }
  print "$out\n";
}
