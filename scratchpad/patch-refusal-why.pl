#!/usr/bin/perl
# 2026-10-19 14:50, after hw225 (convoys only, allied staging): hammers 215 / 142 started, 68 / 25 struck, 120 / 104 failed
# "no siege the pools pay in 30 d" with nothing saying what was short (siegeCanPay is a bare boolean) - and the invade
# pass staged ONE hammer a faction at a time (my gate of 14:05), which with a 60-day fill-or-fail made sieges sailed
# 76 vs hw219a's 263. (1) IncursionManager.lastRefusal names the gate that said no (marines / armaments H of W, razing
# fuel, or "pays P of M points (fuel F, supplies S, hulls free H)"), and the hammer's MUSTER and FAILURE lines carry it.
# (2) The staging gate is per BASE (stagingHammerAt): the stock is the base's, so each base may stage one hammer.
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

patch('src/threatinc/IncursionManager.java',
"\t/** As above, razing the worlds in {\@code razeGiven} rather than those the faction's navy would pick (null: razeWorlds) - a war council play's siege (ThreatPlays). */\n\tprotected static boolean siegeCanPay(MarketAPI base, FactionAPI faction, StarSystemAPI system,\n\t\t\tjava.util.List<MarketAPI> targets, java.util.List<Integer> sizes, java.util.Set<String> razeGiven) {\n\t\tif (sizes.isEmpty() || !ThreatWarState.isAtWar(faction)) return true;\n",
"\t/** Why the last siegeCanPay said no (for the plays' wait and failure lines, 2026-10-19): the gate and its figures; \"\" after a yes. */\n\tprotected static String lastRefusal = \"\";\n\n\t/** As above, razing the worlds in {\@code razeGiven} rather than those the faction's navy would pick (null: razeWorlds) - a war council play's siege (ThreatPlays). */\n\tprotected static boolean siegeCanPay(MarketAPI base, FactionAPI faction, StarSystemAPI system,\n\t\t\tjava.util.List<MarketAPI> targets, java.util.List<Integer> sizes, java.util.Set<String> razeGiven) {\n\t\tlastRefusal = \"\";\n\t\tif (sizes.isEmpty() || !ThreatWarState.isAtWar(faction)) return true;\n",
"\t\t\tif (have < wants[i] * minMarinesFraction(faction)) return false;\n",
"\t\t\tif (have < wants[i] * minMarinesFraction(faction)) {\n\t\t\t\tlastRefusal = c + \" \" + (int) have + \" of \" + (int) (wants[i] * minMarinesFraction(faction)) + \" at \" + base.getName();\n\t\t\t\treturn false;\n\t\t\t}\n",
"\t\tif (haveFuel < 0f) return false;\n",
"\t\tif (haveFuel < 0f) {\n\t\t\tlastRefusal = \"razing fuel \" + (int) fuel[2] + \" over the \" + (int) (haveFuel + fuel[2]) + \" at \" + base.getName();\n\t\t\treturn false;\n\t\t}\n",
"\t\t\tpayable = Math.min(payable, hullPayable);\n\t\t}\n\t\treturn payable >= minProvisions;\n",
"\t\t\tpayable = Math.min(payable, hullPayable);\n\t\t}\n\t\tif (payable < minProvisions) {\n\t\t\tlastRefusal = \"pays \" + (int) payable + \" of \" + (int) minProvisions + \" points at \" + base.getName() + \" (fuel \"\n\t\t\t\t\t+ (int) haveFuel + \" for \" + (int) fuelPerPoint + \" a point, supplies \"\n\t\t\t\t\t+ (int) Math.max(0f, siegePooled(base, provisionPool, com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES)\n\t\t\t\t\t\t\t- ThreatFactionStock.yardWant(faction.getId())) + \" for \" + (int) suppliesPerPoint + \" a point\"\n\t\t\t\t\t+ (ThreatHulls.enabled() ? \", hulls free \" + (int) ThreatHulls.freeFP(faction.getId()) + \" FP\" : \"\") + \")\";\n\t\t}\n\t\treturn payable >= minProvisions;\n");

