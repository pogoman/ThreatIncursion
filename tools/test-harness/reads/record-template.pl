#!/usr/bin/perl
# record-hw117.pl <mod root> <memory dir> - hw117 + hw118 (avoid list / hop: no; old sector 151 hives / 6 colonies - the swing) into game-runs-2.md 43, handover 29, swarm-defence.md, facts, memory.
use strict; use warnings;
my ($root, $mem) = @ARGV;
sub slurp { my $f = shift; open my $F, '<', $f or die "$f: $!"; local $/; my $s = <$F>; close $F; $s }
sub spit { my ($f, $s) = @_; open my $F, '>', $f or die "$f: $!"; print $F $s; close $F }
sub nl { $_[0] =~ /\r\n/ ? "\r\n" : "\n" }
sub sub1 { my ($s, $old, $new, $what) = @_; index($$s, $old) >= 0 or die "$what: anchor"; $$s =~ s/\Q$old\E/$new/; }

{ my $f = "$root/docs/game-runs-2.md"; my $s = slurp($f); my $nl = nl($s);
  my $b = "- **hw117 + hw118** (avoid list, hop, carry, fleet ids, cdc351a7, jar 10:35; hw117a from `ck2`, hw118a / b from `ck5`; 81 months each, no exception, ended 11:45): **the old sector's biggest swarm win of the night on the same rules that gave 44 hives two hours earlier - 151 hives against 6 colonies - and neither in-place remedy frees a frozen swarm.** hw117a: hives 38 -> 56 (m20) -> 60 (m40) -> 93 (m60) -> 151, humans 50 -> 6 (Hegemony 3, Independent 3; no forward base left), 10 hives eradicated against 42 conquests, 52 ground victories; sends 4,413 / arrived 4,246 / disbanded 99, 14 in flight at the end (none 90+ days); fund 278k, supplies 1k at +172k / -176k, fuel 3.2M, spare +34k, sends held 59. hw118a / b: hives 32 -> 191 / 103, humans 3 / 3 (Independent 2, Hegemony 1 both), eradicated 0 / 1, sends 2,015 / 958, arrived 1,906 / 922, in flight at the end 122 / 35, fund 264k / 452k, spare +141k / +49k. The old sector on one build (hw111a-hw117a, the carry stopgap in all four): 89 / 21, 49 / 33, 44 / 32, 151 / 6 - the sector's outcome is not the build's; it is the opening (which yard world the first strike takes, which hives the first sieges find). The read (650 swarms read overdue, by fleet id): 46 had `moved 0 units since the send` (7%), all in a belt or ring; a span after the nav avoid list was cleared 12 still at the unit, 19 creeping, 3 free; after the 500-unit hop along the heading 7 creeping, 4 free; the carry frees all. Frozen swarms whose ids were followed to an arrival: carried ones arrive within days of the carry (arrivals at 180 / 181 / 271 days out). hw119 / hw120: the carry at the second read (the swarm loses 180 days, not 270), and the read adds the velocity vector, the facing and the distance to the source hive's planet - a vector that flips between reads is an oscillation about a point, one that holds is a dead stop.";
  $s =~ s/(- \*\*hw115 \+ hw116\*\*[^\n]*)(\n|\z)/$1$nl$b$nl/ or die "runs bullet";
  spit($f, $s); print "runs ok\n"; }

