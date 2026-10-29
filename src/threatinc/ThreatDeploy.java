package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

/**
 * DEPLOY / REDEPLOY (the user, 2026-10-29: "deploy should deploy a fleet sized based on hulls
 * modifier (if none then deploy is greyed out), with fuel sized on its modifers, supplies on its
 * etc. ... you should be able to deploy again and modify the deployed fleet by changing the none
 * med max buttons ... if none hulls set then its the same effect as recall"): the colonies table's
 * Deploy button. A colony's own hulls become ONE fleet in orbit over it, sized by the Hulls lever
 * - None / Med / Max = nothing / half / all of the hulls it has free, the fleet on station counted
 * with them - and loaded from its own reserve at the other four levers: marines and heavy
 * armaments as cargo (as many as its berths and holds take), fuel and supplies as figures the
 * fleet carries (ThreatReturns.MEM_FUEL_ABOARD, the hold). The fleet is the colony's staged guard
 * (ThreatFleetOrders.KIND_GUARD over its own colony, until recalled); its hulls are the colony's
 * (ThreatAidCapacity, rehome) and the Hulls cell reads what is left free.
 *
 * <p>Pressed again with a fleet deployed, the button is <b>Redeploy</b>: the fleet is resized and
 * reloaded to the levers as they stand now - hulls built from the colony's free hulls when the
 * lever asks for more, the smallest ships reabsorbed as hulls when it asks for less
 * ({@link #reabsorb}), each good drawn from the reserve or put back into it, and what the resized
 * fleet can no longer berth or hold put back too. At Hulls None the fleet dissolves into the
 * colony: hulls and everything aboard back where they came from (ThreatFleetOrders.fold).
 *
 * <p>What is deployed is what an Attack sails (IncursionManager.attackPlan: the nearest colony
 * with a fleet deployed, everything aboard it - the passage out of the fuel first, the rest
 * razes; the Attack button is off while the fleet carries less fuel than the passage or fewer
 * supplies than the expedition's hold). Nothing of this draws for a voyage: the levers are the
 * cost, chosen by the player.
 *
 * <p>An outpost's Deploy (no hulls or reserve of its own) builds and loads at the nearest colony
 * of the player's whose Hulls share fields a fleet, and sends it to the outpost - the old Fleet
 * order at the levers; nothing resizes there.
 */
public class ThreatDeploy {

	/** What a Deploy at a row would do at the colonies table's levers. */
	public static class Plan {
		/** Where the fleet stages: the row's colony, or the player's outpost. */
		public ThreatBases.Base target;
		/** Whose hulls and reserve it is built and loaded from: the colony itself, or the nearest colony for an outpost. */
		public MarketAPI source;
		/** The fleet(s) deployed over the target now (ThreatFleetOrders.stagedAt); empty for a fresh Deploy. */
		public List<ThreatFleetOrders.Order> staged = new ArrayList<ThreatFleetOrders.Order>();
		/** Whole-fleet points deployed now, and at the Hulls lever. */
		public float livePts, targetPts;
		/** The source's own free hulls, whole-fleet points. */
		public float ownFree;
		/** Aboard now, and at the levers: marines, heavy armaments, fuel, supplies (ThreatReserves.COMMODITIES order). */
		public float[] aboard = new float[4], load = new float[4];
		/** Hulls None with a fleet deployed: it dissolves into the colony. */
		public boolean dissolve;
		/** Why not, one fact per line; null when the order would go. */
		public ThreatNotice.Reason reason;
		/** The colonies table's levers the plan was made at (ThreatIncursionIntel.colonyLevers). */
		public int[] levers;

		public boolean ok() {
			return reason == null && source != null && target != null;
		}

		/** Whether a fleet is deployed there already (the button reads Redeploy). */
		public boolean redeploy() {
			return !staged.isEmpty();
		}
	}

	/** Smallest fleet a Deploy makes, whole-fleet points (aidGuardMinFP in the ledger's unit). */
	public static float minPoints() {
		return ThreatAidCapacity.taskForcePoints(ThreatIncConfig.aidGuardMinFP());
	}

	/** The Hulls lever's tier of a colonies-table lever set (ThreatIncursionIntel.colonyLevers), 0 when it has none. */
	protected static int hullsTier(int[] levers) {
		return levers != null && levers.length > 4 ? levers[4] : 0;
	}

