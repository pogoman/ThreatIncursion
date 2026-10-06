package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

/**
 * COALITION - factions acting together, on their own initiative
 * (docs/design-theory.md 8.7, docs/player-aid.md section 5).
 *
 * <p>Two things happen on the slow tick. When a mobilised faction launches a
 * siege against a hive system it posts a CALL for coalitionCallDays; every
 * OTHER mobilised faction with a base in reach that has not yet answered
 * rolls coalitionSupportChance and, on success, raises a pooled hunting force
 * against the Defense Swarms over the worlds the siege is fighting
 * (ThreatSoftening.send, its purge's targets) so the siege lands under cover.
 *
 * <p>And allies help each other's colonies. Every mobilised faction looks at
 * every other mobilised faction's colonies with a need the sector would post
 * a request for ({@link ThreatAidRequests}): a strike coming that the
 * defenders are outmatched by, or a commodity the depot is exhausted for or
 * has been short of for missionAidShortageDays. Its willingness is its
 * standing with the needy faction - Cooperative 1.0, Friendly 0.75, Welcoming
 * 0.5, Favourable 0.25, anything less nothing - times allyAidChance per tick
 * per need; its means are what its own bases and donor colonies can spare.
 * Aid is a real guard task force or a real convoy, paid from the helper's
 * reserves, that can be lost on the way. The player never helps here - the
 * player helps by choice, from the war board - but IS a possible recipient:
 * an ally will guard a struck player colony or land a convoy at it.
 */
public class ThreatCoalition {

	public static final String KEY_CALLS = "threatinc_coalitionCalls";

	public static class Call {
		public String systemId;
		public String callerFactionId;
		public long issuedTimestamp;
		public List<String> answered = new ArrayList<String>();

		public float daysLeft() {
			return Math.max(0f, ThreatIncConfig.coalitionCallDays()
					- Global.getSector().getClock().getElapsedDaysSince(issuedTimestamp));
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Call> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_CALLS);
		if (!(val instanceof List)) {
			val = new ArrayList<Call>();
			Global.getSector().getPersistentData().put(KEY_CALLS, val);
		}
		return (List<Call>) val;
	}

	public static Call callFor(String systemId) {
		for (Call c : all()) {
			if (systemId.equals(c.systemId)) return c;
		}
		return null;
	}

