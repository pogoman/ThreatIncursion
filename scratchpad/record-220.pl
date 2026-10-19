use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }

append("$root/docs/facts.md", <<'F');
- **hw220 read (2026-10-19 13:16, leash fix + invade-pass line on set A; b lost its window a fourth time, before the relaunch patch): hives 113 / - / 41, colonies 51 / - / 70 (hw218 112 / - / 83 with 37 / - / 40; hw212 137 / 79 / 83 with 24 / 33 / 32), 0 exceptions.** AmaruDugas (c) went HUMAN: 41 hives (peak 41, hw218c peak 86), 70 colonies, the Hegemony 12 -> 10 with 14.8k of 17.4k FP free and 21 sieges, 10 hives eradicated, human landings 49 -> 10 cores - on a build that changes no strategy (the leash fix, a log line): the same snapshot ran 83 / 83 / 91 / 41 hives today and on the 18th, the opening's swing decides it. PoseidonDeimos (a) the swarm's again, 113 hives, but the humans at 51 colonies (37 / 24) and the Hegemony 12 -> 6. Refused plays 0 / 0; marine refusals 679 / 522 of 974 / 621 systems. Leash strays 7 / 13. Over the day's eight games the humans lean or win in 4 (OceanPena x3, AmaruDugas), the swarm takes Aphelion, PoseidonDeimos and the openings' strong games - the humans-must-not-win rule is in play before any marine step lands. (game-runs-2.md s54)
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw220 read (2026-10-19 13:16): hives 113 / - / 41, colonies 51 / - / 70.** Set A on the leash fix + pass line (b "NO GAME WINDOW" a fourth time at 12:43; the relaunch patch 1ada125b landed after this harness had started). Same snapshots as hw218 (112 / - / 83, colonies 37 / - / 40) and hw212 (137 / 79 / 83, colonies 24 / 33 / 32). PoseidonDeimos (a) 113 hives, colonies 51, the Hegemony 12 -> 6 (8.9k hulls, 3.4k out), swarm strikes 42 -> 33 takeovers, Threat ground victories 10 of 27, human landings 37 -> 8 cores, 44 bases founded - the swarm's with a stronger human core than hw218a (1 colony left then). AmaruDugas (c) HUMAN: 41 hives at the peak, colonies 70, the swarm 62k FP on 9.4k FP/mo income (hw218c 57k / 8.8k but 83 hives), the Hegemony 10 of 12 with 17.4k hulls, 14.8k FREE, 2.6k out, 21 council sieges of its own (209 all factions), 10 hives eradicated, human landings 49 -> 10 cores, Threat ground victories 7 of 15, 40 swarm strikes -> 39 takeovers; the invade passes refused 621 systems, marines 522, cannot pay 98. Nothing in this build steers a strategy, so AmaruDugas 83 / 83 / 91 (hw212c, hw218c, hw210c) -> 41 is the opening: a human win unattended on a snapshot the swarm held three times. Refused plays 0 / 0; allied convoys 154 / 60 with hulls 98 / 51; leash strays 7 / 13; help requests expired 10 / 21. THE DAY (eight games, four rounds on N1 / N1b / leash): swarm Aphelion x3 (131-195), PoseidonDeimos x2 (112-113), AmaruDugas 83; humans lean OceanPena x3 (33 -> 46 -> 52 colonies, hives 70 -> 86 -> 95) and WIN AmaruDugas (70 colonies). Next: the marine step (the user's version, 13:00) - a marine-bound faction's core build raises the military ladder (Patrol HQ -> Military Base -> High Command) before another yard - specced in war-council-invasions.md, then hw221 (B) / hw222 (A).
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**hw220 read 13:16:** hives 113 / - / 41, colonies 51 / - / 70 (hw218 112 / 83). AmaruDugas went HUMAN (41 hives, 70
colonies) on a strategy-neutral build - opening swing (83 / 83 / 91 -> 41); b slot failed a 4th time (relaunch patch
1ada125b now in sbs.ps1 for hw221+). Day: humans lean OceanPena x3 + win AmaruDugas; swarm Aphelion x3, Poseidon x2.
USER 13:00: marine step = HIS version (build Military Base / High Command when marine-bound, before another yard),
building now; then one chain per hive (option 2 of 10-18) recommended next.
M
print "done\n";
