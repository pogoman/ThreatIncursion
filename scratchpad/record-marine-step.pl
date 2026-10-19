use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }
sub patch { my ($p, $from, $to) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; $t =~ s/\r\n/\n/g if $crlf; my $c = () = $t =~ /\Q$from\E/g; die "$p: $c matches for $from" unless $c == 1;
  $t =~ s/\Q$from\E/$to/; $t =~ s/\n/\r\n/g if $crlf; open($h, '>:raw', $p) or die; print $h $t; close $h; print "patched $p\n"; }

append("$root/docs/facts.md", <<'F');
- **DECISION 2026-10-19 13:00 (the user, on the marine bind: "marines come from military base/high command right? by P1 do you mean they build those building when short instead of more orbital works?" - "yeah my version"): THE MARINE STEP.** Built 13:20 (`patch-marine-step.pl`, jar 13:20): `ThreatPlays.payingBase` notes the marines a landing lacked (`ThreatFactionStock.noteMarineShort`, the window's largest, stands `SHORT_DAYS` 30); while it stands `ThreatCoreBuild.marineStep` runs before the yard step - at the largest core colony with a step left it upgrades Patrol HQ -> Military Base, Military Base -> High Command (one a faction, `hasHighCommand`), or starts a Patrol HQ where none of the three stands and a slot is free; paid now or its price held at the colony (`holdStep`, as a yard's). Log `Core build: X (f) upgrades patrolhq to militarybase (marine step: N marines short)`. Knob `threatinc_marineStep`. Vanilla: Patrol HQ raises no marines, Military Base raises them by size, High Command more; the reserve banks that surplus at `reserveTroopSurplusMult` 0.5 plus the militia. Trial hw221 (B) / hw222 (A) from 13:22. (frontlines.md s8, war-council-invasions.md s2)
- **DECISION 2026-10-19 13:30 (the user, on the forward bases: "one chain per hive? theres like 30 hives no are you mad? the allied sharing fix sounds better ... unsure how they share supplies at a shared base sounds complicated"): ALLIED STAGING, no shared purse - next after the marine step.** A faction sieges from an ally's base already within reach instead of founding its own chain toward the same hive; its siege is paid as today by its OWN markets hauling to the base (`IncursionManager.siegeDonors`, net of the haul) - the base is a berth, not a warehouse, the ally's stock untouched beyond its existing aid convoys. Two changes: `ThreatPlays.payingBase` / the launch's base pick take any allied base in reach, and `ThreatFrontlines.planFor`'s "reached" test counts an ally's base. Not built yet.
F

patch("$root/docs/war-council-invasions.md",
"What stops an invasion now: no report of the system, marines, fuel, supplies, armaments, or relief\n",
"A landing `payingBase` cannot man notes the marines it lacked (`ThreatFactionStock.noteMarineShort`): the\ncore build raises the faction's military ladder before another yard while the note stands (THE MARINE STEP,\n`ThreatCoreBuild.marineStep`, 2026-10-19 - hw219a: 1,095 of the Hegemony's 1,360 refused systems were\nmarines, with 35k FP free; `frontlines.md` s8).\n\nWhat stops an invasion now: no report of the system, marines, fuel, supplies, armaments, or relief\n");

append("$root/docs/frontlines.md", <<'R');
- **The marine step** (2026-10-19 13:00, the user: "build those buildings when short instead of more orbital
  works"; knob `threatinc_marineStep`). The invade pass's `payingBase` notes the marines a landing lacked
  (`ThreatFactionStock.noteMarineShort`, the window's largest, 30 days); while the note stands the core build's
  pass runs `ThreatCoreBuild.marineStep` ahead of the yard step: at the largest core colony with a step left,
  Patrol HQ upgraded to Military Base, Military Base to High Command (one a faction, `hasHighCommand`), a
  Patrol HQ started where none of the three stands and a slot is free - paid now (`payBuild`) or its price
  held at the colony (`holdStep`, as a yard's is). Vanilla's Military Base raises marines by colony size and
  High Command more (a Patrol HQ none); the reserve banks that surplus at `reserveTroopSurplusMult` (0.5)
  plus the militia. hw219a: the Hegemony sat on 35k of 39k FP free with 1,095 of 1,360 refused systems
  "marines" - a landing wants 1,100-2,700, a market raises 40-77 a month. Trial hw221 / hw222.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**13:20 MARINE STEP BUILT** (user's version: Patrol HQ -> Military Base -> High Command before another yard while
payingBase notes a marine shortfall; knob threatinc_marineStep; jar 13:20) - hw221 (B) / hw222 (A) from 13:22
(run-siege-24, sbs relaunch patch live). USER 13:30: forward bases -> ALLIED STAGING (siege from an ally's base in
reach, paid by own markets hauling there, no shared purse; planner counts an ally's base as reached) - next, unbuilt.
M
print "done\n";
