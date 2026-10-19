use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }

append("$root/docs/facts.md", <<'F');
- **hw217 read (2026-10-19 11:23, N1b on set B; b lost its window at launch, a / c only): hives 86 / - / 131, colonies 46 / - / 33 (hw215 70 / 91 / 189 with 33 / 38 / 12; hw211 93 / 67 / 136 with 35 / 43 / 20), 0 exceptions.** Both contested: the Hegemony core held 11 (a) and 9 (c) of 12 (hw215 5 / 1) under the same 93 swarm strikes a game; council sieges 203 / 262 (hw215 224 / 118), the Hegemony's 53 / 60; refused plays 0 / 0 (hw215 12 / 43) - N1b CONFIRMED on the refusals. Hull convoys 141 / 157 (hw215a 515), siege-short lends 110 / 138 - still a lot, "no hulls" gate 47 / 100. The Hegemony in a ended 26k hulls with 15k free and 10k out (the early check's 39k of 42k free came down); in c 8.4k with 1.5k free. Human landings -> cores destroyed 20 of 73 (a), 6 of 47 (c); Threat ground victories 17 / 20. OceanPena is the best result yet for the humans (46 colonies, 20 hives eradicated) - a human-leaning contest, not a swarm sector. hw218 (A) launched 11:23. (game-runs-2.md s54)
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw217 read (2026-10-19 11:23): hives 86 / - / 131, colonies 46 / - / 33 - contested both.** Set B on N1b (b "NO GAME WINDOW" at launch, the round ran a / c). Against hw215 (70 / 91 / 189, colonies 33 / 38 / 12) and hw211 (93 / 67 / 136, colonies 35 / 43 / 20): OceanPena (a) 86 hives but the humans at 46 colonies, their most on any snapshot - the Hegemony's core 11 of 12 (hw215a also held), 20 hives eradicated by human fronts (hw215a 26), human landings 73 -> 20 cores destroyed; Aphelion (c) 131 (189) and the Hegemony's core 9 of 12 (hw215c 1 of 12) under the same 93 swarm strikes - so hw215c's collapse was the opening's swing, not the sieges exposing the core. Council sieges sailed 203 / 262 (hw215 224 / 118), the Hegemony's 53 / 60 at a median 1,700 FP; refused plays 0 / 0 (12 / 43): the yard hold in `siegeCanPay` was the last refusal. Hull lending: 141 / 157 hull convoys (hw215a 515), siege-short lends 110 / 138, the "no hulls" gate 47 / 100 - the circle is broken but a quarter of all allied convoys still carry hulls. The Hegemony's navy in a: 42k FP with 39k free at ~w2,976, 26k with 15k free and 10k out at the end (yards 4.5k FP/mo); in c 8.4k, 1.5k free, 6.7k out - spent. Help requests expired 20 / 19 of 58 / 73. Effects: swarm strikes 93 / 93 -> 68 / 85 sieges taken over, daily sieges 95 / 101 -> landed 32 / 32 -> Threat ground victories 17 / 20; feeding swarms 1,256 / 1,325 landed 1,214 / 1,288; reinforcements 2,222 / 5,297 sent. The leash loop (7,804 lines, c) is in this log, fixed for hw219 (facts). OPEN for the user: OceanPena leans human (46 colonies, 86 hives) - whether the invade passes start too few plays is what the new pass line answers in hw219 / hw220.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**hw217 read 11:23:** hives 86 / - / 131, colonies 46 / - / 33 (hw215 70 / 91 / 189) - CONTESTED both; Hegemony core
held 11 / 9 of 12 (hw215 5 / 1) under the same 93 strikes -> hw215's b / c collapse was the opening's swing. Refused
plays 0 (N1b confirmed); hull convoys 141 / 157 (515); Hegemony a ended 26k / 15k free. OceanPena leans human.
hw218 (A) 11:23 -> read vs hw212 137 / 79 / 83; then after-siege22.sh builds leash fix + invade-pass line, hw219 / hw220.
M
print "done\n";
