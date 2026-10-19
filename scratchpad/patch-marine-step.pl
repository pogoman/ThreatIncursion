#!/usr/bin/perl
# 2026-10-19 13:25, THE MARINE STEP (the user's version, 13:00: "marines come from military base / high command ...
# build those buildings when short instead of more orbital works"). hw219a / hw220c: the Hegemony sat on 35k / 15k FP
# free while 1,095 / 522 of its invade-pass refusals were "marines" (a landing wants 1,100-2,700, the markets raise
# 40-77 a month each). payingBase notes the marine shortfall (ThreatFactionStock.noteMarineShort, stands SHORT_DAYS);
# while it stands the core build's pass raises the military ladder first - Patrol HQ -> Military Base -> High Command
# (one per faction), a Patrol HQ where none stands - at the largest core colony with a step left, paid now or its
# price held like a yard's; the yard step follows. Knob threatinc_marineStep.
use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
sub patch {
    my ($file, @pairs) = @_;
    my $path = "$root/$file";
    open(my $fh, '<:raw', $path) or die "open $path: $!";
    local $/; my $text = <$fh>; close $fh;
    my $crlf = ($text =~ /\r\n/) ? 1 : 0;
    $text =~ s/\r\n/\n/g if $crlf;
    while (@pairs) {
        my ($from, $to) = splice(@pairs, 0, 2);
        my $count = () = $text =~ /\Q$from\E/g;
        die "$file: expected 1 match, got $count for:\n$from\n" unless $count == 1;
        $text =~ s/\Q$from\E/$to/;
    }
    $text =~ s/\n/\r\n/g if $crlf;
    open($fh, '>:raw', $path) or die "write $path: $!";
    print $fh $text; close $fh;
    print "patched $file (crlf=$crlf)\n";
}

# 1. the faction stock remembers a marine shortfall
patch('src/threatinc/ThreatFactionStock.java',
"\t/** Whether allies may sail the faction another convoy of the stock: the last has had SHORT_DAYS to land. */\n",
"\t/**\n\t * A landing the faction's invade pass could not man (ThreatPlays.payingBase): the marines short, for the core\n\t * build to answer with the military ladder (ThreatCoreBuild.marineStep). The largest note of the window stands\n\t * SHORT_DAYS. The user, 2026-10-19: \"build those buildings when short instead of more orbital works\".\n\t */\n\tpublic static void noteMarineShort(String f, float marines) {\n\t\tif (f == null || marines <= 0f) return;\n\t\tdata().put(key(f, Commodities.MARINES, \"want\"), Math.max(marines, marineShort(f)));\n\t\tdata().put(key(f, Commodities.MARINES, \"wantAt\"), Global.getSector().getClock().getTimestamp());\n\t}\n\n\t/** Marines the faction's landings wait on (noteMarineShort); 0 once the note is older than SHORT_DAYS. */\n\tpublic static float marineShort(String f) {\n\t\tif (f == null) return 0f;\n\t\tObject n = data().get(key(f, Commodities.MARINES, \"want\"));\n\t\tObject at = data().get(key(f, Commodities.MARINES, \"wantAt\"));\n\t\tif (!(n instanceof Float) || !(at instanceof Long)) return 0f;\n\t\tif (Global.getSector().getClock().getElapsedDaysSince((Long) at) > SHORT_DAYS) return 0f;\n\t\treturn (Float) n;\n\t}\n\n\t/** Whether allies may sail the faction another convoy of the stock: the last has had SHORT_DAYS to land. */\n");

# 2. payingBase notes what the landing lacked
patch('src/threatinc/ThreatPlays.java',
"\t\t\tif (IncursionManager.siegeMarinesPooled(m, faction, s) < need) {\n\t\t\t\tlastWhy = \"marines\";\n\t\t\t\tcontinue;\n\t\t\t}\n",
"\t\t\tfloat pooled = IncursionManager.siegeMarinesPooled(m, faction, s);\n\t\t\tif (pooled < need) {\n\t\t\t\tlastWhy = \"marines\";\n\t\t\t\t// the core build raises the military ladder while this stands (the marine step, 2026-10-19)\n\t\t\t\tThreatFactionStock.noteMarineShort(faction.getId(), need - pooled);\n\t\t\t\tcontinue;\n\t\t\t}\n");