	/** A mobilised faction's siege posts (or renews) the call for its target system. */
	public static void post(FactionAPI faction, StarSystemAPI system) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.coalitionEnabled()) return;
		if (faction == null || system == null || !ThreatWarState.isAtWar(faction)) return;
		Call c = callFor(system.getId());
		if (c == null) {
			c = new Call();
			c.systemId = system.getId();
			all().add(c);
		}
		c.callerFactionId = faction.getId();
		c.issuedTimestamp = Global.getSector().getClock().getTimestamp();
		c.answered.clear();
		c.answered.add(faction.getId());
		ThreatIncConfig.log("Coalition call posted by " + faction.getId() + " for "
				+ system.getName());
	}

	/** Slow tick: allies answer open calls, then help each other's colonies. */
	public static void tick(Random random) {
		answerCalls(random);
		allyAid(random);
	}

	protected static void answerCalls(Random random) {
		if (all().isEmpty()) return;
		for (Call c : new ArrayList<Call>(all())) {
			if (c.daysLeft() <= 0f) {
				all().remove(c);
				continue;
			}
			StarSystemAPI system = Global.getSector().getStarSystem(c.systemId);
			if (system == null || ThreatIncData.getLiveColonyMarkets(c.systemId).isEmpty()) {
				all().remove(c);
				continue;
			}
			for (String factionId : ThreatWarState.warFactionIds()) {
				if (c.answered.contains(factionId)) continue;
				FactionAPI faction = Global.getSector().getFaction(factionId);
				if (faction == null || faction.isPlayerFaction()) continue; // the player answers by hand
				// no answer to an enemy's call, nor into a system where an enemy's fleets
				// already fight: the Diktat answered Hegemony's Blost call while hostile to
				// it and fought its Support task force instead of the swarms (Run 7)
				if (c.callerFactionId != null && faction.isHostileTo(c.callerFactionId)) continue;
				if (ThreatSoftening.hostileAt(faction, c.systemId)) continue;
				MarketAPI base = ThreatFleetOrders.pickBase(faction, system.getLocation());
				if (base == null) continue;
				if (ThreatIncConfig.softenEnabled() && ThreatSoftening.hunting(factionId, c.systemId)) {
					// its hunting force is already there: that is the answer
					c.answered.add(factionId);
					continue;
				}
				if (random.nextFloat() >= ThreatIncConfig.coalitionSupportChance()) continue;
				// hunts the system's Defense Swarms under cover of the siege
				// (Intercept at the jump-point until 2026-09-24)
				if (ThreatIncConfig.softenEnabled()) {
					// with a pooled hunting force sized to win, not one guard fleet (a lone 100 FP
					// answer met 171 FP swarms, Run 6); a faction that cannot pay for one yet
					// tries again while the call stands
					// over the worlds the caller's siege is fighting (its purge's targets),
					// not the system's strongest garrison: a siege takes a subset since 2026-09-27.
					// From the best paid base free to hunt, as a bounty's hunt (huntBases)
					boolean sent = false;
					for (MarketAPI from : ThreatSoftening.huntBases(faction, system)) {
						if (ThreatSoftening.send(faction, from, system,
								IncursionManager.siegeTargetsOf(c.callerFactionId, c.systemId))) {
							sent = true;
							break;
						}
					}
					if (!sent) continue;
					c.answered.add(factionId);
					ThreatIncConfig.log("Coalition: " + factionId + " answers " + c.callerFactionId
							+ "'s call at " + system.getName() + " with a hunting force");
					continue;
				}
				ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchHunt(faction, system);
				c.answered.add(factionId);
				if (o != null) {
					ThreatIncConfig.log("Coalition: " + factionId + " answers " + c.callerFactionId
							+ "'s call at " + system.getName());
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// allies helping each other (docs/player-aid.md section 5)
	// ------------------------------------------------------------------

	/** How far a faction will go for another, 0..1, by its standing with it. */
	public static float willingness(FactionAPI helper, String needyFactionId) {
		if (helper == null || needyFactionId == null) return 0f;
		RepLevel level = helper.getRelationshipLevel(needyFactionId);
		if (level == null) return 0f;
		if (level.isAtWorst(RepLevel.COOPERATIVE)) return 1f;
		if (level.isAtWorst(RepLevel.FRIENDLY)) return 0.75f;
		if (level.isAtWorst(RepLevel.WELCOMING)) return 0.5f;
		if (level.isAtWorst(RepLevel.FAVORABLE)) return 0.25f;
		return 0f;
	}

	/**
	 * A faction's coalition partners: the other mobilised NPC factions it
	 * would help (willingness above 0) and is not hostile to either way. They
	 * pool their reports of the swarm as they are made (ThreatIntel, user's
	 * answer 2a, 2026-10-01). Empty for the player, who reads the reports of
	 * factions at Cooperative instead.
	 */
	public static List<String> partners(String factionId) {
		List<String> out = new ArrayList<String>();
		if (factionId == null || com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER.equals(factionId)) return out;
		FactionAPI self = Global.getSector().getFaction(factionId);
		if (self == null) return out;
		for (String other : ThreatWarState.warFactionIds()) {
			if (other.equals(factionId) || com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER.equals(other)) continue;
			if (ThreatWarState.excluded(other)) continue;
			FactionAPI them = Global.getSector().getFaction(other);
			if (them == null || self.isHostileTo(them) || them.isHostileTo(self)) continue;
			if (willingness(self, other) <= 0f && willingness(them, factionId) <= 0f) continue;
			out.add(other);
		}
		return out;
	}

	/** Whether a guard task force is already bound for or over the colony. */
	protected static boolean guardBoundFor(MarketAPI market) {
		return ThreatFleetOrders.guardBoundFor(market.getId());
	}

	/**
	 * Each mobilised colony with a need, each other mobilised NPC faction: by
	 * willingness and chance, one helper sends a guard or relief; every willing
	 * helper ships what it can spare until a shortage is covered.
	 */
	public static void allyAid(Random random) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.allyAidEnabled()) return;
		List<String> ids = ThreatWarState.warFactionIds();
		if (ids.size() < 2) return;
		float chance = ThreatIncConfig.allyAidChance();
		for (String needyId : ids) {
			FactionAPI needy = Global.getSector().getFaction(needyId);
			if (needy == null) continue;
			for (MarketAPI market : ThreatReserves.marketsOf(needyId)) {
				if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
				// a strike coming, or a landed Threat army whose relief the owner
				// could not pay for in full (2026-09-27: help used to stop at the landing)
				float owed = ThreatFleetOrders.reliefShort(market);
				if ((ThreatAidRequests.needsDefence(market) && !guardBoundFor(market)) || owed > 0f) {
					for (String helperId : ids) {
						if (helperId.equals(needyId)) continue;
						FactionAPI helper = Global.getSector().getFaction(helperId);
						if (helper == null || helper.isPlayerFaction()) continue;
						float w = willingness(helper, needyId);
						if (w <= 0f || random.nextFloat() >= w * chance) continue;
						// relief from the nearest base that can provision it at any distance, as the
						// owner's own (pickReliefBase, 2026-10-03); a guard against a strike keeps pickBase
						MarketAPI base = owed > 0f ? ThreatFleetOrders.pickReliefBase(helper, market)
								: ThreatFleetOrders.pickBase(helper, market.getLocationInHyperspace());
						if (base == null) continue;
						if (owed > 0f) {
							if (ThreatFleetOrders.sendRelief(helper, market, base, owed) <= 0f) continue;
						} else if (ThreatFleetOrders.dispatchGuard(helper, market, base, false) == null) {
							continue;
						}
						report(helper, needy, "Allied Task Force Sails", "sends a task force to guard %s",
								ThreatNotice.market(market));
						break;
					}
				}
				if (!ThreatReserves.hasDepot(market)) continue; // nowhere to land it
				// (2026-09-29: one convoy per helped colony, one helper per need and a
				// hull-load clip were caps - every willing helper now sends what it can
				// spare until the shortage is covered, net of what is already at sea)
				float[] atSea = ThreatConvoys.inbound(market.getId());
				for (String c : ThreatReserves.COMMODITIES) {
					if (!ThreatAidRequests.shortageStanding(market, c)) continue;
					float need = ThreatAidRequests.requestItems(market, c) - atSea[ThreatAid.index(c)];
					if (need < 1f) continue;
					for (String helperId : ids) {
						if (need < 1f) break;
						if (helperId.equals(needyId)) continue;
						FactionAPI helper = Global.getSector().getFaction(helperId);
						if (helper == null || helper.isPlayerFaction()) continue;
						float w = willingness(helper, needyId);
						if (w <= 0f || random.nextFloat() >= w * chance) continue;
						MarketAPI donor = ThreatConvoys.pickAllyDonor(helper, market, c);
						if (donor == null) continue;
						float amount = Math.min(need, ThreatConvoys.spare(donor, c));
						if (amount < 1f) continue;
						float[] load = new float[ThreatReserves.COMMODITIES.length];
						load[ThreatAid.index(c)] = amount;
						ThreatConvoys.Convoy convoy = ThreatConvoys.dispatch(donor, market, helper, load,
								random, needyId, false);
						if (convoy == null) continue;
						report(helper, needy, "Allied Convoy Sails", "sends %s %s from %s to %s",
								Misc.getWithDGS((int) amount), ThreatReserves.label(c),
								ThreatNotice.market(donor), ThreatNotice.market(market));
						need -= amount;
					}
				}
			}
		}
		aidStockPlans(ids, random);
	}

	/**
	 * Trade through the war (user, 2026-10-06): a war faction whose stock plan
	 * reads short of fuel or supplies - a producer it cannot pay for, or a
	 * stock that runs dry before one could stand (ThreatFactionStock.aidNeed)
	 * - is sent a real convoy by every war faction whose plan reads surplus of
	 * it, each from its colony in reach with the most to spare, until the need
	 * is covered net of what is at sea; one sailing a faction a month per
	 * stock. Any two war factions not hostile to each other trade, whatever
	 * their standing: the shortage rule above reads vanilla's peacetime market,
	 * never the war reserve, so in hw33b the Hegemony sat on 27k supplies for
	 * 1,200 days with its yards a hive and no ally sent a unit.
	 */
	protected static void aidStockPlans(List<String> ids, Random random) {
		String[] stocks = { Commodities.FUEL, Commodities.SUPPLIES };
		for (String needyId : ids) {
			FactionAPI needy = Global.getSector().getFaction(needyId);
			if (needy == null || needy.isPlayerFaction() || ThreatWarState.excluded(needyId)) continue;
			for (String c : stocks) {
				if (!ThreatFactionStock.mayAid(needyId, c)) continue;
				float need = ThreatFactionStock.aidNeed(needyId, c);
				if (need <= 0f) continue;
				MarketAPI to = ThreatFactionStock.aidTarget(needyId, c);
				if (to == null) continue;
				need -= ThreatConvoys.inbound(to.getId())[ThreatAid.index(c)];
				float unit = Global.getSettings().getCommoditySpec(c).getEconUnit();
				if (need < unit) continue;
				boolean sailed = false;
				for (String helperId : ids) {
					if (need < unit) break;
					if (helperId.equals(needyId) || ThreatWarState.excluded(helperId)) continue;
					FactionAPI helper = Global.getSector().getFaction(helperId);
					if (helper == null || helper.isPlayerFaction()) continue;
					if (helper.isHostileTo(needy) || needy.isHostileTo(helper)) continue;
					if (!ThreatFactionStock.surplus(helperId, c)) continue;
					MarketAPI donor = ThreatConvoys.pickAllyDonor(helper, to, c);
					if (donor == null) continue;
					float amount = Math.min(need, ThreatConvoys.spare(donor, c));
					if (amount < unit) continue;
					float[] load = new float[ThreatReserves.COMMODITIES.length];
					load[ThreatAid.index(c)] = amount;
					ThreatConvoys.Convoy convoy = ThreatConvoys.dispatch(donor, to, helper, load, random, needyId, false);
					if (convoy == null) continue;
					String held = ThreatFactionStock.heldIndustry(to);
					report(helper, needy, "Allied Convoy Sails", "sends %s %s from %s to %s (%s)",
							Misc.getWithDGS((int) amount), ThreatReserves.label(c), ThreatNotice.market(donor),
							ThreatNotice.market(to), held != null && Commodities.SUPPLIES.equals(c)
									? "for its " + held : ThreatReserves.label(c) + " runs dry");
					need -= amount;
					sailed = true;
				}
				if (sailed) ThreatFactionStock.aided(needyId, c);
			}
		}
	}

	/** An ally's convoy landed at another faction's colony. */
	public static void onAllyDelivered(ThreatConvoys.Convoy c, MarketAPI base) {
		FactionAPI helper = Global.getSector().getFaction(c.factionId);
		if (helper == null) return;
		ThreatColonyManager.announce(ThreatNotice.titled("Allied Convoy Landed").icon(helper)
				.line("%s convoy lands at %s", ThreatNotice.faction(helper), ThreatNotice.market(base))
				.line("Delivered: %s", ThreatFactionView.cargoText(c.marines, c.armaments, c.fuel, c.supplies)));
	}

	/**
	 * Debug narration of an ally's aid: "{Helper} {what}" with the names in
	 * {@code what} highlighted, then who it is for and the helper's standing.
	 */
	protected static void report(FactionAPI helper, FactionAPI needy, String title, String what,
			Object... args) {
		String standing = helper.getRelationshipLevel(needy.getId()) != null
				? helper.getRelationshipLevel(needy.getId()).getDisplayName() : "";
		Object[] withWho = new Object[args.length + 1];
		withWho[0] = ThreatNotice.faction(helper);
		System.arraycopy(args, 0, withWho, 1, args.length);
		ThreatNotice n = ThreatNotice.titled(title).icon(helper).line("%s " + what, withWho);
		if (needy.isPlayerFaction()) n.line("For you");
		else n.line("For %s", ThreatNotice.faction(needy));
		if (!standing.isEmpty()) n.line("Standing: %s", standing);
		ThreatColonyManager.announce(n);
		ThreatIncConfig.log("Ally aid: " + helper.getId() + " for " + needy.getId() + ": " + n.plain());
	}
}
