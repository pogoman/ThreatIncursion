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
- **Why does a human faction sit on a free navy and invade so little (hw217a / hw218c / hw219a, 2026-10-19 12:30: the Hegemony 39k of 40k FP free, 13-19 council sieges a game)? MARINES.** The invade-pass line (hw219a, 739 passes): of the systems with no paying base, 594 were "marines", 67 "cannot pay", 22 "its front finishes first" - `ThreatPlays.payingBase` wants `siegeRaidStrNeeded` x `minMarinesFraction` (1.0, `npcSiegeFullStrength`) pooled at the base and its donors (`IncursionManager.siegeMarinesPooled`), 1,100-2,700 marines a landing, while the faction raises ~40-77 marines per market per 30 days (`ThreatReserves.accrualPer30`: vanilla surplus x `reserveTroopSurplusMult` 0.5 - halved 2026-09-30 when banking outran landings 13:1 - plus the militia), ~700 a month across 14 markets; the reserve fell 4,443 -> 2,052 over the game. The yards-first navy outgrew the marine trickle: a landing every 2-4 months a faction, whatever the navy. Bombardment cannot substitute (`bombardPlan` wears fortifications only; the sizing already takes that step). OPTIONS for the user (strategy before knobs): P1 marines follow the war footing - a marine-bound faction raises marines from its surplus supplies as it builds hulls from its yards (a `ThreatFactionStock` want like `yardWant`, priced, closed economy) - recommended; P2 `reserveTroopSurplusMult` back to 1.0 (the knob; banks vanilla's surplus, unconnected to the navy); P3 coalition marines pooled like hulls (everyone's trickle is small). CAVEAT: the humans already lean in 2 of 4 N1b games; more landings push toward a human win unattended (humans-must-not-win rule) unless the swarm answers. (game-runs-2.md s54, war-council-invasions.md 2)
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw219 early check (2026-10-19 12:23, leash fix + invade-pass line, set B; b lost its window again - third b-slot failure today): hw219a ~w3,085 hives 55 (hw217a 61 at the same point), hw219c ~w3,250 hives 143 (hw217c ~101).** Refused plays 0 / 0, play sieges 94 / 32, hull convoys 66 / 68, 0 exceptions, the watcher quiet (leash armed: strays 22 / 17 in hw218, none looping). THE IDLE NAVY EXPLAINED: the Hegemony in a holds 40k FP with 39.3k free and 803 out; its invade passes (739 in the log) end "no paying base in N: {marines=N}" - 594 systems refused for marines, 67 cannot pay, 22 its front finishes first; in c 479 / 104 / 1. Its reserve ledgers raise +40..+77 marines per market per 30 days (vanilla surplus x 0.5 + militia), the reserve 4,443 -> 2,052 marines over the game, while a full-strength landing wants 1,141-2,667 (the sieges that did sail). The marines bind the human offensive now that the yards do not - options P1 / P2 / P3 in facts for the user; the caveat that more human landings press the swarm harder in games the humans already lean.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**hw219 early 12:23 - IDLE NAVY = MARINES:** invade-pass line: 594 of 683 refused systems "marines" (Hegemony 39k of 40k
FP free); accrual ~40-77 marines/market/30d (reserveTroopSurplusMult 0.5 since 09-30 + militia) vs 1,100-2,700 a
landing -> one landing per 2-4 months a faction. Options P1 (marines follow the war footing, a FactionStock want -
recommended) / P2 (troop mult 1.0, knob) / P3 (coalition marines) in facts; caveat: humans lean 2 of 4 already.
hw219 end ~12:38, hw220 (A) ~13:15; b slot (300,0) failed 3 of 3 rounds today.
M

patch("$mem/MEMORY.md",
"n1b (yard hold in siegeCanPay, no circular lending) -> hw217 / hw218\n",
"n1b (yard hold in siegeCanPay, no circular lending) -> hw217 / hw218 read: refused 0 all, swarm 2 / contested 2 (humans lean OceanPena 86 / 46 col, AmaruDugas 83 / 40); leash loop (raider + rearm) FIXED 911f71f0; IDLE NAVY = MARINES (hw219 pass line: 594 of 683 refusals), options P1 / P2 / P3 for the user; hw219 / hw220 from 12:01\n");
print "done\n";
