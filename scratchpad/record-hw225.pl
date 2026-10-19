use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }

append("$root/docs/facts.md", <<'F');
- **hw225 read (2026-10-19 14:46, allied staging A + convoys only, set B; b lost its window twice again): hives 84 / - / 123, colonies 34 / - / 17, bases 63 / - / 12 (hw219 95 / - / 195 with 52 / - / 11 colonies).** The pool is off (0 `pooled for` lines), allied staging ran (hostings 27 / 18, guest convoys 17 / 11, 0 exceptions), convoys flowed (1,024 dispatched / 889 landed in a, 187k marines carried, 383 marine convoys to core colonies). THE SIEGE MACHINE STALLED: hammers 215 / 142 started, 68 / 25 reached STRIKE, 120 / 104 FAILED "no siege the pools pay in 30 d", 57 / 49 held for relief owed; sieges sailed 76 / 27 against hw219a's 263, the invade line paid 58 / 13 today against 176. `siegeCanPay` is a bare boolean, so WHAT was short is unknown (a's Hegemony had 14.4k of 17.9k FP OUT at the check - hulls are a candidate). OceanPena went 84 hives / 34 colonies: the swarm ahead, but by a human siege machine that barely sails - not the win the user wants. Fixes 14:55 (`patch-refusal-why.pl`): `IncursionManager.lastRefusal` names the gate that refused (marines / armaments H of W, razing fuel, or `pays P of M points (fuel F for f a point, supplies S for s a point, hulls free H FP)`) and the hammer's MUSTER / FAILURE lines carry it; the invade pass's staging gate is PER BASE (`ThreatPlays.stagingHammerAt`; one a faction was my 14:05 cut, not the user's). Trial hw227 (B) / hw228 (A). Harness: `-Knobs`-less knob flips still go through the LunaLib store (see hw223).
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw225 read (2026-10-19 14:46): hives 84 / - / 123, colonies 34 / - / 17, bases 63 / - / 12.** First valid round of allied staging A + convoys only (set B; b lost its window twice, relaunch included). Pool off: 0 pooled draws. Allied staging: 27 / 18 hostings (the Diktat at the League's Madeira, the Path at the Church's Gilead), 17 / 11 guest convoys, 0 exceptions. Convoys: 1,024 dispatched, 889 landed, 461 holding off blockades (Baetis 95), 187k marines carried. BUT the human sieges stalled: hammers 215 / 142, struck 68 / 25, failed for stock 120 / 104 (60 days: 30 preparing + 30 waiting), held for relief owed 57 / 49; sieges sailed 76 / 27 (hw219a 263), paid-today strikes 58 / 13 (176). OceanPena 84 hives / 34 colonies (hw219a 95 / 52), Aphelion 123 / 17 (195 / 10). Unknown what the refusals were (siegeCanPay said no without saying why; Hegemony hulls 14.4k of 17.9k out at the check). Build 14:55: lastRefusal on the wait / failure lines, staging gate per base (was per faction). Next hw227 (B) / hw228 (A): read the refusal reasons first, then the sailed count against 263.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**14:46 hw225 (A + convoys only, valid):** 84 / - / 123 hives, colonies 34 / 17, bases 63 / 12; pool off, allied staging
runs (27 / 18 hostings, 17 / 11 guest convoys). BUT human sieges stalled: hammers failed 120 / 104 for stock in 60 d,
sieges sailed 76 vs 263 (hw219a); reason unlogged. Built 14:55: IncursionManager.lastRefusal on MUSTER / FAILURE lines,
staging gate per base (stagingHammerAt). hw227 (B) / hw228 (A) next - read refusal reasons first.
M
print "done\n";
