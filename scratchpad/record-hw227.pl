use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }

append("$root/docs/facts.md", <<'F');
- **hw227 read (2026-10-19 15:25, convoys only + lastRefusal + per-base staging gate, set B; b lost its window a third time): hives 88 / - / 176, human colonies 22 / - / 13 (hw225 84 / 123 with 34 / 17; hw219 95 / 195 with 52 / 10).** Both the swarm's, but by a human siege machine that cannot siege: of 424 / 279 staged hammers, 0 / 6 ever had "its stock is in"; 454 / 291 failed at 60 days, 342 / 264 of them refused for MARINES at the base (`last refusal: marines 1374 of 1520 at Ailmar`, 1253 of 1901, 826 / 1690 / 1782 of 2666 - the want grows while it waits), 109 / 27 for points (supplies or hulls free). Sieges sailed 63 / 78, all the invade line's "paid today" ones (37 / 22 strikes). THE CAUSE (Ailmar traced): a base's marines arrive in dribs (419 marine convoys landed, mean 256 - a core colony raises 40-77 a month and keeps half), so a 1,500-2,700 landing takes months and the hammer fails at 60 d; the failure (or a `neutral` end) clears its play staging, `ownKeep` drops to the donor keep, and the planner ships the base's marines to ANOTHER base's default staging the same week - Ailmar sent 2,255 marines + 2,918 armaments to Ilm and 2,844 marines to Vannin between its hammers, neither of which sailed a siege either; 42 of the 46 marine convoys of 500+ in a (32 of 32 in c) sailed from one staging base to another. Marines circulate among 44 / 55 staging bases and never meet a landing. The per-base staging gate (my 14:50 cut) made it worse (sailed 37 vs 76 at the early check). Options for the user in the 15:30 message: S1 a base's staging is the base's commitment (survives the play's end, no out-shipping while a landing want stands) + the hammer waits while stock rises, few staging hammers a faction; S3 stage at the richest base; the min-load rule for the last part of a landing. Nothing built. hw228 (A) follows.
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw227 read (2026-10-19 15:25): hives 88 / - / 176, colonies 22 / - / 13.** Convoys only with the refusal reasons logged and the staging gate per base. The swarm takes both (OceanPena 88 / 22 against hw219a's 95 / 52), but the human sieges are dead, not beaten: staged hammers 424 / 279, filled 0 / 6, failed 454 / 291 - refused for marines 342 / 264 (figures like 1,374 of 1,520, 826 of 2,666: the want grows as the garrison does), for points 109 / 27; sieges sailed 63 / 78, all paid-today strikes. Traced at Ailmar: marine convoys land in dribs (mean 256 / 189 marines), the hammer fails at 60 d, its staging clears, and the planner ships the base's marines to another base's default staging (Ailmar -> Ilm 2,255, -> Vannin 2,844); 42 of 46 big marine convoys in a were base-to-base. Marines circulate among 44 / 55 staging bases and never land. Per-base gate worse than per-faction (37 vs 76 sailed at the early check). Decision pending (S1 / S3 / min-load); hw228 (A) running.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**15:25 hw227 (convoys only + lastRefusal + per-base gate): 88 / - / 176 hives, colonies 22 / 13.** Swarm's, but human
sieges DEAD: 424 / 279 staged hammers, 0 / 6 filled, failed 454 / 291 (marines 342 / 264). Cause: marines arrive in dribs
(mean 256), hammer fails at 60 d, staging clears, planner ships the base's marines to another staging base (Ailmar -> Ilm
2,255, -> Vannin 2,844; 42 of 46 big marine convoys base-to-base). Options S1 (base's commitment survives the play, no
out-shipping, wait while rising, few hammers a faction - recommended) / S3 / min-load put to the user 15:30. hw228 (A) running.
M
print "done\n";