# 3. the core build's marine step, ahead of the yards while the shortfall stands
patch('src/threatinc/ThreatCoreBuild.java',
"\t\t\t// yards first, every pass: a yard started or held is the faction's whole pass\n\t\t\tif (yardStep(fid, sites)) continue;\n",
"\t\t\t// the marine step (the user, 2026-10-19): a faction whose landings wait on marines raises its military\n\t\t\t// ladder before another yard; a step started or held is the faction's whole pass\n\t\t\tif (marineStep(fid, sites)) continue;\n\t\t\t// yards first, every pass: a yard started or held is the faction's whole pass\n\t\t\tif (yardStep(fid, sites)) continue;\n",
"\t/** The one military step the colony lacks, by size; true when a build or upgrade started. */\n",
"\t/**\n\t * THE MARINE STEP (the user, 2026-10-19: \"marines come from military base / high command ... build those\n\t * buildings when short instead of more orbital works\"). While the faction's invade pass notes landings it\n\t * cannot man (ThreatFactionStock.marineShort, from ThreatPlays.payingBase), the pass raises the ladder that\n\t * produces marines at the largest core colony with a step left: Patrol HQ upgraded to Military Base,\n\t * Military Base to High Command (one a faction, as vanilla has it), a Patrol HQ started where none of the\n\t * three stands and a slot is free. Paid now (ThreatFrontlines.payBuild) or its price held at the colony\n\t * (ThreatFactionStock.hold, as a yard's is), ahead of the yard step. True when a step started or is held.\n\t * hw219a / hw220c: the Hegemony sat on 35k / 15k FP free while 1,095 / 522 of its refused systems were\n\t * \"marines\" - a landing wants 1,100-2,700, its markets raise 40-77 a month each.\n\t */\n\tprotected static boolean marineStep(String fid, List<MarketAPI> sites) {\n\t\tif (!ThreatIncConfig.marineStep()) return false;\n\t\tfloat short_ = ThreatFactionStock.marineShort(fid);\n\t\tif (short_ <= 0f) return false;\n\t\tboolean highCommand = hasHighCommand(fid);\n\t\tfor (MarketAPI m : sites) {\n\t\t\tIndustry hq = m.getIndustry(Industries.PATROLHQ);\n\t\t\tIndustry mil = m.getIndustry(Industries.MILITARYBASE);\n\t\t\tIndustry high = m.getIndustry(Industries.HIGHCOMMAND);\n\t\t\tif (high != null) continue;\n\t\t\tIndustry step = mil != null && !highCommand ? mil : hq;\n\t\t\tif (step != null) {\n\t\t\t\tif (step.isBuilding() || step.isUpgrading() || step.getSpec().getUpgrade() == null) continue;\n\t\t\t\tString to = step.getSpec().getUpgrade();\n\t\t\t\tThreatFactionStock.release(m);\n\t\t\t\tif (ThreatFrontlines.payBuild(m, to)) {\n\t\t\t\t\tstep.startUpgrading();\n\t\t\t\t\tstarted(fid);\n\t\t\t\t\tThreatIncConfig.log(\"Core build: \" + m.getName() + \" (\" + fid + \") upgrades \" + step.getId() + \" to \" + to\n\t\t\t\t\t\t\t+ \" (marine step: \" + (int) short_ + \" marines short) - \" + ThreatFactionStock.describe(fid));\n\t\t\t\t\treturn true;\n\t\t\t\t}\n\t\t\t\treturn holdStep(m, fid, to, short_);\n\t\t\t}\n\t\t\tif (mil == null && Misc.getNumIndustries(m) < Misc.getMaxIndustries(m)) {\n\t\t\t\tThreatFactionStock.release(m);\n\t\t\t\tif (ThreatFrontlines.startNew(m, Industries.PATROLHQ)) {\n\t\t\t\t\tstarted(fid);\n\t\t\t\t\tThreatIncConfig.log(\"Core build: \" + m.getName() + \" (\" + fid + \") builds \" + Industries.PATROLHQ\n\t\t\t\t\t\t\t+ \" (marine step: \" + (int) short_ + \" marines short) - \" + ThreatFactionStock.describe(fid));\n\t\t\t\t\treturn true;\n\t\t\t\t}\n\t\t\t\treturn holdStep(m, fid, Industries.PATROLHQ, short_);\n\t\t\t}\n\t\t}\n\t\treturn false;\n\t}\n\n\t/** Whether the faction has (or is raising) its one High Command. */\n\tprotected static boolean hasHighCommand(String fid) {\n\t\tfor (MarketAPI m : ThreatReserves.marketsOf(fid)) {\n\t\t\tif (m == null || !m.isInEconomy()) continue;\n\t\t\tif (m.getIndustry(Industries.HIGHCOMMAND) != null) return true;\n\t\t\tIndustry mil = m.getIndustry(Industries.MILITARYBASE);\n\t\t\tif (mil != null && mil.isUpgrading()) return true;\n\t\t}\n\t\treturn false;\n\t}\n\n\t/** Holds the marine step's price at the colony (ThreatFactionStock.hold) and logs it once; true (the faction waits on it). */\n\tprotected static boolean holdStep(MarketAPI m, String fid, String id, float short_) {\n\t\tfloat cost = ThreatBuildCost.supplies(id), funds = ThreatFrontlines.buildFunds(m);\n\t\tif (cost > funds) {\n\t\t\tThreatFactionStock.hold(m, id, cost - funds);\n\t\t\tThreatIncConfig.logQuiet(\"cb_hold_\" + m.getId(), \"Core build: \" + m.getName() + \" holds \"\n\t\t\t\t\t+ (int) (cost - funds) + \" supplies for a \" + id + \" (\" + fid + \" marine step, \" + (int) short_ + \" short)\");\n\t\t}\n\t\treturn true;\n\t}\n\n\t/** The one military step the colony lacks, by size; true when a build or upgrade started. */\n");

# 4. the knob
patch('src/threatinc/ThreatIncConfig.java',
"\tpublic static boolean yardsFirst()               { return b(\"threatinc_yardsFirst\", true); }\n",
"\tpublic static boolean yardsFirst()               { return b(\"threatinc_yardsFirst\", true); }\n\tpublic static boolean marineStep()               { return b(\"threatinc_marineStep\", true); }\n");
patch('data/config/settings.json',
"\t\"threatinc_yardsFirst\": true,\n",
"\t\"threatinc_yardsFirst\": true,\n\t# THE MARINE STEP (2026-10-19, the user: \"marines come from military base / high command ... build those\n\t# buildings when short instead of more orbital works\"): while a faction's invade pass notes landings it\n\t# cannot man (ThreatPlays.payingBase -> ThreatFactionStock.noteMarineShort, 30 days), its core build raises\n\t# the military ladder before another yard - Patrol HQ -> Military Base -> High Command (one a faction), a\n\t# Patrol HQ where none stands - paid or its price held. hw219a: the Hegemony 35k FP free, 1,095 of 1,360\n\t# refused systems short of marines (a landing wants 1,100-2,700; a market raises 40-77 a month)\n\t\"threatinc_marineStep\": true,\n");
print "all patched\n";
