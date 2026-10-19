use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }
sub patch { my ($p, $from, $to) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; $t =~ s/\r\n/\n/g if $crlf; my $c = () = $t =~ /\Q$from\E/g; die "$p: $c matches" unless $c == 1;
  $t =~ s/\Q$from\E/$to/; $t =~ s/\n/\r\n/g if $crlf; open($h, '>:raw', $p) or die; print $h $t; close $h; print "patched $p\n"; }

append("$root/docs/facts.md", <<'F');
- **Why did hw217c log "Reinforcement strayed from Khore ... (was: GO_TO_LOCATION -> Pair 'returning to the hive')" 7,800 times (2026-10-19 11:10)?** One fleet in two books. A raider (`ThreatRaiders.detach`) keeps its `GARRISON_FLAG` while out; back over Pair and dry, the bombardment's rearm pass (`ThreatGroundFronts` -> `ThreatColonyManager.sendToRearm`) read the flag as Pair's own and sent it to Khore as a reinforcement; the raider poll then found it within `HOME_RANGE` and seated it in Pair's garrison list ("Raider home at Pair"). The garrison leash (list) and the reinforcement leash (`REINFORCE_TARGET_KEY`) re-ordered it against each other every frame; it never moved. FIXED `patch-leash-raider.pl`: `sendToRearm` skips raiders, the raider poll seats no fleet meanwhile tracked in `reinforcementFleets` ("re-tasked meanwhile, not seated"), `sendToGarrison` strips the fleet from every garrison list. First on hw219 / hw220. (ground-war-orbit-control.md "ordnance", swarm-defence.md leash)
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw217 early check (2026-10-19 11:02, N1b, set B, b lost - "NO GAME WINDOW" at launch, the round ran a / c).** hw217a war ~w2,976: hives 61, refused plays 0 (hw215a 12), play sieges 59, hull convoys 55 / 48 landed (hw215a 515 all game), "short itself" gate 27; the Hegemony 42k FP with 39k FREE and 2.8k out - the navy sits again despite N1b, to read at the end. hw217c w3,180: hives 101, refused 0 (43), sieges 75, hull convoys 76. The watcher stopped on a `leash` signal in c: 7,804 lines "Reinforcement strayed from Khore: Defense Swarm (12 FP) 6257 units out (was: GO_TO_LOCATION -> Pair 'returning to the hive') - recalled" - DIAGNOSED (facts): a dry RAIDER returning to Pair was sent to rearm at Khore (its garrison flag never cleared on detach) and then seated home by the raider poll, so the garrison leash and the reinforcement leash fought over it every frame. Fixed in `scratchpad/patch-leash-raider.pl` (rearm skips raiders; the raider poll seats nothing a reinforcement book holds; `sendToGarrison` strips every garrison list), applied to the sources 11:12, built when hw218 frees the jar. `leash` added to the watcher's ignore list for this round.
R

patch("$root/docs/ground-war-orbit-control.md",
"arrivals poll); nowhere to go or no fuel for the trip, it stays dry and is judged again a day later.\n",
"arrivals poll); nowhere to go or no fuel for the trip, it stays dry and is judged again a day later.\nA raider (`ThreatRaiders.isRaider`) is never sent: it keeps its garrison flag while out and its poll seats\nit home - hw217c (2026-10-19) sent one to rearm, the poll seated it too, and the two leashes fought over it\n7,800 times (`sendToGarrison` now strips the fleet from every garrison list, and the raider poll seats no\nfleet the reinforcement book holds).\n");

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**hw217 early 11:02 + leash loop (11:10):** hw217b failed to open a window (round = a / c); a 61 hives, refused 0,
Hegemony 39k of 42k FP FREE (navy sits again - read at end); c 101 hives, refused 0. Leash loop DIAGNOSED + FIXED
(patch-leash-raider.pl, sources only, jar locked until hw218 ends ~12:10): a dry raider returning home was sent
to rearm (garrison flag kept on detach) AND seated home by the raider poll -> two leashes fought every frame.
Build + hw219 / hw220 after hw218.
M
print "done\n";