	/**
	 * The plan for a Deploy / Redeploy at the target at the levers. A colony of the player's is
	 * its own source; an outpost's source is {@link #outpostSource}. The hulls: the lever's share
	 * of the source's free hulls plus what is deployed there already; under {@link #minPoints}
	 * the plan is refused with the figures. Each good: the lever's share of what the source
	 * spares (ThreatConvoys.handSpare) plus what is aboard already - marines and arms are then
	 * cut to the fleet's berths and holds when it is loaded.
	 */
	public static Plan plan(ThreatBases.Base target, int[] levers) {
		Plan p = new Plan();
		p.target = target;
		p.levers = levers != null ? levers : new int[] { 1, 1, 1, 1, 1 };
		if (target == null || target.entity() == null) {
			p.reason = ThreatNotice.Reason.of("No target");
			return p;
		}
		MarketAPI colony = target.market != null && target.market.isPlayerOwned() ? target.market : null;
		if (colony != null) {
			p.source = colony;
			p.staged = ThreatFleetOrders.stagedAt(colony);
		} else {
			p.source = outpostSource(target, levers);
		}
		if (!ThreatAidCapacity.enabled()) {
			p.reason = ThreatNotice.Reason.of("Player aid is disabled in the mod settings");
			return p;
		}
		for (ThreatFleetOrders.Order o : p.staged) {
			CampaignFleetAPI f = o.fleet;
			if (ThreatAidCapacity.heldBy(f) == null) continue;
			p.livePts += ThreatAidCapacity.liveFP(f);
			p.aboard[0] += f.getCargo().getMarines();
			p.aboard[1] += f.getCargo().getCommodityQuantity(Commodities.HAND_WEAPONS);
			p.aboard[2] += ThreatReturns.fuelAboard(f);
			p.aboard[3] += ThreatReturns.hold(f);
		}
		int hulls = hullsTier(levers);
		if (hulls <= 0) {
			if (p.redeploy()) {
				p.dissolve = true;
				p.targetPts = 0f;
				return p;
			}
			p.reason = ThreatNotice.Reason.of("The Hulls lever is at None")
					.line("Med or Max deploys half or all of the hulls free");
			return p;
		}
		if (p.source == null) {
			p.reason = ThreatNotice.Reason.of("No colony of yours can field a fleet for %s at the Hulls lever",
					target.name()).line("A fleet needs at least %s fleet points", (int) Math.ceil(minPoints()));
			return p;
		}
		p.ownFree = Math.max(0f, ThreatAidCapacity.ownFreeFP(p.source));
		p.targetPts = (float) Math.floor(ThreatConvoys.share(hulls) * (p.ownFree + p.livePts));
		if (p.targetPts < minPoints() - 0.5f) {
			p.reason = ThreatNotice.Reason.of("%s has %s fleet points free", ThreatNotice.market(p.source),
					Misc.getWithDGS((int) p.ownFree))
					.line("The Hulls lever makes a fleet of %s", Misc.getWithDGS((int) p.targetPts))
					.line("A fleet needs at least %s", Misc.getWithDGS((int) Math.ceil(minPoints())));
			return p;
		}
		for (int i = 0; i < 4 && i < p.levers.length; i++) {
			float spare = ThreatConvoys.handSpare(p.source, ThreatReserves.COMMODITIES[i]);
			p.load[i] = (float) Math.floor(ThreatConvoys.share(p.levers[i]) * (spare + p.aboard[i]));
		}
		return p;
	}

