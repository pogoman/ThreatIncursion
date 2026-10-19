#!/usr/bin/perl
# 2026-10-19 10:10, after hw215's early check: every remaining "could not be paid after all" (10 / 1 / 26) was
# supplies-short at the launch - siegeCanPay does not subtract the yard hold (ThreatFactionStock.yardWant) the
# launch subtracts since YARDS FIRST (00:20); and hull lending ran in circles (independent -> church 15,
# church -> independent 12) because a faction with its own noted hull shortfall still lent.
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
"\t\tif (suppliesPerPoint > 0f) {\n\t\t\tpayable = Math.min(payable, siegePooled(base, provisionPool,\n\t\t\t\t\tcom.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES) / suppliesPerPoint);\n\t\t}\n\t\tint mustPay = 0;",
"\t\tif (suppliesPerPoint > 0f) {\n\t\t\t// net of the yard hold, as the launch reads it (YARDS FIRST; hw215's remaining refusals were all this)\n\t\t\tfloat haveSupplies = Math.max(0f, siegePooled(base, provisionPool,\n\t\t\t\t\tcom.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES)\n\t\t\t\t\t- ThreatFactionStock.yardWant(faction.getId()));\n\t\t\tpayable = Math.min(payable, haveSupplies / suppliesPerPoint);\n\t\t}\n\t\tint mustPay = 0;");

patch('src/threatinc/ThreatCoalition.java',
"\t\t\tif (helper.isHostileTo(needy) || needy.isHostileTo(helper)) gate = \"hostile\";\n\t\t\telse if (!ThreatFactionStock.hullsSurplus(helperId)) gate = \"none free\";",
"\t\t\tif (helper.isHostileTo(needy) || needy.isHostileTo(helper)) gate = \"hostile\";\n\t\t\telse if (!ThreatFactionStock.hullsSurplus(helperId)) gate = \"none free\";\n\t\t\t// a faction whose own siege waits on hulls lends none (hw215a: the Independents and the Church lent each other 27 convoys)\n\t\t\telse if (ThreatFactionStock.hullShortFP(helperId) > 0f) gate = \"short itself\";");
print "all patched\n";
