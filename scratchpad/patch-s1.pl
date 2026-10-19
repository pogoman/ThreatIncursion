#!/usr/bin/perl
# 2026-10-19 15:35, S1 (the user: "s1") after hw227: under convoys only 0 of 424 staged hammers ever filled - marines land
# in dribs (mean 256), the hammer fails at 60 d, its staging clears, ownKeep drops and the planner ships the base's marines
# to another base's default staging (Ailmar -> Ilm 2,255, -> Vannin 2,844; 42 of 46 big marine convoys base-to-base).
# (1) STAGING IS THE BASE'S COMMITMENT: a play's staging outlives the play (PlayStaging.committed, key base#commit, latest
#     wins, the live play takes it over) until a siege sails from the base (IncursionManager's expedition draw) or
#     stagingCommitDays pass with no hammer re-staging there - so ownKeep holds the marines and nothing ships them out.
# (2) THE HAMMER WAITS WHILE THE STOCK RISES: at the end of a wait window the strike fails only if the refused figure has
#     not risen by a tenth of its need since the window opened (or the refusing gate changed): councilWaitWhileRising.
# (3) A FEW STAGING HAMMERS A FACTION: invadeStagingMax (2) at a time, one a base - the donors feed two bases, not 44.
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

# ---------------------------------------------------------------- knobs
patch('src/threatinc/ThreatIncConfig.java',
"\tpublic static boolean invadeStaging()            { return b(\"threatinc_invadeStaging\", true); }\n",
"\tpublic static boolean invadeStaging()            { return b(\"threatinc_invadeStaging\", true); }\n"
. "\t/** S1 (the user, 2026-10-19 15:30): the invade pass keeps at most this many hammers staging at once a faction (one a base). */\n"
. "\tpublic static int invadeStagingMax()             { return i(\"threatinc_invadeStagingMax\"); }\n"
. "\t/** S1: a play's staging outlives the play as the base's commitment (ThreatConvoys.PlayStaging.committed) until a siege sails from the base or stagingCommitDays pass. */\n"
. "\tpublic static boolean stagingCommitment()        { return b(\"threatinc_stagingCommitment\", true); }\n"
. "\tpublic static float stagingCommitDays()          { return f(\"threatinc_stagingCommitDays\"); }\n"
. "\t/** S1: a hammer waiting on stock fails at the end of a wait window only if the refused figure has not risen (ThreatPlays.strike). */\n"
. "\tpublic static boolean councilWaitWhileRising()   { return b(\"threatinc_councilWaitWhileRising\", true); }\n");

patch('data/config/settings.json',
"\t\"threatinc_invadeStaging\": true,\n",
"\t\"threatinc_invadeStaging\": true,\n"
. "\t# S1 (2026-10-19, the user: \"s1\" after hw227 - 0 of 424 staged hammers ever filled, marines shipped base to base):\n"
. "\t# the invade pass keeps at most this many hammers staging at once a faction, one a base, so the donors feed two\n"
. "\t# bases rather than 44 (hw227a)\n"
. "\t\"threatinc_invadeStagingMax\": 2,\n"
. "\t# a play's staging is the BASE's commitment: it outlives the play (failed, neutral, its worlds taken) so the base\n"
. "\t# keeps the landing's stock (ThreatConvoys.ownKeep) and ships none of it to another base's staging, until a siege\n"
. "\t# sails from the base (IncursionManager's expedition draw) or this many days pass with no hammer re-staging there\n"
. "\t\"threatinc_stagingCommitment\": true,\n"
. "\t\"threatinc_stagingCommitDays\": 180,\n"
. "\t# a hammer waiting on stock (councilStrikeWaitDays a window) fails at the window's end only if the refused figure\n"
. "\t# (marines, armaments or points) has not risen by a tenth of its need since the window opened and the refusing gate\n"
. "\t# is the same; while the convoys fill the base it waits on. hw227a: 1,374 of 1,520 marines failed at 60 d\n"
. "\t\"threatinc_councilWaitWhileRising\": true,\n");