	/**
	 * The colony of the player's an outpost's Deploy builds at: the nearest whose own free hulls at
	 * the Hulls lever's share make at least {@link #minPoints}, or null.
	 */
	protected static MarketAPI outpostSource(ThreatBases.Base target, int[] levers) {
		if (target == null || target.hyperLoc() == null) return null;
		float share = ThreatConvoys.share(hullsTier(levers));
		if (share <= 0f) return null;
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(Global.getSector().getPlayerFaction().getId())) {
			if (m.getStarSystem() == null || !ThreatAidCapacity.applies(m)) continue;
			if (share * ThreatAidCapacity.ownFreeFP(m) < minPoints() - 0.5f) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), target.hyperLoc());
			if (d < bestDist) {
				bestDist = d;
				best = m;
			}
		}
		return best;
	}

	/** The button's one-line tooltip while it is off: the plan's reason, or the order gate's. */
	public static String offReason(Plan p) {
		return p == null ? null : ThreatNotice.text(p.reason);
	}

	/** "320 marines, 400 arms, 1,200 fuel and 3,000 supplies" - a load's non-zero parts, or "nothing". */
	public static String loadText(float[] load) {
		List<String> parts = new ArrayList<String>();
		if (load[0] > 0.5f) parts.add(Misc.getWithDGS((int) load[0]) + " marines");
		if (load[1] > 0.5f) parts.add(Misc.getWithDGS((int) load[1]) + " arms");
		if (load[2] > 0.5f) parts.add(Misc.getWithDGS((int) load[2]) + " fuel");
		if (load[3] > 0.5f) parts.add(Misc.getWithDGS((int) load[3]) + " supplies");
		return parts.isEmpty() ? "nothing" : Misc.getAndJoined(parts);
	}

	/**
	 * Carries the plan out for the player: the fleet built, resized or dissolved, loaded to the
	 * levers, the notice sent. Returns a word for the log, or null when nothing happened (a
	 * refusal notice says why).
	 */
	public static String execute(Plan p, FactionAPI faction) {
		if (p == null || faction == null || !faction.isPlayerFaction() || p.target == null) return null;
		if (p.dissolve) return dissolve(p, faction);
		if (!p.ok()) {
			ThreatNotice.titled("Deploy Refused").bad().icon(faction).lines(p.reason).send();
			return null;
		}
		MarketAPI source = p.source;
		boolean redeploy = p.redeploy();
		CampaignFleetAPI lead = null;
		ThreatFleetOrders.Order order = null;
		// several fleets deployed over one colony (an older save's guards): one fleet from here on
		for (ThreatFleetOrders.Order o : p.staged) {
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			ThreatFleetOrders.rehome(o);
			if (lead == null) {
				lead = o.fleet;
				order = o;
				continue;
			}
			for (ThreatAidCapacity.Commitment c : ThreatAidCapacity.heldAllBy(o.fleet)) c.fleet = lead;
			ThreatFleetOrders.all().remove(o);
			ThreatFleetOrders.absorb(lead, o.fleet);
		}
		float before = lead != null ? ThreatAidCapacity.liveFP(lead) : 0f;
		float built = 0f, reabsorbed = 0f;
		String label = "fleet deployed over " + p.target.name();
		if (p.targetPts > before + 1f) {
			float[] got = new float[] {0f};
			lead = build(source, faction, (p.targetPts - before) / ThreatAidCapacity.TASK_FORCE_HULL_MULT, lead,
					p.target, label, got);
			built = got[0];
			if (lead == null) {
				ThreatNotice.titled("Deploy Refused").bad().icon(faction)
						.line("No fleet could be raised at %s", ThreatNotice.market(source)).send();
				return null;
			}
		} else if (lead != null && p.targetPts < before - 1f) {
			reabsorbed = reabsorb(lead, before - p.targetPts);
		}
		if (order == null) {
			// a fresh Deploy: the colony's staged guard, until recalled; over its own colony it is on
			// station now (ThreatFleetOrders.poll would say so at the next pass)
			order = ThreatFleetOrders.guardOrder(lead, faction, p.target, source, ThreatIncConfig.guardOwnDays(), false);
			if (p.target.market != null && p.target.market == source) {
				order.arrived = true;
				ThreatFleetOrders.rehome(order);
			}
		}
		float[] back = new float[4];
		float[] now = load(lead, source, p.levers, back);
		float live = ThreatAidCapacity.liveFP(lead);
		ThreatNotice n = ThreatNotice.titled(redeploy ? "Fleet Redeployed" : "Fleet Deployed").icon(faction)
				.line(p.target.market != null && p.target.market == source ? "Over %s: %s fleet points"
						: "For %s: %s fleet points", ThreatNotice.base(p.target), Misc.getWithDGS((int) live));
		if (p.target.market == null || p.target.market != source) {
			n.line("From %s's hulls and reserve", ThreatNotice.market(source));
		}
		if (built > 0.5f) n.line("%s fleet points built from the hulls free", Misc.getWithDGS((int) built));
		if (reabsorbed > 0.5f) n.line("%s fleet points back as hulls", Misc.getWithDGS((int) reabsorbed));
		n.line("Aboard: %s", loadText(now));
		if (back[0] + back[1] + back[2] + back[3] > 0.5f) n.line("Back in the reserve: %s", loadText(back));
		n.send();
		ThreatIncConfig.log("Deploy at " + p.target.name() + " from " + source.getName() + ": " + (int) live
				+ " FP (" + (int) built + " built, " + (int) reabsorbed + " reabsorbed), aboard " + loadText(now)
				+ ", back " + loadText(back));
		ThreatAidCapacity.logHulls("after deploy at " + p.target.name());
		return redeploy ? "redeploy" : "deploy";
	}

	/** Hulls None with a fleet deployed: it dissolves into the colony where it stands - hulls, cargo and holds (ThreatFleetOrders.fold). */
	protected static String dissolve(Plan p, FactionAPI faction) {
		float pts = 0f;
		for (ThreatFleetOrders.Order o : p.staged) {
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			ThreatFleetOrders.rehome(o);
			pts += ThreatAidCapacity.liveFP(o.fleet);
			ThreatFleetOrders.fold(o, p.target.market != null ? p.target.market : p.source);
		}
		ThreatNotice.titled("Fleet Dissolved").icon(faction)
				.line("At %s: %s fleet points back as hulls", ThreatNotice.base(p.target), Misc.getWithDGS((int) pts))
				.line("Back in the reserve: %s", loadText(p.aboard)).send();
		ThreatIncConfig.log("Deploy: fleet over " + p.target.name() + " dissolved, " + (int) pts + " FP, aboard "
				+ loadText(p.aboard));
		ThreatAidCapacity.logHulls("after the fleet over " + p.target.name() + " dissolved");
		return "dissolve";
	}

	/**
	 * Builds {@code combat} combat points at the source as fleets of one fleet's size
	 * (ThreatSoftening.fleetCapFor; vanilla's factory stops at maxShipsInAIFleet ships) and folds
	 * each into {@code lead} - or the first built, when there is none - with its ledger points
	 * committed to the source and riding the lead (ThreatAidCapacity.commit). No draw: the levers
	 * load the fleet ({@link #load}). Returns the lead, or null when nothing could be built;
	 * {@code builtOut}[0] takes the points built.
	 */
	protected static CampaignFleetAPI build(MarketAPI source, FactionAPI faction, float combat, CampaignFleetAPI lead,
			ThreatBases.Base target, String label, float[] builtOut) {
		float got = 0f;
		int fleets = 0;
		while (got < combat - 1f && fleets < 40) {
			float left = combat - got;
			float ask = Math.min(left, ThreatSoftening.fleetCapFor(source, faction.getId(), left));
			if (fleets > 0 && ask < ThreatIncConfig.aidGuardMinFP()) break;
			CampaignFleetAPI fleet = ThreatFleetOrders.buildTaskForce(source, faction, ask, target.hyperLoc(), false);
			if (fleet == null) break;
			float fp = ThreatSoftening.combatFP(fleet);
			if (fp < 1f) {
				Misc.fadeAndExpire(fleet);
				break;
			}
			ThreatSoftening.learnFleetCap(faction.getId(), fleet, ask);
			float points = ThreatFleetOrders.builtPoints(fleet, ask);
			fleets++;
			got += fp;
			if (lead == null) {
				lead = fleet;
				ThreatAidCapacity.commit(source, points, lead, label);
				builtOut[0] += points;
				continue;
			}
			ThreatAidCapacity.commit(source, points, lead, label);
			builtOut[0] += points;
			ThreatFleetOrders.absorb(lead, fleet);
		}
		return lead;
	}

	/**
	 * Takes about {@code pts} fleet points of ships off the fleet, smallest first (never its
	 * flagship, never the last ship): they are the colony's hulls again - every ledger entry the
	 * fleet holds shrinks by the share removed, as does the strength it is refunded against
	 * (ThreatReturns.MEM_FP0), so nothing reads as lost. Returns the points removed.
	 */
	public static float reabsorb(CampaignFleetAPI fleet, float pts) {
		if (fleet == null || !fleet.isAlive() || pts <= 0f) return 0f;
		float before = fleet.getFleetPoints();
		if (before <= 0f) return 0f;
		float target = Math.max(0f, before - pts);
		List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
		Collections.sort(members, new Comparator<FleetMemberAPI>() {
			public int compare(FleetMemberAPI a, FleetMemberAPI b) {
				return Float.compare(a.getFleetPointCost(), b.getFleetPointCost());
			}
		});
		FleetMemberAPI flagship = fleet.getFlagship();
		for (FleetMemberAPI m : members) {
			if (fleet.getFleetPoints() <= target + 0.5f) break;
			if (m == flagship || fleet.getFleetData().getNumMembers() <= 1) continue;
			fleet.getFleetData().removeFleetMember(m);
		}
		fleet.getFleetData().setSyncNeeded();
		fleet.getFleetData().syncIfNeeded();
		fleet.forceSync();
		float after = fleet.getFleetPoints();
		float frac = Math.max(0f, Math.min(1f, after / before));
		for (ThreatAidCapacity.Commitment c : ThreatAidCapacity.heldAllBy(fleet)) {
			c.fp *= frac;
		}
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		for (String k : new String[] { ThreatReturns.MEM_FP0, ThreatReturns.MEM_FP_ORDER }) {
			if (mem.contains(k)) mem.set(k, mem.getFloat(k) * frac);
		}
		ThreatIncConfig.log("Deploy: " + fleet.getName() + " " + (int) before + " -> " + (int) after
				+ " FP, " + (int) (before - after) + " FP reabsorbed as hulls");
		return before - after;
	}

	/**
	 * Loads the fleet to the plan's levers from the source's reserve: each good's figure at the
	 * lever less what is aboard is drawn (ThreatReserves.drawAbove) or put back
	 * (ThreatReserves.deposit) - marines within the berths, heavy armaments within the holds
	 * (what a resized fleet no longer takes goes back too). Returns what is aboard after;
	 * {@code backOut} takes what went back.
	 */
	protected static float[] load(CampaignFleetAPI fleet, MarketAPI source, int[] levers, float[] backOut) {
		float[] now = new float[4];
		if (fleet == null || source == null || levers == null) return now;
		// the levers again, against the fleet as it stands (the plan quoted them against the
		// fleet before it was resized)
		CargoAPI cargo = fleet.getCargo();
		float[] aboard = new float[] { cargo.getMarines(), cargo.getCommodityQuantity(Commodities.HAND_WEAPONS),
				ThreatReturns.fuelAboard(fleet), ThreatReturns.hold(fleet) };
		float[] want = new float[4];
		for (int i = 0; i < 4; i++) {
			float spare = ThreatConvoys.handSpare(source, ThreatReserves.COMMODITIES[i]);
			want[i] = (float) Math.floor(ThreatConvoys.share(i < levers.length ? levers[i] : 0) * (spare + aboard[i]));
		}
		// marines within the berths, arms within the holds (supplies and fuel ride as figures)
		// (never below 0: after a reabsorb the berths or holds can be over-full, and the surplus goes back)
		want[0] = Math.max(0f, Math.min(want[0], aboard[0] + cargo.getFreeCrewSpace()));
		want[1] = Math.max(0f, Math.min(want[1], aboard[1] + cargo.getSpaceLeft()));
		for (int i = 0; i < 4; i++) {
			String good = ThreatReserves.COMMODITIES[i];
			float delta = want[i] - aboard[i];
			float after = aboard[i];
			if (delta >= 1f) {
				float got = (float) Math.floor(ThreatReserves.drawAbove(source, good, delta));
				after += got;
			} else if (delta <= -1f) {
				float back = Math.min(aboard[i], (float) Math.floor(-delta));
				if (back < 1f) continue;
				ThreatReserves.deposit(source.getId(), good, back);
				backOut[i] += back;
				after -= back;
			}
			switch (i) {
				case 0:
					if (after > aboard[0]) cargo.addMarines((int) (after - aboard[0]));
					else if (after < aboard[0]) cargo.removeMarines((int) (aboard[0] - after));
					break;
				case 1:
					if (after > aboard[1]) cargo.addCommodity(Commodities.HAND_WEAPONS, after - aboard[1]);
					else if (after < aboard[1]) cargo.removeCommodity(Commodities.HAND_WEAPONS, aboard[1] - after);
					break;
				case 2:
					ThreatReturns.setFuelAboard(fleet, after);
					break;
				default:
					ThreatReturns.setHold(fleet, after);
					break;
			}
			now[i] = after;
		}
		return now;
	}

	/** Whether the guard order is a fleet deployed over the player's own colony (the fleets table's "Deployed"). */
	public static boolean isDeployed(ThreatFleetOrders.Order o) {
		if (o == null || !ThreatFleetOrders.KIND_GUARD.equals(o.kind) || o.aid || o.recipientFactionId != null) return false;
		if (!Global.getSector().getPlayerFaction().getId().equals(o.factionId)) return false;
		MarketAPI host = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		return host != null && host.isPlayerOwned();
	}
}
