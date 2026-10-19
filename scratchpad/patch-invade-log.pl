#!/usr/bin/perl
# 2026-10-19 11:15: hw217a's Hegemony sat on 39k of 42k FP free with 29 invasion plays in ~430 weekly passes and
# nothing in the log says why a pass started nothing. One summary line per pass (weekly per faction): clusters in
# reach, with its own strike, without a report, hives open of known, plays started, and the systems with no paying
# base by the reason the last base was refused for (payingBase.lastWhy).
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

patch('src/threatinc/ThreatPlays.java',
# candidates: count what the pass saw
"\t\tList<MarketAPI> ready = new ArrayList<MarketAPI>(), recent = new ArrayList<MarketAPI>();\n\t\tfor (ThreatWarCouncil.Cluster k : p.clusters) {\n\t\t\tif (!k.inReach() || strikingAt(fid, k.systemId)) continue;\n\t\t\t// a siege is sized by the faction's report of the system (strike)\n\t\t\tif (ThreatIntel.report(observer, k.systemId) == null) continue;\n\t\t\tfor (MarketAPI w : liveTargets(faction, k.hiveIds)) {\n\t\t\t\t(IncursionManager.onPurgeCooldown(observer, w) ? recent : ready).add(w);\n\t\t\t}\n\t\t}\n",
"\t\tList<MarketAPI> ready = new ArrayList<MarketAPI>(), recent = new ArrayList<MarketAPI>();\n\t\tint inReach = 0, ownStrike = 0, noReport = 0, known = 0;\n\t\tfor (ThreatWarCouncil.Cluster k : p.clusters) {\n\t\t\tif (!k.inReach()) continue;\n\t\t\tinReach++;\n\t\t\tif (strikingAt(fid, k.systemId)) {\n\t\t\t\townStrike++;\n\t\t\t\tcontinue;\n\t\t\t}\n\t\t\t// a siege is sized by the faction's report of the system (strike)\n\t\t\tif (ThreatIntel.report(observer, k.systemId) == null) {\n\t\t\t\tnoReport++;\n\t\t\t\tcontinue;\n\t\t\t}\n\t\t\tknown += k.hiveIds.size();\n\t\t\tfor (MarketAPI w : liveTargets(faction, k.hiveIds)) {\n\t\t\t\t(IncursionManager.onPurgeCooldown(observer, w) ? recent : ready).add(w);\n\t\t\t}\n\t\t}\n",
# the start loop: tally the systems with no paying base by reason, then one line a pass
"\t\t\tMarketAPI base = payingBase(k, faction, s, w);\n\t\t\tif (base == null) continue;\n\t\t\tstarted.add(k.systemId);\n",
"\t\t\tMarketAPI base = payingBase(k, faction, s, w);\n\t\t\tif (base == null) {\n\t\t\t\tif (noBase.add(k.systemId)) {\n\t\t\t\t\tInteger n = noBaseWhy.get(lastWhy);\n\t\t\t\t\tnoBaseWhy.put(lastWhy, n == null ? 1 : n + 1);\n\t\t\t\t}\n\t\t\t\tcontinue;\n\t\t\t}\n\t\t\tstarted.add(k.systemId);\n",
"\t\tjava.util.Set<String> started = new java.util.HashSet<String>();\n\t\tfor (MarketAPI w : hives) {\n",
"\t\tjava.util.Set<String> started = new java.util.HashSet<String>();\n\t\tjava.util.Set<String> noBase = new java.util.HashSet<String>();\n\t\tjava.util.Map<String, Integer> noBaseWhy = new java.util.LinkedHashMap<String, Integer>();\n\t\tfor (MarketAPI w : hives) {\n",
"\t\t\t\tend(pl, NEUTRAL, \"its siege could not be paid after all\", today);\n\t\t\t}\n\t\t}\n\t}\n",
"\t\t\t\tend(pl, NEUTRAL, \"its siege could not be paid after all\", today);\n\t\t\t}\n\t\t}\n\t\t// one line a pass (hw217a, 2026-10-19: the Hegemony sat on 39k FP free and nothing said why its passes started nothing)\n\t\tThreatIncConfig.log(\"Council \" + fid + \" invade pass: \" + inReach + \" clusters in reach (\" + ownStrike\n\t\t\t\t+ \" with its own strike, \" + noReport + \" without a report), \" + hives.size() + \" of \" + known\n\t\t\t\t+ \" hives open, started \" + started.size()\n\t\t\t\t+ (noBase.isEmpty() ? \"\" : \", no paying base in \" + noBase.size() + \": \" + noBaseWhy));\n\t}\n",
# payingBase says why the last base was refused
"\tprotected static MarketAPI payingBase(ThreatWarCouncil.Cluster k, FactionAPI faction, StarSystemAPI s, MarketAPI w) {\n\t\tList<MarketAPI> one = java.util.Collections.singletonList(w);\n\t\tfloat need = IncursionManager.siegeRaidStrNeeded(one) * IncursionManager.minMarinesFraction(faction);\n\t\tfor (MarketAPI m : k.bases) {\n\t\t\tif (m == null || !faction.getId().equals(m.getFactionId()) || !m.isInEconomy()) continue;\n\t\t\t// its own front takes the world before a siege from here could arrive\n\t\t\tif (IncursionManager.frontFinishesFirst(m, faction, s, w)) continue;\n\t\t\t// the marines first: the cheap read, and what most landings are short of\n\t\t\tif (IncursionManager.siegeMarinesPooled(m, faction, s) < need) continue;\n\t\t\tList<Integer> sizes = IncursionManager.siegeSizesFor(m, faction, one, 0f, NO_RAZE);\n\t\t\tif (!sizes.isEmpty() && IncursionManager.siegeCanPay(m, faction, s, one, sizes, NO_RAZE)) return m;\n\t\t}\n\t\treturn null;\n\t}\n",
"\t/** Why payingBase refused its last base (for the pass's summary line). */\n\tprotected static String lastWhy = \"no base of its own\";\n\n\tprotected static MarketAPI payingBase(ThreatWarCouncil.Cluster k, FactionAPI faction, StarSystemAPI s, MarketAPI w) {\n\t\tList<MarketAPI> one = java.util.Collections.singletonList(w);\n\t\tfloat need = IncursionManager.siegeRaidStrNeeded(one) * IncursionManager.minMarinesFraction(faction);\n\t\tlastWhy = \"no base of its own\";\n\t\tfor (MarketAPI m : k.bases) {\n\t\t\tif (m == null || !faction.getId().equals(m.getFactionId()) || !m.isInEconomy()) continue;\n\t\t\t// its own front takes the world before a siege from here could arrive\n\t\t\tif (IncursionManager.frontFinishesFirst(m, faction, s, w)) {\n\t\t\t\tlastWhy = \"its front finishes first\";\n\t\t\t\tcontinue;\n\t\t\t}\n\t\t\t// the marines first: the cheap read, and what most landings are short of\n\t\t\tif (IncursionManager.siegeMarinesPooled(m, faction, s) < need) {\n\t\t\t\tlastWhy = \"marines\";\n\t\t\t\tcontinue;\n\t\t\t}\n\t\t\tList<Integer> sizes = IncursionManager.siegeSizesFor(m, faction, one, 0f, NO_RAZE);\n\t\t\tif (sizes.isEmpty()) {\n\t\t\t\tlastWhy = \"no siege size\";\n\t\t\t\tcontinue;\n\t\t\t}\n\t\t\tif (IncursionManager.siegeCanPay(m, faction, s, one, sizes, NO_RAZE)) return m;\n\t\t\tlastWhy = \"cannot pay\";\n\t\t}\n\t\treturn null;\n\t}\n");
print "all patched\n";