# ---------------------------------------------------------------- the refusal figures
patch('src/threatinc/IncursionManager.java',
"\tprotected static String lastRefusal = \"\";\n",
"\tprotected static String lastRefusal = \"\";\n"
. "\t/** The gate that refused (\"marines\", \"hand_weapons\", \"fuel\", \"points\"), what it had and what it needed - for ThreatPlays.strike's wait-while-rising (S1, 2026-10-19). */\n"
. "\tprotected static String lastRefusalGate = \"\";\n"
. "\tprotected static float lastRefusalHave = 0f, lastRefusalNeed = 0f;\n",
"\t\tlastRefusal = \"\";\n\t\tif (sizes.isEmpty() || !ThreatWarState.isAtWar(faction)) return true;\n",
"\t\tlastRefusal = lastRefusalGate = \"\";\n\t\tlastRefusalHave = lastRefusalNeed = 0f;\n\t\tif (sizes.isEmpty() || !ThreatWarState.isAtWar(faction)) return true;\n",
"\t\t\t\tlastRefusal = c + \" \" + (int) have + \" of \" + (int) (wants[i] * minMarinesFraction(faction)) + \" at \" + base.getName();\n",
"\t\t\t\tlastRefusal = c + \" \" + (int) have + \" of \" + (int) (wants[i] * minMarinesFraction(faction)) + \" at \" + base.getName();\n"
. "\t\t\t\tlastRefusalGate = c;\n\t\t\t\tlastRefusalHave = have;\n\t\t\t\tlastRefusalNeed = wants[i] * minMarinesFraction(faction);\n",
"\t\t\tlastRefusal = \"razing fuel \" + (int) fuel[2] + \" over the \" + (int) (haveFuel + fuel[2]) + \" at \" + base.getName();\n",
"\t\t\tlastRefusal = \"razing fuel \" + (int) fuel[2] + \" over the \" + (int) (haveFuel + fuel[2]) + \" at \" + base.getName();\n"
. "\t\t\tlastRefusalGate = \"fuel\";\n\t\t\tlastRefusalHave = haveFuel + fuel[2];\n\t\t\tlastRefusalNeed = fuel[2];\n",
"\t\tif (payable < minProvisions) {\n\t\t\tlastRefusal = \"pays \" + (int) payable + \" of \" + (int) minProvisions + \" points at \" + base.getName() + \" (fuel \"\n",
"\t\tif (payable < minProvisions) {\n\t\t\tlastRefusalGate = \"points\";\n\t\t\tlastRefusalHave = payable;\n\t\t\tlastRefusalNeed = minProvisions;\n"
. "\t\t\tlastRefusal = \"pays \" + (int) payable + \" of \" + (int) minProvisions + \" points at \" + base.getName() + \" (fuel \"\n",
# a siege sailing from the base ends its commitment
"\t\t\tThreatIncConfig.log(\"Expedition draw at \" + base.getName() + \": \"\n\t\t\t\t\t+ (int) drawn[0] + \"/\" + (int) lift + \" marines, \"\n",
"\t\t\t// the siege sailed: the base's staging commitment (S1) is met\n\t\t\tThreatConvoys.clearCommitment(base);\n"
. "\t\t\tThreatIncConfig.log(\"Expedition draw at \" + base.getName() + \": \"\n\t\t\t\t\t+ (int) drawn[0] + \"/\" + (int) lift + \" marines, \"\n");