{ my $f = "$root/docs/handover-2026-10-08-morning.md"; my $s = slurp($f); my $nl = nl($s);
  my $sec = "
## 29. hw117 + hw118 - read at 11:50: old sector 151 hives / 6 colonies on the rules that gave 44 / 32; no in-place remedy frees a frozen swarm

cdc351a7 (jar 10:35). hw117a (ck2): hives 38 -> 151, humans 50 -> 6, 10 eradicated / 42 conquests. hw118a
/ b (ck5): hives 191 / 103, humans 3 / 3. Four old-sector samples on one build: 89 / 21, 49 / 33, 44 / 32,
151 / 6 - the outcome is the opening's, not the build's. The read by fleet id: 46 of 650 overdue swarms
never moved from their send spot; a cleared avoid list left 12 still and 19 creeping (3 free), a 500-unit
hop 7 creeping (4 free); the carry frees all, and carried swarms arrive within days. hw119 / hw120 (jar
11:19): the carry at the second read; the read adds the velocity vector, the facing and the distance to
the source hive. `game-runs-2.md` 43, `swarm-defence.md` \"Overdue reinforcements\".
";
  $sec =~ s/\n/$nl/g; $s =~ s/\s*$/$nl$sec/; spit($f, $s); print "handover ok\n"; }

{ my $f = "$root/docs/swarm-defence.md"; my $s = slurp($f);
  my $old = "  overdue and arrival lines carry `id <fleet id>` and the days out, so a swarm's reads and its arrival link.\n";
  my $new = $old . "  Read (hw117/hw118, 11:50): 46 of 650 overdue swarms never moved from their send spot; a cleared avoid\n  list left 12 still and 19 creeping (3 free), the 500-unit hop 7 creeping (4 free); the carry frees all and\n  carried swarms arrive within days. So nothing done to the fleet in place frees it; only placing it near\n  its target does. hw119/hw120: the carry at the SECOND read (kick 2; the swarm loses 180 days, not 270),\n  and the read adds `velocity x/y`, `facing` and `from <hive> N units off (radius R)` - the source hive's\n  planet, to tell whether a frozen swarm sits on its hive.\n";
  sub1(\$s, $old, $new, "doc"); spit($f, $s); print "doc ok\n"; }

{ my $f = "$root/docs/facts.md"; my $s = slurp($f);
  sub1(\$s, "hw117/hw118: kick 1 clears the nav avoid list, kick 2 hops 500 units along the heading, kick 3 carries; fleet ids on the overdue and arrival lines.",
    "hw117/hw118 (11:50): a cleared avoid list and a 500-unit hop free almost none (46 of 650 overdue swarms never moved from their send spot); only the carry does, and carried swarms arrive within days. hw119/hw120: the carry at the second read; the read adds the velocity vector, the facing and the distance to the source hive.", "facts 1");
  sub1(\$s, "hw115/hw116 (11:00): old sector 44 / 32 (+25 forward bases), new sector 202 / 144 vs 4 / 4.", "hw115/hw116 (11:00): old sector 44 / 32 (+25 forward bases), new sector 202 / 144 vs 4 / 4. hw117/hw118 (11:50): old sector 151 / 6 on the same rules, new sector 191 / 103 vs 3 / 3 - four old-sector samples on one build span 44 to 151 hives: the opening decides, not the build.", "facts 2");
  spit($f, $s); print "facts ok\n"; }

{ my $f = "$mem/navy-fits-supply-and-use.md"; my $s = slurp($f);
  sub1(\$s, "Old sector all night: 66/47/40/42/80/36/77/92/65/89/49/44 hives.",
    "hw117/hw118 (11:50): old sector 151 / 6 (!), new 191 / 103 vs 3 / 3; in-place remedies (avoid list, hop) free none, only the carry; hw119/hw120 carry at the second read + velocity vector read. Four samples of one build: 89/21, 49/33, 44/32, 151/6 - the opening decides. Old sector all night: 66/47/40/42/80/36/77/92/65/89/49/44/151 hives.", "mem");
  spit($f, $s); print "mem ok\n"; }
{ my $f = "$mem/MEMORY.md"; my $s = slurp($f);
  $s =~ s/old sector 49\/33 then 44\/32 at 11:00 - trending human, new sector the swarm's always\)$/no in-place remedy frees them (hw117), carry at the second read from hw119; old sector on ONE build 89\/21, 49\/33, 44\/32, 151\/6 - the opening decides, not the build; new sector the swarm's always)/m or die "index";
  spit($f, $s); print "index ok\n"; }
