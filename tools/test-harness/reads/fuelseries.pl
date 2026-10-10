#!/usr/bin/perl
# fuelseries.pl <ti-tag.txt> [days] - the swarm economy every N war days (default 180): hives, fleets, fuel stock
# (+made, -spent), fuel makers and output units, claims (Spread to), seedings held, supplies, the fuel plan (hw137 fuel lock read)
# per-game series: war day, hives, found, fleets FP, fuel stock (+mo, spent), fuel makers/output, cumulative spreads, seeding holds, strikes
my ($f,$step)=@ARGV; $step||=180;
open my $h,'<',$f or die; my ($day,$next,$spread,$mk,$out,$held,$seedheld,$plan)=(0,0,0,0,0,0,0,'');
while(<$h>){
  if(/^Clock: day \S+ war (\d+)/){$day=$1;next}
  if(/^Spread to:/){$spread++;next}
  if(/^Hive stock: a Seeding Swarm from .* held/){$seedheld++;next}
  if(/^Hive stock sources: fuel makers (\d+), output (\d+)/){($mk,$out)=($1,$2);next}
  if(/^Hive stock plan: fuel (.*)/){$plan=$1;$plan=~s/ in stock.*?made/ made/; next}
  if(/^Census: threat hives (\d+) \(size (\d+)\), found (\d+), fleets (\d+) FP.*?; fuel (\d+) \(\+(\d+)\/mo, spent (\d+)\); supplies (\d+) \(\+(\d+)\/mo, spent (\d+)\)/){
    next if $day<$next; $next=$day+$step;
    printf "d%5d hives %3d sz %4d found %2d fleets %6d | fuel %7d +%6d -%6d | makers %2d out %6d | spreads %3d seedHeld %4d | sup %6d +%6d -%6d | %s\n",$day,$1,$2,$3,$4,$5,$6,$7,$mk,$out,$spread,$seedheld,$8,$9,$10,substr($plan,0,70);
  }
}