# ---------------------------------------------------------------- the commitment
patch('src/threatinc/ThreatConvoys.java',
"\t\t/** The base staged (null in saves before 2026-10-19: the map key). */\n\t\tpublic String baseId;\n\t}\n",
"\t\t/** The base staged (null in saves before 2026-10-19: the map key). */\n\t\tpublic String baseId;\n"
. "\t\t/** S1 (2026-10-19): the play ended but the base keeps staging for its landing (key base#commit) - cleared when a siege sails from the base or stagingCommitDays pass. */\n"
. "\t\tpublic boolean committed;\n\t\t/** The day the commitment was made (committed). */\n\t\tpublic float commitDay;\n\t}\n",
# stageForPlay: the live play takes the commitment over
"\t\ts.factionId = factionId;\n\t\ts.baseId = base.getId();\n\t\tplayStaging().put(base.getId() + \"#\" + playId, s);\n",
"\t\ts.factionId = factionId;\n\t\ts.baseId = base.getId();\n"
. "\t\t// a live play takes the base's commitment over (S1): one landing's want, not two\n"
. "\t\tplayStaging().remove(base.getId() + \"#commit\");\n"
. "\t\tplayStaging().put(base.getId() + \"#\" + playId, s);\n",
# clearPlayStaging: commit or clear
"\t/** The play's bases stage as the faction's sieges would again. */\n\tpublic static void clearPlayStaging(String playId) {\n\t\tif (playId == null) return;\n\t\tboolean any = false;\n\t\tfor (java.util.Iterator<PlayStaging> it = playStaging().values().iterator(); it.hasNext();) {\n\t\t\tif (playId.equals(it.next().playId)) {\n\t\t\t\tit.remove();\n\t\t\t\tany = true;\n\t\t\t}\n\t\t}\n\t\tif (any) forgetStagingTargets();\n\t}\n",
"\t/** The play's bases stage as the faction's sieges would again. */\n\tpublic static void clearPlayStaging(String playId) {\n\t\tclearPlayStaging(playId, false);\n\t}\n\n"
. "\t/**\n\t * As above; with {\@code commit} (a play that ended without its siege sailing) and stagingCommitment on, the base\n"
. "\t * keeps the staging as ITS commitment (S1, the user 2026-10-19: hw227a shipped Ailmar's 2,255 marines to Ilm the week\n"
. "\t * its hammer failed): keyed base#commit, the latest replacing an older one, until {\@link #clearCommitment} or\n"
. "\t * stagingCommitDays pass ({\@link #siegeStock}).\n\t */\n"
. "\tpublic static void clearPlayStaging(String playId, boolean commit) {\n\t\tif (playId == null) return;\n\t\tboolean any = false;\n"
. "\t\tjava.util.List<PlayStaging> commits = new ArrayList<PlayStaging>();\n"
. "\t\tfor (java.util.Iterator<PlayStaging> it = playStaging().values().iterator(); it.hasNext();) {\n"
. "\t\t\tPlayStaging s = it.next();\n\t\t\tif (!playId.equals(s.playId)) continue;\n\t\t\tit.remove();\n\t\t\tany = true;\n"
. "\t\t\tif (commit && ThreatIncConfig.stagingCommitment() && s.wants != null && s.baseId != null) commits.add(s);\n\t\t}\n"
. "\t\tfor (PlayStaging s : commits) {\n\t\t\ts.playId = null;\n\t\t\ts.committed = true;\n\t\t\ts.commitDay = ThreatPosture.today();\n"
. "\t\t\tplayStaging().put(s.baseId + \"#commit\", s);\n"
. "\t\t\tMarketAPI b = Global.getSector().getEconomy().getMarket(s.baseId);\n"
. "\t\t\tThreatIncConfig.log(\"Staging: \" + (b != null ? b.getName() : s.baseId) + \" keeps its commitment to the landing (\"\n"
. "\t\t\t\t\t+ (int) s.wants[0] + \" marines, \" + (int) s.wants[1] + \" armaments, \" + (int) s.wants[2] + \" fuel, \" + (int) s.wants[3]\n"
. "\t\t\t\t\t+ \" supplies\" + (s.factionId != null ? \", \" + s.factionId : \"\") + \") after play \" + playId);\n\t\t}\n"
. "\t\tif (any) forgetStagingTargets();\n\t}\n\n"
. "\t/** The base's commitment is met or void (a siege sailed from it, S1): it stages as its faction's sieges would again. */\n"
. "\tpublic static void clearCommitment(MarketAPI base) {\n\t\tif (base == null) return;\n"
. "\t\tif (playStaging().remove(base.getId() + \"#commit\") != null) {\n"
. "\t\t\tThreatIncConfig.log(\"Staging: \" + base.getName() + \" met its commitment - a siege sailed\");\n\t\t\tforgetStagingTargets();\n\t\t}\n\t}\n",
# siegeStock: an expired commitment is dropped
"\t\tList<PlayStaging> plays = playStagingsOf(base);\n\t\tboolean anyPlay = false;\n\t\tfor (PlayStaging play : plays) anyPlay |= play.wants != null;\n",
"\t\tList<PlayStaging> plays = playStagingsOf(base);\n"
. "\t\t// a commitment no hammer re-staged within stagingCommitDays lapses (S1): the marines are the faction's again\n"
. "\t\tfor (java.util.Iterator<PlayStaging> it = plays.iterator(); it.hasNext();) {\n\t\t\tPlayStaging s = it.next();\n"
. "\t\t\tif (s.committed && ThreatPosture.today() - s.commitDay > Math.max(1f, ThreatIncConfig.stagingCommitDays())) {\n"
. "\t\t\t\tplayStaging().remove(base.getId() + \"#commit\");\n\t\t\t\tit.remove();\n"
. "\t\t\t\tThreatIncConfig.log(\"Staging: \" + base.getName() + \"'s commitment lapsed after \" + (int) ThreatIncConfig.stagingCommitDays() + \" d with no hammer staged there\");\n"
. "\t\t\t}\n\t\t}\n"
. "\t\tboolean anyPlay = false;\n\t\tfor (PlayStaging play : plays) anyPlay |= play.wants != null;\n");

