use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
my $mem = 'C:/Users/zuzam/.claude/projects/C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion/memory';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }

append("$root/docs/facts.md", <<'F');
- **hw223 SCRATCHED at d70 (2026-10-19 14:15): the LunaLib store kept the siege pools ON.** `threatinc_siegePoolMarines` / `siegePoolProvisions` have `LunaSettings.csv` rows, so `ThreatIncConfig.b` reads them from the LunaLib store (`saves/common/LunaSettings/threatinc.json.data`, copied into every clone by `sbs.ps1`), not from `settings.json` - 219 / 198 `Expedition ... pooled for` lines in hw223a / c, staged hammers struck the same day ("its stock is in"), 90 staged in 70 days. **Rule: flipping a knob that has a CSV row means editing the master store too** (or `-Knobs`); fixed 14:18 by setting both false in the store (backup `.pre-alliedstaging`) and in the CSV default. What hw223 did show: allied staging runs - `Madeira (persean) hosts sindrian_diktat's play ... (allied staging)` x41 / 18, `sindrian_diktat sails Cruor -> Madeira (persean's base)` x32 / 8, 0 exceptions; hw223a OceanPena 60 hives at w3,079 (forward bases 29 founded / 22 lost), hw223c Aphelion 105 at w3,175. b lost its window twice (relaunch too) - the slot-2 launch failure is not fixed by `Requeue`. Rerun hw225 (B) / hw226 (A) from 14:19.
F

append("$root/docs/game-runs-2.md", <<'R', 1);
**hw223 scratched 14:15 (2026-10-19): the LunaLib store overrode the pool knobs.** The two siege-pool knobs have LunaSettings rows, so the game read them from `saves/common/LunaSettings/threatinc.json.data` (true) rather than `settings.json` (false): 219 / 198 pooled draws, staged hammers struck at once ("its stock is in"), 90 staged in 70 days - the one-at-a-time gate never bound because nothing waited. Allied staging itself ran (41 / 18 hostings, 32 / 8 guest convoys, Diktat staging at the League's Madeira; a Diktat siege pooled Persean marines through the host, which is the pool, not the convoys). Store and CSV default set false; hw225 (B) / hw226 (A) relaunched 14:19 (run-siege-26). hw223b lost its window twice, relaunch included.
R

append("$mem/council-off-aid-convoys.md", <<'M', 1);
**14:15 hw223 SCRATCHED: LunaLib store kept siege pools ON** (knobs with a LunaSettings.csv row read the store
saves/common/LunaSettings/threatinc.json.data, copied into each clone - edit the store too, done 14:18 + CSV default).
Allied staging lines ran (41 hostings, 32 guest convoys, 0 exc). hw225 (B) / hw226 (A) from 14:19 (run-siege-26).
M
print "done\n";
