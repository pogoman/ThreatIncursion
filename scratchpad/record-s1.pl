use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }
sub patch { my ($p, $from, $to) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; $t =~ s/\r\n/\n/g if $crlf; my $n = () = $t =~ /\Q$from\E/g; die "$p: $n matches for $from" unless $n == 1;
  $t =~ s/\Q$from\E/$to/; $t =~ s/\n/\r\n/g if $crlf; open($h, '>:raw', $p) or die; print $h $t; close $h; print "patched $p\n"; }

append("$root/docs/facts.md", <<'F');
- **DECISION 2026-10-19 15:30 (the user: "s1") - S1 BUILT (jar 15:37, `patch-s1.pl`): STAGING IS THE BASE'S COMMITMENT.** (1) A hammer that ends without its siege sailing (failure, neutral, worlds taken) leaves its staging to the base as a commitment (`ThreatConvoys.PlayStaging.committed`, key `base#commit`, the latest replacing an older one; a live play staging there takes it over) - so `ownKeep` still holds the landing's stock and the planner ships none of it to another base. Cleared when a siege sails from the base (`ThreatConvoys.clearCommitment` at IncursionManager's expedition draw) or after `threatinc_stagingCommitDays` 180 with no hammer re-staged there (lapses in `siegeStock`). Log: `Staging: X keeps its commitment to the landing (...) after play P`, `X met its commitment - a siege sailed`, `X's commitment lapsed`. (2) THE HAMMER WAITS WHILE STOCK RISES (`threatinc_councilWaitWhileRising`): `siegeCanPay` sets `lastRefusalGate / Have / Need`; at the end of a 30-day wait window `ThreatPlays.strike` fails only if the refused figure has not risen by `WAIT_RISE` 0.1 of the need since the window opened and the gate is the same (`stockRising`; `Play.refusedGate / refusedHave`); log `stock rising (...), waits another 30 d`. (3) A FEW STAGING HAMMERS A FACTION: the invade pass keeps `threatinc_invadeStagingMax` 2 staging at once (`stagingHammers`), one a base (`stagingHammerAt`), replacing my per-faction (14:05) and per-base (14:50) cuts. hw228 (A, convoys only without S1) killed at d~600 for the jar; trial hw229 (B) / hw230 (A) from 15:37 (run-siege-28). Read for: `keeps its commitment` vs `met its commitment` counts, `stock rising` lines, staged hammers reaching "its stock is in" (0 / 6 in hw227), sieges sailed against hw219a's 263, base-to-base marine convoys (42 of 46 in hw227a), and the sector outcome against hw227's 88 / 176.
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**S1 built 15:37 (the user 15:30: "s1"), hw229 (B) / hw230 (A) from 15:37.** A play's staging outlives the play as the base's commitment (no marines shipped out while a landing want stands; cleared when a siege sails from the base or after 180 d), the waiting hammer gets another 30-d window whenever its refused figure rose a tenth of the need, two staging hammers a faction at once, one a base. hw228 (set A, convoys only without S1) was killed at ~d600 to free the jar. Watch: `keeps its commitment` / `met its commitment` / `lapsed`, `stock rising`, `its stock is in` (hw227: 0 / 6), sieges sailed (hw227 63 / 78, hw219a 263), hives / colonies against hw227 88 / 22, 176 / 13.
R

patch("$root/docs/strategy-convoys.md",
"- **One staging hammer a faction.** The invade pass stages a hammer where nothing pays today\n  (`war-council-invasions.md` s2); its 30 + 30 days against a convoy fill is the figure to watch (hw223 / hw224).",
"- **A few staging hammers a faction, one a base** (`threatinc_invadeStagingMax` 2, S1 2026-10-19 15:30). The invade pass\n  stages a hammer where nothing pays today (`war-council-invasions.md` s2).\n- **Staging is the base's commitment (S1).** hw227a: 0 of 424 staged hammers ever filled - marines landed in dribs\n  (mean 256), the hammer failed at 60 d, its staging cleared, `ownKeep` dropped and the planner shipped the base's\n  marines to another base's default staging the same week (Ailmar -> Ilm 2,255, -> Vannin 2,844; 42 of 46 marine\n  convoys of 500+ were base-to-base). Now a hammer that ends without sailing leaves the base its staging as a\n  commitment (`PlayStaging.committed`, key `base#commit`, latest wins, a live play takes it over) until a siege sails\n  from the base (`clearCommitment`, at the expedition draw) or `stagingCommitDays` 180 pass with no hammer re-staged.\n  And the hammer waits on: at a 30-d window's end it fails only if the refused figure has not risen a tenth of its\n  need (`ThreatPlays.stockRising`, `councilWaitWhileRising`).");

append("$root/docs/war-council-invasions.md", <<'W', 1);
**S1 (2026-10-19 15:30, the user).** A staged hammer's staging is the base's commitment and outlives the play (`ThreatConvoys.clearPlayStaging(playId, commit)` from `end`; cleared by a siege sailing from the base or after `stagingCommitDays`); the strike's wait window renews while the refused figure rises (`stockRising`, `WAIT_RISE` 0.1 of the need, or the refusing gate changed); the invade pass keeps `invadeStagingMax` 2 hammers staging a faction, one a base (`stagingHammers`, `stagingHammerAt`). Why: hw227a staged 424 hammers at 44 bases, filled none, and shipped the marines base to base between them (`strategy-convoys.md`, "Staging is the base's commitment").
W

patch("$root/docs/handover-2026-10-19-afternoon.md",
"  Still pooled instantly (open for the user): link upkeep, founding kits, ordnance, hive-world feeding.\n",
"  Still pooled instantly (open for the user): link upkeep, founding kits, ordnance, hive-world feeding.\n- **S1 - STAGING IS THE BASE'S COMMITMENT** (15:30, the user: \"s1\"; built 15:37, `patch-s1.pl`): hw225 / hw227 showed\n  convoys only killed the human sieges (0 of 424 staged hammers filled; marines shipped base to base after each\n  failure). A hammer's staging outlives the play (`PlayStaging.committed`, cleared by a siege sailing or 180 d), the\n  wait window renews while the refused stock rises (`ThreatPlays.stockRising`), two staging hammers a faction, one a\n  base (`invadeStagingMax`). Trial hw229 (B) / hw230 (A) from 15:37. The swarm took both hw227 games (88 / 176 hives,\n  colonies 22 / 13) by the dead siege machine; expect OceanPena back toward hw219's contested 95 / 52.\n");

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**15:30 user: "s1" -> BUILT 15:37 (patch-s1.pl):** staging = the base's commitment (PlayStaging.committed, base#commit,
cleared when a siege sails from the base or 180 d), hammer waits another window while the refused figure rises a tenth
of the need (stockRising), invadeStagingMax 2 a faction, one a base. hw228 killed for the jar; hw229 (B) / hw230 (A)
from 15:37 (run-siege-28, watch-siege-28.out, check-hw229.sh). Read: keeps/met commitment, stock rising, its stock is in.
M
print "done\n";
