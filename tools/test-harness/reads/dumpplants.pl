#!/usr/bin/perl
# dumpplants.pl <validation dir> <first Clock day, e.g. -644225> <from war day> <to> - per sim dump: hives with a fuel plant, size, state (* = siege clock running)
# per dump: hives with a fuel plant and its state (dir, day0 = the run's first Clock day, from, to)
my ($dir,$day0,$from,$to)=@ARGV;
opendir my $dh,$dir or die; my @f=grep /simdump_d-\d+\.json\.data$/, readdir $dh;
my %d; for(@f){ /d-(\d+)/; $d{$_}=-$1 } 
for my $f (sort {$d{$a}<=>$d{$b}} @f){ my $wd=$d{$f}-$day0+1; next if $wd<$from||$wd>$to;
  local $/; open my $h,'<',"$dir/$f"; my $s=<$h>; close $h;
  my ($hv)=$s=~/"hives": \[(.*?)\n \]/s; my @rows; my %st;
  while($hv=~/\{(.*?)\}/sg){ my $b=$1; my ($n)=$b=~/"name": "([^"]*)"/; my ($fp)=$b=~/"fuelPlant": "([^"]*)"/; my ($sz)=$b=~/"size": (\d+)/; my ($sc)=$b=~/"siegeClock": ([\d.]+)/;
    $st{$fp||'none'}++; push @rows, sprintf("%s%d:%s%s",$n,$sz,$fp,($sc>0?"*":"")) if $fp && $fp ne 'none' && $fp ne ''; }
  my ($fuel)=$s=~/"fuel": ([\d.]+)/;
  printf "w%4d fuel %7d plants %s | %s\n",$wd,$fuel,join(" ",map{"$_=$st{$_}"} sort keys %st),join(", ",@rows);
}