patch('src/threatinc/ThreatPlays.java',
"\t\t\t\tend(pl, FAILURE, why + \"; no siege the pools pay in \" + (int) pl.payWaited + \" d (\" + (int) faced\n\t\t\t\t\t\t+ \" FP reported over the strongest world)\", today);\n",
"\t\t\t\tend(pl, FAILURE, why + \"; no siege the pools pay in \" + (int) pl.payWaited + \" d (\" + (int) faced\n\t\t\t\t\t\t+ \" FP reported over the strongest world; last refusal: \" + IncursionManager.lastRefusal + \")\", today);\n",
"\t\t\t\tphase(pl, MUSTER, why + \"; no siege the pools pay yet (\" + (int) faced\n\t\t\t\t\t\t+ \" FP reported over the strongest world), waits up to \" + (int) wait + \" d for stock\", today);\n",
"\t\t\t\tphase(pl, MUSTER, why + \"; no siege the pools pay yet (\" + (int) faced\n\t\t\t\t\t\t+ \" FP reported over the strongest world; refused: \" + IncursionManager.lastRefusal + \"), waits up to \" + (int) wait + \" d for stock\", today);\n",
# the staging gate per base: the stock is the base's
"\t/** Whether a hammer of the faction is still staging or waiting on stock (PREPARE / MUSTER, no siege sailed): the invade pass stages one at a time. */\n\tprotected static boolean stagingHammer(String fid) {\n\t\tfor (Play pl : plays().values()) {\n\t\t\tif (!fid.equals(pl.factionId) || !HAMMER.equals(pl.type) || pl.sieged) continue;\n",
"\t/** Whether a hammer of the faction is still staging or waiting on stock AT THE BASE (PREPARE / MUSTER, no siege sailed): the invade pass stages one a base at a time (per faction until hw225, 2026-10-19 14:50: sieges sailed 76 vs 263). */\n\tprotected static boolean stagingHammerAt(String fid, String baseId) {\n\t\tfor (Play pl : plays().values()) {\n\t\t\tif (!fid.equals(pl.factionId) || !HAMMER.equals(pl.type) || pl.sieged || baseId == null || !baseId.equals(pl.baseId)) continue;\n",
"\t\tint staged = 0;\n\t\tif (!noBase.isEmpty() && ThreatIncConfig.invadeStaging() && !stagingHammer(fid)) {\n\t\t\tfor (MarketAPI w : hives) {\n\t\t\t\tStarSystemAPI s = w.getStarSystem();\n\t\t\t\tThreatWarCouncil.Cluster k = s != null ? p.cluster(s.getId()) : null;\n\t\t\t\tif (k == null || !noBase.contains(k.systemId)) continue;\n\t\t\t\tString why = whyOf.get(k.systemId);\n\t\t\t\tif (!\"marines\".equals(why) && !\"cannot pay\".equals(why)) continue;\n\t\t\t\tPlay pl = startHammer(c, faction, p, k, today, random, \"invades \" + w.getName()\n\t\t\t\t\t\t+ \", staged: no base in reach holds its landing's stock today (\" + why + \")\", null);\n\t\t\t\tif (pl != null) {\n\t\t\t\t\tstaged++;\n\t\t\t\t\tbreak;\n\t\t\t\t}\n\t\t\t}\n\t\t}\n",
"\t\tint staged = 0;\n\t\tif (!noBase.isEmpty() && ThreatIncConfig.invadeStaging()) {\n\t\t\tjava.util.Set<String> stagedAt = new java.util.HashSet<String>();\n\t\t\tfor (MarketAPI w : hives) {\n\t\t\t\tStarSystemAPI s = w.getStarSystem();\n\t\t\t\tThreatWarCouncil.Cluster k = s != null ? p.cluster(s.getId()) : null;\n\t\t\t\tif (k == null || !noBase.contains(k.systemId) || started.contains(k.systemId)) continue;\n\t\t\t\tString why = whyOf.get(k.systemId);\n\t\t\t\tif (!\"marines\".equals(why) && !\"cannot pay\".equals(why)) continue;\n\t\t\t\t// one staging hammer a base: the stock it waits for is the base's\n\t\t\t\tMarketAPI b = baseOf(k, fid);\n\t\t\t\tif (b == null || stagedAt.contains(b.getId()) || stagingHammerAt(fid, b.getId())) continue;\n\t\t\t\tPlay pl = startHammer(c, faction, p, k, today, random, \"invades \" + w.getName()\n\t\t\t\t\t\t+ \", staged: no base in reach holds its landing's stock today (\" + why + \")\", null);\n\t\t\t\tif (pl != null) {\n\t\t\t\t\tstaged++;\n\t\t\t\t\tstagedAt.add(b.getId());\n\t\t\t\t\tstarted.add(k.systemId);\n\t\t\t\t}\n\t\t\t}\n\t\t}\n");
print "all patched\n";
