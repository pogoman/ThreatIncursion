#!/usr/bin/perl
# patch-siege-commit.pl (2026-10-20 13:10, the user: "you dont have to block sieges while structures are being built
# ... its dependent on worlds under attack ... only recall sieges that have only just sailed. if there are 30% or
# more to their destination they should commit as offence is still a form of defence"):
#  1. HOME FIRST off by default (threatinc_homeFirst false) - the offence gate is HOME UNDER ATTACK alone.
#  2. IncursionManager.recallSiegesOf skips a siege whose lead fleet is recallSiegeMaxProgress (0.3) or more of
#     the way from its base to the target system; it commits. Knob threatinc_recallSiegeMaxProgress.
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
  q~	/** HOME FIRST (2026-10-20): a mobilised faction holds CONSOLIDATE, founds nothing and plans no play until its core colonies' military ladders and yards are complete. */
	public static boolean homeFirst()                { return b("threatinc_homeFirst", true); }~,
  q~	/** HOME FIRST (2026-10-20): a mobilised faction holds CONSOLIDATE, founds nothing and plans no play until its core colonies' military ladders and yards are complete. OFF since 13:10 (the user: the offence waits on worlds under attack, not on structures - HOME UNDER ATTACK is the gate). */
	public static boolean homeFirst()                { return b("threatinc_homeFirst", false); }~,
  q~	public static boolean homeUnderAttack()          { return b("threatinc_homeUnderAttack", true); }~,
  q~	public static boolean homeUnderAttack()          { return b("threatinc_homeUnderAttack", true); }
	/** A siege this far (0-1) along its voyage from base to target commits when home comes under attack; under it, it turns home (the user 2026-10-20: "offence is still a form of defence and time wasted is a net loss"). */
	public static float recallSiegeMaxProgress()     { return f("threatinc_recallSiegeMaxProgress"); }~,
);

patch("data/config/settings.json",
  q~	# paced by the stockpiles alone. homeFirstMaxDays ends a hold the stock never pays off (0 = never) - a hatch.
	"threatinc_homeFirst": true,~,
  q~	# paced by the stockpiles alone. homeFirstMaxDays ends a hold the stock never pays off (0 = never) - a hatch.
	# OFF 2026-10-20 13:10 (the user: "you dont have to block sieges while structures are being built ... its
	# dependent on worlds under attack"): hw248 held the big factions 900 d and the offence never recovered.
	"threatinc_homeFirst": false,~,
  q~	"threatinc_homeUnderAttack": true,
	"threatinc_reliefMarinesOnlyIfEnough": true,~,
  q~	"threatinc_homeUnderAttack": true,
	"threatinc_reliefMarinesOnlyIfEnough": true,
	# A running siege turns home only if its lead fleet is under this fraction of the way from its base to the
	# target system; further along it commits (the user 2026-10-20: "offence is still a form of defence and time
	# wasted is a net loss" - hw248b recalled 17 of 19 sieges).
	"threatinc_recallSiegeMaxProgress": 0.3,~,
);

patch("src/threatinc/IncursionManager.java",
  q~	 * relief pass while ThreatGroundFronts.homeUnderAttack holds; an expedition already ending is left alone.
	 * hw242: the humans' sieges sailed on (444-1,285 a game) while their worlds fell under a swarm orbit the relief
	 * fleets were too few to break.
	 */
	public static int recallSiegesOf(String factionId, String why) {
		if (factionId == null) return 0;
		int n = 0;
		for (Object curr : getPurgeList()) {
			if (!(curr instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI purge = (ThreatPurgeFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.isAborted() || purge.getFaction() == null) continue;
			if (!factionId.equals(purge.getFaction().getId())) continue;
			com.fs.starfarer.api.campaign.StarSystemAPI system = purge.targetSystem();
			purge.setFailedButNotDefeated(true);~,
  q~	 * relief pass while ThreatGroundFronts.homeUnderAttack holds; an expedition already ending is left alone.
	 * hw242: the humans' sieges sailed on (444-1,285 a game) while their worlds fell under a swarm orbit the relief
	 * fleets were too few to break. Only a siege that has just sailed turns home: one recallSiegeMaxProgress or
	 * more of the way to its target commits (the user 2026-10-20: "offence is still a form of defence and time
	 * wasted is a net loss" - hw248b recalled 17 of its 19 sieges), logged quietly once a siege.
	 */
	public static int recallSiegesOf(String factionId, String why) {
		if (factionId == null) return 0;
		int n = 0;
		float commitAt = ThreatIncConfig.recallSiegeMaxProgress();
		for (Object curr : getPurgeList()) {
			if (!(curr instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI purge = (ThreatPurgeFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.isAborted() || purge.getFaction() == null) continue;
			if (!factionId.equals(purge.getFaction().getId())) continue;
			com.fs.starfarer.api.campaign.StarSystemAPI system = purge.targetSystem();
			float progress = siegeProgress(purge);
			if (progress >= commitAt) {
				ThreatIncConfig.logQuiet("siege-commits:" + factionId + ":" + (system != null ? system.getId() : "hive"),
						"Home under attack: " + factionId + " commits its siege of "
						+ (system != null ? system.getName() : "a hive") + " - " + (int) (progress * 100f) + "% of the way");
				continue;
			}
			purge.setFailedButNotDefeated(true);~,
  q~	/** Factions with a siege expedition still running against a colony of the system. */
	public static java.util.Set<String> siegeFactionsIn(String systemId) {~,
  q~	/**
	 * How far along its voyage a siege expedition is, 0 (nothing spawned, or still at its base) to 1 (a fleet in
	 * the target system), by its lead fleet's hyperspace position between the base and the target system.
	 */
	static float siegeProgress(ThreatPurgeFGI purge) {
		com.fs.starfarer.api.campaign.StarSystemAPI system = purge.targetSystem();
		if (system == null || !purge.isSpawnedFleets() || purge.getFleets() == null) return 0f;
		MarketAPI base = purge.sourceBase();
		org.lwjgl.util.vector.Vector2f from = base != null ? base.getLocationInHyperspace() : null;
		org.lwjgl.util.vector.Vector2f to = system.getLocation();
		if (from == null || to == null) return 0f;
		float whole = Misc.getDistance(from, to);
		float best = 0f;
		for (CampaignFleetAPI fleet : purge.getFleets()) {
			if (fleet == null || !fleet.isAlive()) continue;
			if (fleet.getContainingLocation() == system) return 1f;
			if (whole <= 1f) return 1f;
			org.lwjgl.util.vector.Vector2f at = fleet.getLocationInHyperspace();
			if (at == null) continue;
			float p = 1f - Misc.getDistance(at, to) / whole;
			best = Math.max(best, Math.max(0f, Math.min(1f, p)));
		}
		return best;
	}

	/** Factions with a siege expedition still running against a colony of the system. */
	public static java.util.Set<String> siegeFactionsIn(String systemId) {~,
);
print "done\n";
