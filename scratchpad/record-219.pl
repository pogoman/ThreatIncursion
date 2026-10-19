use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }

append("$root/docs/facts.md", <<'F');
- **hw219 read (2026-10-19 12:39, leash fix + invade-pass line on set B; b lost its window): hives 95 / - / 195, colonies 52 / - / 11 (hw217 86 / - / 131 with 46 / - / 33; hw215 70 / 91 / 189 with 33 / 38 / 12), 0 exceptions.** The leash fix holds: strays 14 / 8 a game (hw217c 7,804), no loop. OceanPena (a): the humans' 52 colonies are their most on any snapshot, the Hegemony's core 11 of 12, 14 hives eradicated, human landings 81 -> 14 cores; the Hegemony ended 39k FP with 35k FREE and 44 sieges of its own - its 1,360 refused systems: marines 1,095, cannot pay 233, front first 32. Aphelion (c): 195 hives, the Hegemony 12 -> 4 colonies under 130 swarm strikes (hw217c 93 -> 131 hives): the same snapshot swings 131 <-> 195 on behaviour-neutral builds - the opening decides Aphelion, as it did hw215c. Refused plays 0 / 0 again; hull convoys 98 / 125 of 193 / 244 allied convoys. (game-runs-2.md s54)
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw219 read (2026-10-19 12:39): hives 95 / - / 195, colonies 52 / - / 11.** Set B on the leash fix + invade-pass line (jar 12:01, d4020bd5; b "NO GAME WINDOW" a third time). Against hw217 (86 / - / 131, colonies 46 / - / 33) and hw215 (70 / 91 / 189, colonies 33 / 38 / 12): OceanPena (a) 95 hives, the humans 52 colonies (their most anywhere), the Hegemony's core 11 of 12, 14 hives eradicated by human fronts, human landings 81 -> 14 cores destroyed, swarm strikes 53 -> 50 takeovers, Threat ground victories 9 of 16; the Hegemony 39k FP, 35k free, 4k out, 44 council sieges of its own (263 all factions) - its passes refused 1,360 systems: marines 1,095, cannot pay 233, its front finishes first 32 (the marine bind, facts 12:30). Aphelion (c) 195 hives, colonies 11, the Hegemony 12 -> 4 (7.3k hulls), 130 swarm strikes -> 108 takeovers, 46 Threat ground victories of 83 landings, 5 bases founded all game - the swarm's outright, where hw217c on the N1b jar made 131 / 33: the leash fix changes no strategy, so Aphelion swings 60 hives on its opening (hw215c 189, hw217c 131, hw219c 195). Leash strays 14 / 8 (hw217c 7,804) - FIXED; "re-tasked meanwhile" 0 (the rearm pass skips raiders now, so the poll's guard never fires). Refused plays 0 / 0; allied convoys 193 / 244 with hulls 98 / 125, siege-short lends 97 / 92; help requests expired 24 / 21. hw220 (A) runs to ~13:17.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**hw219 read 12:39:** hives 95 / - / 195, colonies 52 / - / 11 (hw217 86 / 131). Leash FIXED (strays 14 / 8). OceanPena
humans 52 colonies (most ever), Hegemony 35k of 39k free, marines 1,095 of 1,360 refusals. Aphelion 195 - swings
131 <-> 195 on behaviour-neutral builds (opening). hw220 (A) ends ~13:17, then the harness is idle: user's call on
P1 / P2 / P3 (marines) and the b slot.
M
print "done\n";
