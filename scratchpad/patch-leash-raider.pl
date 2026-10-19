#!/usr/bin/perl
# 2026-10-19 11:10, hw217c: 7,800+ "Reinforcement strayed from Khore ... (was: GO_TO_LOCATION -> Pair 'returning to
# the hive')" lines. A raider (ThreatRaiders.detach) keeps its GARRISON_FLAG while out; back over Pair and dry, the
# bombardment's rearm pass (ThreatGroundFronts -> ThreatColonyManager.sendToRearm) took it for Pair's own and sent it
# to Khore as a reinforcement; the raider poll then saw it home within HOME_RANGE and seated it in Pair's garrison
# list ("Raider home at Pair") - one fleet in both books, the garrison leash and the reinforcement leash re-ordering
# it against each other every frame. Three guards: sendToRearm skips raiders; the raider poll seats no fleet that is
# meanwhile tracked as a reinforcement; sendToGarrison strips the fleet from every garrison list.
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

patch('src/threatinc/ThreatColonyManager.java',
# 1. sendToRearm: a raider is the raider poll's, whatever flag it still carries
"\t\tif (mem.contains(REARM_RETURN_KEY)) return false;\n\t\t// only this hive's own: its garrison, or a reinforcement bound here (a strike or Defend passing through answers to orbitDoneFor)\n",
"\t\tif (mem.contains(REARM_RETURN_KEY)) return false;\n\t\t// a raider out or returning keeps its garrison flag but is the raider poll's (hw217c, 2026-10-19: one sent\n\t\t// to rearm was seated home by the poll too, and the two leashes fought over it 7,800 times)\n\t\tif (ThreatRaiders.isRaider(fleet)) return false;\n\t\t// only this hive's own: its garrison, or a reinforcement bound here (a strike or Defend passing through answers to orbitDoneFor)\n",
# 2. sendToGarrison: one fleet, one book
"\t\tcom.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();\n\t\tunbindLedger(fleet);\n\t\tmem.unset(Misc.FLEET_RETURNING_TO_DESPAWN);\n\t\tmem.set(REINFORCE_TARGET_KEY, target.getId());\n",
"\t\tcom.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();\n\t\tunbindLedger(fleet);\n\t\t// one fleet, one book: a reinforcement sits in no garrison list, or the garrison leash\n\t\t// drags it back against this order every frame (hw217c, 2026-10-19)\n\t\tfor (List<CampaignFleetAPI> list : ThreatIncData.garrisons().values()) list.remove(fleet);\n\t\tmem.unset(Misc.FLEET_RETURNING_TO_DESPAWN);\n\t\tmem.set(REINFORCE_TARGET_KEY, target.getId());\n");

patch('src/threatinc/ThreatRaiders.java',
# 3. the home poll seats nothing another book took meanwhile
"\t\t\t\tif (fleet.getBattle() != null) continue;\n\t\t\t\tfleet.getMemoryWithoutUpdate().unset(RAIDER_FLAG);\n\t\t\t\tall().remove(r);\n\t\t\t\tThreatIncData.garrisonsFor(home.getId()).add(fleet);\n\t\t\t\tThreatIncConfig.log(\"Raider home at \" + home.getName());\n",
"\t\t\t\tif (fleet.getBattle() != null) continue;\n\t\t\t\tfleet.getMemoryWithoutUpdate().unset(RAIDER_FLAG);\n\t\t\t\tall().remove(r);\n\t\t\t\tif (ThreatIncData.reinforcementFleets().containsKey(fleet.getId())) {\n\t\t\t\t\t// re-tasked meanwhile (sent to rearm, or as a reinforcement): that book keeps it\n\t\t\t\t\tThreatIncConfig.log(\"Raider home at \" + home.getName() + \": re-tasked meanwhile, not seated\");\n\t\t\t\t\tcontinue;\n\t\t\t\t}\n\t\t\t\tThreatIncData.garrisonsFor(home.getId()).add(fleet);\n\t\t\t\tThreatIncConfig.log(\"Raider home at \" + home.getName());\n");
print "all patched\n";