# ---------------------------------------------------------------- the plays
patch('src/threatinc/ThreatPlays.java',
# Play fields
"\t\t/** Days the strike has waited for stock to pay a siege (0 on an older save: not yet waited). */\n\t\tpublic float payWaited;\n",
"\t\t/** Days the strike has waited for stock to pay a siege (0 on an older save: not yet waited). */\n\t\tpublic float payWaited;\n"
. "\t\t/** S1 (2026-10-19): the gate that refused when the wait window opened and what it had then (null: no window open) - the strike fails only if the figure has not risen. */\n"
. "\t\tpublic String refusedGate;\n\t\tpublic float refusedHave;\n",
# end(): commit the staging
"\t\tplays().remove(pl.id);\n\t\tThreatConvoys.clearPlayStaging(pl.id);\n\t\t// a muster that never went in goes home, provisions back in full\n",
"\t\tplays().remove(pl.id);\n\t\t// a hammer that never sailed its siege leaves the base its commitment (S1)\n"
. "\t\tThreatConvoys.clearPlayStaging(pl.id, HAMMER.equals(pl.type) && !pl.sieged);\n\t\t// a muster that never went in goes home, provisions back in full\n",
# strike(): wait while rising
"\t\t\tfloat wait = Math.max(0f, ThreatIncConfig.councilStrikeWaitDays());\n\t\t\tif (pl.payWaited >= wait) {\n\t\t\t\tstandDownForces(pl, \"no siege paid\");\n",
"\t\t\tfloat wait = Math.max(0f, ThreatIncConfig.councilStrikeWaitDays());\n"
. "\t\t\tif (pl.refusedGate == null) {\n\t\t\t\tpl.refusedGate = IncursionManager.lastRefusalGate;\n\t\t\t\tpl.refusedHave = IncursionManager.lastRefusalHave;\n\t\t\t}\n"
. "\t\t\tif (pl.payWaited >= wait && ThreatIncConfig.councilWaitWhileRising() && stockRising(pl)) {\n"
. "\t\t\t\t// S1: the convoys are filling the base - another window (hw227a: 1,374 of 1,520 marines failed at 60 d)\n"
. "\t\t\t\tphase(pl, MUSTER, why + \"; stock rising (\" + IncursionManager.lastRefusal + \", was \" + pl.refusedGate + \" \"\n"
. "\t\t\t\t\t\t+ (int) pl.refusedHave + \" at the window's start), waits another \" + (int) wait + \" d\", today);\n"
. "\t\t\t\tpl.refusedGate = IncursionManager.lastRefusalGate;\n\t\t\t\tpl.refusedHave = IncursionManager.lastRefusalHave;\n"
. "\t\t\t\tpl.payWaited = 1f;\n\t\t\t\tpl.phaseDue = today + 1f;\n\t\t\t\treturn;\n\t\t\t}\n"
. "\t\t\tif (pl.payWaited >= wait) {\n\t\t\t\tstandDownForces(pl, \"no siege paid\");\n",
# the helper, before stagingHammerAt
"\t/** Whether a hammer of the faction is still staging or waiting on stock AT THE BASE",
"\t/** The share of the refused need the figure must have risen by over a wait window for the hammer to wait on (S1). */\n"
. "\tprotected static final float WAIT_RISE = 0.1f;\n\n"
. "\t/** Whether the stock a waiting hammer was refused on has risen since its window opened: the gate changed (one passed), or the figure rose by WAIT_RISE of the need (S1, 2026-10-19). */\n"
. "\tprotected static boolean stockRising(Play pl) {\n"
. "\t\tif (pl.refusedGate == null || IncursionManager.lastRefusalGate == null || IncursionManager.lastRefusalGate.isEmpty()) return false;\n"
. "\t\tif (!pl.refusedGate.equals(IncursionManager.lastRefusalGate)) return true;\n"
. "\t\treturn IncursionManager.lastRefusalHave >= pl.refusedHave + WAIT_RISE * Math.max(1f, IncursionManager.lastRefusalNeed);\n\t}\n\n"
. "\t/** The hammers of the faction staging or waiting on stock (PREPARE / MUSTER, no siege sailed), for invadeStagingMax (S1). */\n"
. "\tprotected static int stagingHammers(String fid) {\n\t\tint n = 0;\n\t\tfor (Play pl : plays().values()) {\n"
. "\t\t\tif (!fid.equals(pl.factionId) || !HAMMER.equals(pl.type) || pl.sieged) continue;\n"
. "\t\t\tif (PREPARE.equals(pl.phase) || MUSTER.equals(pl.phase)) n++;\n\t\t}\n\t\treturn n;\n\t}\n\n"
. "\t/** Whether a hammer of the faction is still staging or waiting on stock AT THE BASE",
# the invade pass: a few a faction, one a base
"\t\tint staged = 0;\n\t\tif (!noBase.isEmpty() && ThreatIncConfig.invadeStaging()) {\n\t\t\tjava.util.Set<String> stagedAt = new java.util.HashSet<String>();\n\t\t\tfor (MarketAPI w : hives) {\n",
"\t\tint staged = 0;\n\t\tif (!noBase.isEmpty() && ThreatIncConfig.invadeStaging()) {\n\t\t\tjava.util.Set<String> stagedAt = new java.util.HashSet<String>();\n"
. "\t\t\t// S1 (2026-10-19): a few staging hammers a faction at once (invadeStagingMax), one a base - hw227a staged at 44\n"
. "\t\t\t// bases and the donors fed none of them\n"
. "\t\t\tint staging = stagingHammers(fid);\n\t\t\tint max = Math.max(1, ThreatIncConfig.invadeStagingMax());\n"
. "\t\t\tfor (MarketAPI w : hives) {\n\t\t\t\tif (staging + staged >= max) break;\n");
print "all patched\n";
