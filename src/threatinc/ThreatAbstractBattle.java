package threatinc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.fleets.EconomyFleetRouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.OptionalFleetData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * OFF-SCREEN FIGHTS COST BOTH SIDES (2026-10-01, user's call; docs/ground-war.md
 * "Off-screen fights"). Vanilla's FGRaidAction.autoresolve charges only the
 * attacker - min(0.75, half the defence over its strength) at each world it
 * raids - and never touches the fleets that defend. So an NPC siege razed hive
 * worlds while their Defense Swarms sat it out and bound themselves to the
 * next colony: 29 razes in ng3a cost the attackers 34 FP and the hive no ship.
 *
 * <p>The defenders now take the same rule from the other side, once per
 * fight: min(0.75, half the attacker's strength over theirs), struck as ships
 * from every fleet vanilla counted in the defence (WarSimScript.getEnemyStrength:
 * the fleets of each faction with a market there hostile to the attacker, no
 * stations) and as damage on their routes not yet spawned. Both strengths are
 * read before the fight. An expedition that breaks off before vanilla's fight
 * (ThreatPurgeFGI.breaksOffAbstract) fights no one.
 */
public final class ThreatAbstractBattle {

	private ThreatAbstractBattle() {}

	/** Vanilla's cap on what one off-screen fight takes (FGRaidAction.autoresolve). */
	public static final float MAX_LOSS = 0.75f;
	/** Vanilla's loss per unit of the other side's strength over one's own. */
	public static final float LOSS_PER_RATIO = 0.5f;

	public static boolean enabled() {
		return ThreatIncConfig.abstractDefendersFight();
	}

	/**
	 * The attacker's strength in the system as vanilla's autoresolve reads it:
	 * its fleets and routes there, and its own route once expired - unless the
	 * route is still located there (a base in the target system: its return
	 * leg starts in it), which getFactionStrength already counted. pd6a's
	 * 14,900-unit saturation from Salamanca fought Yma as 30,507.
	 */
	public static float attackerStrength(FactionAPI attacker, StarSystemAPI where, RouteData route) {
		if (attacker == null || where == null) return 0f;
		float str = WarSimScript.getFactionStrength(attacker, where);
		if (route != null && route.isExpired() && route.getExtra() != null
				&& !RouteManager.getInstance().getRoutesInLocation(where).contains(route)) {
			str += route.getExtra().getStrengthModifiedByDamage();
		}
		return str;
	}

	/** The defence vanilla weighs the attacker against: the fleets of every faction with a market there hostile to it. */
	public static float defenderStrength(FactionAPI attacker, StarSystemAPI where) {
		if (attacker == null || where == null) return 0f;
		return WarSimScript.getEnemyStrength(attacker, where, false);
	}

	/** The share of its fleet points each defender loses to an attacker of {@code attackerStr} against {@code defenderStr}. */
	public static float defenderLoss(float attackerStr, float defenderStr) {
		if (attackerStr <= 0f || defenderStr <= 0f) return 0f;
		return Math.min(MAX_LOSS, LOSS_PER_RATIO * attackerStr / defenderStr);
	}

	/**
	 * One off-screen fight's other half: the defenders lose their share
	 * ({@link #defenderLoss}), and the exchange is booked - the Threat's ships
	 * lost into its hive system's loss ledger, both sides into the stance's
	 * attrition ledger (ThreatStance.noteTrend). {@code attackerLostFP} is what
	 * vanilla's half of the fight took off the attacker. Logged once.
	 */
	public static void fought(FactionAPI attacker, StarSystemAPI where, float attackerStr, float defenderStr,
			float attackerLostFP, String what) {
		fought(attacker, where, attackerStr, defenderStr, attackerLostFP, what, null);
	}

	/**
	 * As above, against the factions {@link #defenders} read before the fight:
	 * a world the attack razes leaves the system's markets before this runs,
	 * and its defenders with it - h41a's strikes on forward bases weighed
	 * 2,567 defending and struck no fleet and no route.
	 */
	public static void fought(FactionAPI attacker, StarSystemAPI where, float attackerStr, float defenderStr,
			float attackerLostFP, String what, Set<String> defenders) {
		if (!enabled() || attacker == null || where == null) return;
		float share = defenderLoss(attackerStr, defenderStr);
		java.util.Map<String, Float> byFaction = new java.util.HashMap<String, Float>();
		float[] lost = share > 0f ? strike(attacker, where, share, byFaction, defenders) : new float[4];
		book(attacker, where, lost, byFaction, attackerLostFP);
		ThreatIncConfig.log("Off-screen fight in " + where.getName() + " (" + what + "): " + attacker.getId() + " "
				+ (int) attackerStr + " vs " + (int) defenderStr + " defending (vanilla units); attacker lost "
				+ (int) Math.max(0f, attackerLostFP) + " FP, defenders " + Math.round(share * 100f) + "%: "
				+ (int) lost[0] + " Threat FP, " + (int) lost[1] + " other FP (" + (int) lost[2] + " fleets, "
				+ (int) lost[3] + " routes)");
	}

	/**
	 * The exchange booked: the Threat's ships lost into its hive system's loss
	 * ledger, both sides into the stance's attrition ledger, each faction's own
	 * into its stance. {@code lost} is {Threat FP, other FP, ...} struck from the
	 * defence, {@code attackerLostFP} what the fight cost the attacker.
	 */
	protected static void book(FactionAPI attacker, StarSystemAPI where, float[] lost,
			java.util.Map<String, Float> byFaction, float attackerLostFP) {
		boolean threatAttacks = Factions.THREAT.equals(attacker.getId());
		float attackerLost = Math.max(0f, attackerLostFP);
		float threatLost = lost[0] + (threatAttacks ? attackerLost : 0f);
		float enemyLost = lost[1] + (threatAttacks ? 0f : attackerLost);
		if (lost[0] > 0f && ThreatIncData.colonyMarkets().containsKey(where.getId())) {
			ThreatPosture.addLoss(where.getId(), lost[0]);
		}
		if (threatAttacks || lost[0] > 0f) ThreatStance.noteTrend(threatLost, enemyLost);
		// each faction's own exchange (ThreatFactionStance): a siege's against the
		// swarms it met; a strike's losses shared by those who inflicted them
		if (!threatAttacks) {
			ThreatFactionStance.noteTrend(attacker.getId(), attackerLost, lost[0]);
		} else if (lost[1] > 0f) {
			for (java.util.Map.Entry<String, Float> e : byFaction.entrySet()) {
				if (Factions.THREAT.equals(e.getKey())) continue;
				ThreatFactionStance.noteTrend(e.getKey(), e.getValue(), attackerLost * e.getValue() / lost[1]);
			}
		}
	}

	/**
	 * One day of an off-screen siege's fight over a world (ThreatPurgeFGI's
	 * daily siege), both strengths in fleet points as read when the day began:
	 * each fleet the besieger weighed there
	 * (ThreatGroundFronts.hostileFleetsNear) loses min(0.75, half the
	 * besieger's points over theirs) as ships struck ({@link #removeShare}),
	 * and the day is booked as {@link #fought} books a fight, with
	 * {@code attackerLostFP} what the day cost the besieger. Station-mode
	 * fleets and the player's are never struck (a hive has no station, and the
	 * player near the world would have spawned the expedition). The knob
	 * abstractDefendersFight off, the defenders lose nothing and nothing is
	 * booked, as {@link #fought}. Logged quietly: it runs every day of a fight.
	 * Returns the fleet points struck.
	 */
	public static float foughtDay(FactionAPI attacker, MarketAPI world, List<CampaignFleetAPI> defenders,
			float attackerFP, float defenderFP, float attackerLostFP) {
		return foughtDay(attacker, world, defenders, attackerFP, defenderFP, attackerLostFP, null, 0f);
	}

	/**
	 * As above, with {@code friends} - the besieger's own fleets at the world
	 * (its play's hunts, ThreatSoftening.playFleetsNear) - fighting beside it:
	 * {@code attackerFP} includes theirs, and each loses {@code friendShare} of
	 * its ships as the besieger's flotilla loses that share of its route, their
	 * loss added to what the day cost the attacker.
	 */
	public static float foughtDay(FactionAPI attacker, MarketAPI world, List<CampaignFleetAPI> defenders,
			float attackerFP, float defenderFP, float attackerLostFP, List<CampaignFleetAPI> friends, float friendShare) {
		if (!enabled() || attacker == null || world == null || world.getStarSystem() == null) return 0f;
		StarSystemAPI where = world.getStarSystem();
		float share = defenderLoss(attackerFP, defenderFP);
		java.util.Map<String, Float> byFaction = new java.util.HashMap<String, Float>();
		float[] lost = new float[4];
		int hunts = 0;
		float huntsLost = 0f;
		if (friendShare > 0f && friends != null && !friends.isEmpty()) {
			Random random = new Random();
			for (CampaignFleetAPI fleet : friends) {
				if (fleet == null || !fleet.isAlive() || fleet.isStationMode() || fleet.isPlayerFleet()) continue;
				hunts++;
				huntsLost += removeShare(fleet, friendShare, random);
			}
			attackerLostFP += huntsLost;
		}
		if (share > 0f && defenders != null) {
			Random random = new Random();
			for (CampaignFleetAPI fleet : defenders) {
				if (fleet == null || fleet.getFaction() == null || !fleet.isAlive()) continue;
				if (fleet.isStationMode() || fleet.isPlayerFleet()) continue;
				String fid = fleet.getFaction().getId();
				float fp = removeShare(fleet, share, random);
				lost[Factions.THREAT.equals(fid) ? 0 : 1] += fp;
				lost[2]++;
				add(byFaction, fid, fp);
			}
		}
		book(attacker, where, lost, byFaction, attackerLostFP);
		// every day logged (a siege fights a handful): pd6a's one line a month hid days 2-10
		ThreatIncConfig.log("Off-screen fight over "
				+ world.getName() + " (daily siege): " + attacker.getId() + " " + (int) attackerFP + " FP"
				+ (hunts > 0 ? " with " + hunts + " hunting fleets" : "") + " vs "
				+ (int) defenderFP + " FP; attacker lost " + (int) Math.max(0f, attackerLostFP) + " FP"
				+ (hunts > 0 ? " (hunts " + (int) huntsLost + ")" : "") + ", defenders "
				+ Math.round(share * 100f) + "%: " + (int) lost[0] + " Threat FP, " + (int) lost[1] + " other FP ("
				+ (int) lost[2] + " fleets)");
		return lost[0] + lost[1];
	}

	/**
	 * Strikes {@code share} of their fleet points from every fleet and route
	 * defending the system against the attacker, as vanilla counts them.
	 * Returns {Threat FP lost, other FP lost, fleets struck, routes struck};
	 * {@code byFaction} gets each faction's.
	 */
	protected static float[] strike(FactionAPI attacker, StarSystemAPI where, float share,
			java.util.Map<String, Float> byFaction, Set<String> defenders) {
		float[] lost = new float[4];
		if (defenders == null || defenders.isEmpty()) defenders = defendingFactions(attacker, where);
		if (defenders.isEmpty()) return lost;
		Random random = new Random();
		Set<CampaignFleetAPI> seen = new HashSet<CampaignFleetAPI>();
		for (CampaignFleetAPI fleet : new ArrayList<CampaignFleetAPI>(where.getFleets())) {
			if (fleet == null || fleet.getFaction() == null || !defenders.contains(fleet.getFaction().getId())) continue;
			if (fleet.isStationMode() || fleet.isPlayerFleet() || !fleet.isAlive()) continue;
			if (fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_TRADE_FLEET)) continue;
			if (fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_SMUGGLER)) continue;
			seen.add(fleet);
			String fid = fleet.getFaction().getId();
			float fp = removeShare(fleet, share, random);
			lost[Factions.THREAT.equals(fid) ? 0 : 1] += fp;
			lost[2]++;
			add(byFaction, fid, fp);
		}
		for (RouteData route : RouteManager.getInstance().getRoutesInLocation(where)) {
			if (route == null || route.getFactionId() == null || !defenders.contains(route.getFactionId())) continue;
			if (route.getActiveFleet() != null && seen.contains(route.getActiveFleet())) continue;
			// vanilla counts a route by its strength alone (getStrengthModifiedByDamage)
			OptionalFleetData data = route.getExtra();
			if (data == null || data.strength == null) continue;
			float had = data.damage != null ? data.damage : 0f;
			data.damage = Math.min(1f, 1f - (1f - had) * (1f - share));
			// most routes carry no fleet points, only strength - "in fleet points
			// but modified by quality and doctrine" - so that stands in for them
			float fp = (data.fp != null ? data.fp : data.strength) * (1f - had) * share;
			lost[Factions.THREAT.equals(route.getFactionId()) ? 0 : 1] += fp;
			lost[3]++;
			add(byFaction, route.getFactionId(), fp);
		}
		return lost;
	}

	protected static void add(java.util.Map<String, Float> map, String key, float v) {
		if (map == null || key == null || v <= 0f) return;
		Float had = map.get(key);
		map.put(key, (had != null ? had : 0f) + v);
	}

	/** The factions defending the system against the attacker, read before the fight for {@link #fought}. */
	public static Set<String> defenders(FactionAPI attacker, StarSystemAPI where) {
		if (attacker == null || where == null) return new HashSet<String>();
		return defendingFactions(attacker, where);
	}

	/** The factions vanilla counts in the defence: each one with a market in the system hostile to the attacker. */
	protected static Set<String> defendingFactions(FactionAPI attacker, StarSystemAPI where) {
		Set<String> out = new HashSet<String>();
		for (MarketAPI m : Misc.getMarketsInLocation(where)) {
			if (m == null || m.getFaction() == null || !m.getFaction().isHostileTo(attacker)) continue;
			if (EconomyFleetRouteManager.ENEMY_STRENGTH_CHECK_EXCLUDE_PIRATES
					&& Factions.PIRATES.equals(m.getFactionId())) continue;
			out.add(m.getFactionId());
		}
		return out;
	}

	/**
	 * Strikes ships from the fleet until {@code share} of its fleet points are
	 * gone, civilian hulls a quarter as likely as warships (vanilla's
	 * FleetFactoryV3.applyDamageToFleet weights); the ship the share ends
	 * inside goes as often as the share reaches into it, so a small fleet loses
	 * its share on average rather than every time a whole ship. A fleet left
	 * with nothing is destroyed. Returns the fleet points struck.
	 */
	protected static float removeShare(CampaignFleetAPI fleet, float share, Random random) {
		List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
		float total = fleet.getFleetPoints();
		float target = total * Math.max(0f, Math.min(1f, share));
		if (members.isEmpty() || target <= 0f) return 0f;
		WeightedRandomPicker<FleetMemberAPI> picker = new WeightedRandomPicker<FleetMemberAPI>(random);
		for (FleetMemberAPI m : members) picker.add(m, m.isCivilian() ? 0.25f : 1f);
		float removed = 0f;
		while (removed < target && !picker.isEmpty()) {
			FleetMemberAPI m = picker.pickAndRemove();
			float fp = m.getFleetPointCost();
			if (removed + fp > target) {
				if (random.nextFloat() * fp < target - removed) {
					fleet.getFleetData().removeFleetMember(m);
					removed += fp;
				}
				break;
			}
			fleet.getFleetData().removeFleetMember(m);
			removed += fp;
		}
		if (fleet.getFleetData().getNumMembers() <= 0) {
			fleet.despawn(FleetDespawnReason.DESTROYED_BY_BATTLE, null);
		} else {
			fleet.getFleetData().sort();
			fleet.forceSync();
		}
		return removed;
	}
}
