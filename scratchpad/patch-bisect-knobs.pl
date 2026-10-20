#!/usr/bin/perl
# patch-bisect-knobs.pl (2026-10-20 19:30): knobs for the two 2026-10-20 changes that had none, so the bisect
# against the 10-19 build (hw254) can turn them off without a code revert - defaults true, no behaviour change.
#  threatinc_reliefOneBody   (ThreatFleetOrders.sendRelief / ThreatCoalition.jointRelief): off = sail what was built
#  threatinc_coalitionHullCap (ThreatCoalition.capHulls): off = every sender promises its faction's whole free pool
use strict; use warnings;
my $root = "C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion";
sub patch {
  my ($rel, @pairs) = @_;
  my $p = "$root/$rel";
  open my $in, '<:raw', $p or die "$p: $!"; local $/; my $s = <$in>; close $in;
  my $crlf = $s =~ /\r\n/; $s =~ s/\r\n/\n/g;
  while (@pairs) {
    my ($old, $new) = splice @pairs, 0, 2;
    my $n = () = $s =~ /\Q$old\E/g;
    die "$rel: expected 1 match, got $n for:\n$old\n" unless $n == 1;
    $s =~ s/\Q$old\E/$new/;
  }
  $s =~ s/\n/\r\n/g if $crlf;
  open my $out, '>:raw', $p or die; print $out $s; close $out;
  print "patched $rel\n";
}

patch("src/threatinc/ThreatIncConfig.java",
  q~	public static boolean coalitionRelief()   { return b("threatinc_coalitionRelief", true); }~,
  q~	public static boolean coalitionRelief()   { return b("threatinc_coalitionRelief", true); }
	/** ONE BODY OR NONE (2026-10-20): a relief built short of the owed points stands down (ThreatFleetOrders.sendRelief, ThreatCoalition.jointRelief); off, it sails what was built. */
	public static boolean reliefOneBody()     { return b("threatinc_reliefOneBody", true); }
	/** Hull cap once a faction in joint relief (2026-10-20, ThreatCoalition.capHulls); off, every sender of a faction promises the faction's whole free pool. */
	public static boolean coalitionHullCap()  { return b("threatinc_coalitionHullCap", true); }~,
);

patch("src/threatinc/ThreatFleetOrders.java",
  q~		if (!coalitionEnough && sent > 0f && sent + 0.5f < owed) {
			standDownUnsailed(sailing, base);~,
  q~		if (ThreatIncConfig.reliefOneBody() && !coalitionEnough && sent > 0f && sent + 0.5f < owed) {
			standDownUnsailed(sailing, base);~,
);

patch("src/threatinc/ThreatCoalition.java",
  q~		if (!ThreatHulls.enabled() || factionId == null || can <= 0f) return Math.max(0f, can);
		Float l = left.get(factionId);~,
  q~		if (!ThreatHulls.enabled() || !ThreatIncConfig.coalitionHullCap() || factionId == null || can <= 0f) return Math.max(0f, can);
		Float l = left.get(factionId);~,
  q~		if (sent + 0.5f < owed) {
			for (Object[] p : parts) {
				@SuppressWarnings("unchecked") List<CampaignFleetAPI> fleets = (List<CampaignFleetAPI>) p[1];
				ThreatFleetOrders.standDownUnsailed(fleets, ((Sender) p[0]).base);~,
  q~		if (ThreatIncConfig.reliefOneBody() && sent + 0.5f < owed) {
			for (Object[] p : parts) {
				@SuppressWarnings("unchecked") List<CampaignFleetAPI> fleets = (List<CampaignFleetAPI>) p[1];
				ThreatFleetOrders.standDownUnsailed(fleets, ((Sender) p[0]).base);~,
);

patch("data/config/settings.json",
  q~	"threatinc_reliefMarinesOnlyIfEnough": false,~,
  q~	"threatinc_reliefMarinesOnlyIfEnough": false,
	# ONE BODY OR NONE for relief fleets (2026-10-20 11:05) and the hull cap once a faction in joint relief (12:21):
	# knobs added 19:30 for the bisect against the 10-19 build (hw254); off = sail what was built / promise the pool.
	"threatinc_reliefOneBody": true,
	"threatinc_coalitionHullCap": true,~,
);
print "done\n";
