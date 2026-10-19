use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }

append("$root/docs/facts.md", <<'F');
- **hw218 read (2026-10-19 12:00, N1b on set A; b "NO GAME WINDOW" again - the b slot at 300,0 failed in hw217 and hw218 both, window client 597x373): hives 112 / - / 83, colonies 37 / - / 40 (hw212 same snapshots 137 / 79 / 83 with 24 / 33 / 32; hw210 144 / 84 / 91), 0 exceptions.** PoseidonDeimos (a) the swarm's: the Hegemony's core 12 -> 1 colony, its hulls 1.3k FP, 13 of 124 council sieges its own; AmaruDugas (c) contested: the Hegemony 12 -> 7, 17.4k hulls with 15.2k FREE and 2.2k out, 19 of 117 sieges - the idle-navy question again (the invade-pass line answers it from hw219). Refused plays 0 / 0, hull convoys 99 / 31 of 138 / 63 allied convoys. Leash strays 22 / 17 (hw217c 7,804). N1b over four games: refused 0 everywhere, the swarm 2 / contested 2 (hw217a / hw218c lean human). hw219 (B) / hw220 (A) on the leash fix + pass line from 12:01. (game-runs-2.md s54)
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw218 read (2026-10-19 12:00): hives 112 / - / 83, colonies 37 / - / 40.** Set A on N1b (b AresDaniels "NO GAME WINDOW" at 300,0 - the same slot and offset as hw217b; the first game at 0,0 and the third at 600,0 ran both times, so the second launch is what the loaded machine drops). Same snapshots as hw212 (137 / 79 / 83, colonies 24 / 33 / 32) and hw210 (144 / 84 / 91): PoseidonDeimos (a) 112 hives, colonies 37, the Hegemony's core 12 -> 1 (hulls 1.3k FP, 497 out; its council sieges 13 of 124; swarm strikes 63 -> 49 takeovers, Threat ground victories 23 of 32 landings, human landings 24 -> 2 cores) - the swarm's, fewer hives than hw212 but the Hegemony gone; AmaruDugas (c) 83 hives, colonies 40 (32), the Hegemony 12 -> 7 with 17.4k hulls, 15.2k FREE, 2.2k out and 19 of 117 council sieges - 18 hives eradicated by human fronts, human landings 67 -> 18 cores, the navy idle as in hw217a. Refused plays 0 / 0; allied convoys 138 / 63 of which hulls 99 / 31 (siege-short lends 95 / 31); help requests expired 8 / 18. Leash strays 22 / 17 - the Khore loop was hw217c's raider alone. Feeding swarms 1,891 / 411 (hive upkeep is the swarm's main traffic in a). **N1b over four games: refused plays 0 in all, no exceptions; swarm 2 (hw217c 131, hw218a 112), contested 2 leaning human (hw217a 86 hives / 46 colonies, hw218c 83 / 40).** In both leaning games the Hegemony holds 15k FP free with 13-19 sieges of its own a game: the pass line in hw219 / hw220 says what stops the passes.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**hw218 read 12:00:** hives 112 / - / 83, colonies 37 / - / 40 (hw212 137 / 79 / 83) - a the swarm's (Hegemony 12 -> 1),
c contested, Hegemony 15.2k of 17.4k FP FREE with 19 sieges (same idle navy as hw217a). b slot (300,0) lost its
window twice today. N1b four games: refused 0 all, swarm 2 / contested 2. hw219 (B) / hw220 (A) on the leash fix +
invade-pass line from 12:01 (jar d4020bd5); read the "Council F invade pass" lines for the idle-navy cause.
M
print "done\n";
