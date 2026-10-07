package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE OFFENSIVE - the swarm's grand strategy (the user, 2026-10-07: "they should go
 * through periods where they are saving and then do a multi pronged attack on all
 * known systems ... if they are losing ... focus on nearby bases only ... it depends
 * on their resources vs the known resources of their enemy").
 *
 * Every monthly pass (IncursionManager.tryStrikes) the hive prices a strike at every
 * target system it knows - one prong a system, from the nearest hive system that can
 * stage it, sized by stagedPlan to outweigh the strongest defence seen there - and
 * reads its means: the strike fund, and what the fund gains a month until the
 * campaign's deadline. The campaign is the most valuable set of prongs the means pay
 * by the deadline (value per fleet point, the stance's weights); nothing sails until
 * the fund holds the whole set's bill, then every prong launches in the same pass.
 * At the deadline the fund pays what it can and a new campaign starts. With
 * strikeStagedGarrisons a prong takes the hive's spare Defense Swarms first, up to
 * its need, and the fund builds only what is missing; one pass shares the spare
 * among its prongs (Spares) and a held prong earmarks its swarms until its day
 * (earmarked). Losing, by degree (ThreatStance.losingPressure, 0-1): the reach
 * shrinks from every known target toward offensiveNearLY of a live hive, spoiling
 * blows weigh more, and the horizon shortens toward offensiveLosingMonths.
 * A front of its own short of troops gets its relief strike at once, outside the
 * campaign (strikeReliefFirst), as before. The war's opening strikes keep their floor
 * (strikeStagedMinFP). Off (swarmOffensive): the closest payable target, one a pass.
 * State: threatinc_offensive -> {campaign start day}. docs/strikes-staged.md.
 */
public class ThreatOffensive {

	protected static final String KEY = "threatinc_offensive";

	/** One strike of the campaign: the target, who stages it and what it costs the fund. */
	protected static class Prong {
		MarketAPI target, staging;
		StarSystemAPI source;
		/** The fund's bill, the strike's fleet points, its passage fuel (round trip) and days away. */
		float cost, fp, fuel, days, ly, score, def, expected;
		/** The plan it was priced at: the garrison swarms it takes from which hive systems (spareFP of them). */
		IncursionManager.StagedPlan plan;

		/** Supplies its trip burns beyond what its garrison swarms paid at home (ThreatReach.awayFP). */
		float supplies() {
			return ThreatReach.suppliesPerMonth(ThreatReach.awayFP(fp, plan != null ? plan.spareFP : 0f))
					* Math.max(1f, days) / 30f;
		}

		/** Fleet points a month it keeps away beyond the garrison swarms' home charge (sustainableFP's unit). */
		float awayFP() {
			return ThreatReach.awayFP(fp, plan != null ? plan.spareFP : 0f);
		}
	}

	/**
	 * The hive's spare Defense Swarms one pass shares among its prongs (strikeStagedGarrisons): a
	 * prong takes the first fleets of each system's walk (IncursionManager.stagedPlan), so what it
	 * took is skipped for the next, as consumeGarrison will take them in the same order.
	 */
	protected static class Spares {
		final IncursionManager im;
		final Map<String, List<IncursionManager.StagedSpare>> bySource = new HashMap<String, List<IncursionManager.StagedSpare>>();
		/** Hive system id -> garrison fleets of its spare already given to a prong. */
		final Map<String, Integer> taken = new HashMap<String, Integer>();

		Spares(IncursionManager im) {
			this.im = im;
		}

		/** What is left for a prong staged in this system, nearest first. */
		List<IncursionManager.StagedSpare> left(StarSystemAPI source) {
			List<IncursionManager.StagedSpare> all = bySource.get(source.getId());
			if (all == null) {
				all = im.stagedSpares(source);
				bySource.put(source.getId(), all);
			}
			List<IncursionManager.StagedSpare> out = new ArrayList<IncursionManager.StagedSpare>();
			for (IncursionManager.StagedSpare s : all) {
				Integer t = taken.get(s.colony.getStarSystem().getId());
				int skip = t != null ? t : 0;
				if (skip >= s.walk.size()) continue;
				IncursionManager.StagedSpare c = new IncursionManager.StagedSpare();
				c.colony = s.colony;
				c.ly = s.ly;
				c.walk = s.walk.subList(skip, s.walk.size());
				out.add(c);
			}
			return out;
		}

		void take(Prong p) {
			if (p.plan == null) return;
			for (int i = 0; i < p.plan.from.size(); i++) {
				String id = p.plan.from.get(i).getStarSystem().getId();
				Integer t = taken.get(id);
				taken.put(id, (t != null ? t : 0) + p.plan.counts.get(i));
			}
		}
	}

	protected static float startDay() {
		Object v = Global.getSector().getPersistentData().get(KEY);
		return v instanceof float[] && ((float[]) v).length > 0 ? ((float[]) v)[0] : -1f;
	}

	protected static void setStartDay(float day) {
		Global.getSector().getPersistentData().put(KEY, new float[] { day });
	}

	/** The monthly pass: relief strikes at once, then the campaign - saved for, or launched whole. */
	public static void pass(IncursionManager im) {
		float day = ThreatPosture.today();
		Map<String, MarketAPI> stagings = new HashMap<String, MarketAPI>();
		Map<String, StarSystemAPI> sources = new HashMap<String, StarSystemAPI>();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			MarketAPI colony = ThreatColonyManager.pickStrikeStaging(systemId, true);
			StarSystemAPI source = im.getSystem(systemId);
			if (colony == null || source == null) continue;
			stagings.put(systemId, colony);
			sources.put(systemId, source);
		}
		if (stagings.isEmpty()) return;
		List<MarketAPI> candidates = im.stagedCandidates(null);
		for (MarketAPI m : new ArrayList<MarketAPI>(candidates)) {
			if (scheduled(m)) candidates.remove(m);
		}
		if (candidates.isEmpty()) return;

		// relief first: a front of the swarm's own short of troops (strikeReliefFirst)
		if (ThreatIncConfig.strikeReliefFirst()) {
			for (MarketAPI target : new ArrayList<MarketAPI>(candidates)) {
				if (!ThreatGroundFronts.wantsExpedition(target)) continue;
				String near = nearest(target, sources);
				if (near == null) continue;
				if (im.launchStrike(stagings.get(near), sources.get(near), target) != null) {
					candidates.remove(target);
					if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(near);
				}
			}
		}

		// the prongs: one a target system, sized for the strongest defence seen in it
		// losing is a trend and a degree (ThreatStance.losingPressure, the user 2026-10-07: "a series of worlds
		// falling not just a one off"), and a war's verdict (hw45b): the reach shrinks from every known target
		// toward offensiveNearLY as it rises; with nothing within the reach, the swarm takes what it knows
		float pressure = ThreatStance.losingPressure();
		boolean losing = pressure > 0f;
		float nearLY = 0f;
		float floorLY = Math.max(0f, ThreatIncConfig.offensiveNearLY());
		if (losing && floorLY > 0f) {
			float farthest = 0f;
			for (MarketAPI target : candidates) {
				String near = target.getStarSystem() != null ? nearest(target, sources) : null;
				if (near != null) farthest = Math.max(farthest,
						Misc.getDistanceLY(sources.get(near).getLocation(), target.getStarSystem().getLocation()));
			}
			if (farthest > floorLY) nearLY = farthest - (farthest - floorLY) * pressure;
			boolean anyNear = false;
			for (MarketAPI target : candidates) {
				String near = target.getStarSystem() != null ? nearest(target, sources) : null;
				if (near != null && Misc.getDistanceLY(sources.get(near).getLocation(), target.getStarSystem().getLocation()) <= nearLY) {
					anyNear = true;
					break;
				}
			}
			if (!anyNear) nearLY = 0f;
		}
		Map<String, float[]> memo = new HashMap<String, float[]>();
		Map<String, Prong> bySystem = new HashMap<String, Prong>();
		float margin = Math.max(1f, ThreatIncConfig.strikeStagedMargin());
		for (MarketAPI target : candidates) {
			if (target.getStarSystem() == null) continue;
			String near = nearest(target, sources);
			if (near == null) continue;
			float ly = Misc.getDistanceLY(sources.get(near).getLocation(), target.getStarSystem().getLocation());
			if (nearLY > 0f && ly > nearLY) continue;
			float def = IncursionManager.targetDefence(target, memo);
			if (def >= Float.MAX_VALUE) continue;
			Prong have = bySystem.get(target.getStarSystem().getId());
			if (have != null && have.def >= def) continue;
			Prong p = new Prong();
			p.target = target;
			p.staging = stagings.get(near);
			p.source = sources.get(near);
			p.ly = ly;
			p.def = def;
			bySystem.put(target.getStarSystem().getId(), p);
		}
		// what each faction can answer with, as seen: its navy seen elsewhere is shared among the
		// prongs at it (the user, 2026-10-07: the swarm knows what it has seen, not the total)
		Map<String, Integer> atFaction = new HashMap<String, Integer>();
		for (Prong p : bySystem.values()) {
			Integer n = atFaction.get(p.target.getFactionId());
			atFaction.put(p.target.getFactionId(), n == null ? 1 : n + 1);
		}
		// ranked each against the whole spare: its size is the same whoever pays it, and the spare is shared out below
		Spares whole = new Spares(im);
		List<Prong> prongs = new ArrayList<Prong>();
		for (Prong p : bySystem.values()) {
			if (!price(im, p, memo, atFaction.get(p.target.getFactionId()), whole)) continue;
			float value = IncursionManager.strikeValue(p.target)
					* ThreatStance.strikeTargetMult(p.target, p.source, 1f / margin);
			// losing: the spoiling blow - a base staging against a hive, a forward base - and the near first, by degree
			if (losing && (ThreatFrontlines.isOutpost(p.target) || ThreatStance.stagingAgainstHive(p.target))) value *= 1f + 9f * pressure;
			// the price is the strike's fleet points, from the spare or the fund, and the supplies the trip burns
			// beyond the spare's home charge, in fleet points (the swarm's bottleneck)
			float price = p.fp + p.supplies() / Math.max(0.01f, ThreatReach.suppliesPerFP());
			p.score = value / Math.max(1f, price) / (losing ? (float) Math.pow(Math.max(1f, p.ly), pressure) : 1f);
			prongs.add(p);
		}
		java.util.Collections.sort(prongs, new java.util.Comparator<Prong>() {
			public int compare(Prong a, Prong b) {
				int c = Float.compare(b.score, a.score);
				return c != 0 ? c : Float.compare(a.ly, b.ly);
			}
		});

		// the means by the deadline, and the campaign they pay
		float fund = ThreatColonyManager.strikeFund();
		float perMonth = ThreatColonyManager.strikeFundPerMonth();
		float fullHorizon = ThreatIncConfig.offensiveHorizonMonths();
		float horizon = Math.max(1f, fullHorizon - (fullHorizon - ThreatIncConfig.offensiveLosingMonths()) * pressure);
		float start = startDay();
		if (start < 0f || start > day) {
			start = day;
			setStartDay(day);
		}
		float deadline = start + horizon * 30f;
		float monthsLeft = Math.max(0f, (deadline - day) / 30f);
		float budget = fund + perMonth * monthsLeft;
		// the fuel and the supplies away are summed over the prongs too (hw42a: priced one at a time, the
		// first prong's passage took the whole stock and the held one could not sail on its day)
		float fuelStock = ThreatFuel.stock(), fuelBudget = fuelStock + Math.max(0f, ThreatFuel.perMonth()) * monthsLeft;
		// ...and the supplies the prongs burn away: the free stock and the spare flow over the trip, as canSustain
		// allows - not the flow to the deadline (hw46: the hive banks none of its spare, so a campaign waiting on
		// it waited forever); what the set wants beyond that is booked as demand for the planner
		float suppliesStock = ThreatReach.freeStock(), suppliesFlow = Math.max(0f, Math.min(1e9f, ThreatReach.spare()));
		float supplies = 0f, suppliesShort = 0f;
		List<Prong> campaign = new ArrayList<Prong>();
		float cost = 0f, fuel = 0f, away = 0f, longest = 0f, spare = 0f;
		// the spare goes to the best prongs first: each is re-priced on what the ones before it left
		Spares shared = new Spares(im);
		for (Prong p : prongs) {
			if (!price(im, p, memo, atFaction.get(p.target.getFactionId()), shared)) continue;
			if (cost + p.cost > budget || fuel + p.fuel > fuelBudget) continue;
			float need = p.supplies();
			float keeps = suppliesStock + suppliesFlow * Math.max(1f, Math.max(longest, p.days)) / 30f;
			if (supplies + need > keeps) {
				// the first prong the supplies leave out is the planner's to answer (one a pass)
				if (suppliesShort <= 0f) suppliesShort = supplies + need - keeps;
				continue;
			}
			campaign.add(p);
			shared.take(p);
			cost += p.cost;
			fuel += p.fuel;
			supplies += need;
			away += p.awayFP();
			longest = Math.max(longest, p.days);
		}
		// fewer prongs at a faction than were priced: each meets a bigger share of its navy - re-price
		// the set, and drop from its tail while the means no longer pay it
		Map<String, Integer> inCampaign = new HashMap<String, Integer>();
		for (Prong p : campaign) {
			Integer n = inCampaign.get(p.target.getFactionId());
			inCampaign.put(p.target.getFactionId(), n == null ? 1 : n + 1);
		}
		while (!campaign.isEmpty()) {
			// the spare is shared out in the order the prongs will sail, the farthest first: each muster takes
			// the first fleets of a system's walk on its day (hw48: shared in rank order, a held prong met a bigger
			// fleet on its day than it was priced for, and its set-aside fuel did not pay the passage)
			List<Prong> bySail = new ArrayList<Prong>(campaign);
			java.util.Collections.sort(bySail, new java.util.Comparator<Prong>() {
				public int compare(Prong a, Prong b) {
					return Float.compare(b.ly, a.ly);
				}
			});
			shared = new Spares(im);
			Prong unpriced = null;
			for (Prong p : bySail) {
				if (!price(im, p, memo, inCampaign.get(p.target.getFactionId()), shared)) {
					unpriced = p;
					break;
				}
				shared.take(p);
			}
			if (unpriced != null) {
				campaign.remove(unpriced);
				continue;
			}
			cost = 0f;
			fuel = 0f;
			away = 0f;
			longest = 0f;
			supplies = 0f;
			spare = 0f;
			for (Prong p : campaign) {
				cost += p.cost;
				fuel += p.fuel;
				away += p.awayFP();
				longest = Math.max(longest, p.days);
				supplies += p.supplies();
				spare += p.plan != null ? p.plan.spareFP : 0f;
			}
			if (cost <= budget && fuel <= fuelBudget
					&& supplies <= suppliesStock + suppliesFlow * Math.max(1f, longest) / 30f) break;
			campaign.remove(campaign.size() - 1);
		}
		if (suppliesShort > 0f) ThreatFuel.noteDemand(com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES, suppliesShort);
		String where = losing ? "losing " + ThreatStance.losingSummary() + (nearLY > 0f ? ", within " + (int) nearLY + " ly"
				: ", all known") + ", horizon " + (int) horizon + " mo" : "all known";
		if (campaign.isEmpty()) {
			ThreatIncConfig.logQuiet("offensive", "Offensive: nothing the fund pays by the deadline - " + prongs.size()
					+ " target(s) " + where + ", fund " + (int) fund + " FP (+" + (int) perMonth + "/mo), "
					+ (int) monthsLeft + " month(s) left");
			if (day >= deadline) setStartDay(day);
			return;
		}
		boolean sustained = away <= ThreatReach.sustainableFP(longest);
		if ((fund < cost || fuelStock < fuel || !sustained) && day < deadline) {
			// the supplies it waits on are demand on the stock too
			if (!sustained && supplies > suppliesStock) ThreatFuel.noteDemand(com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES, supplies - suppliesStock);
			// the fuel it waits on is demand on the stock: the planner builds the plants (ThreatFuel.heldShort)
			if (fuelStock < fuel && ThreatIncConfig.strikeWaitBooksFuel()) ThreatFuel.heldShort("the offensive", fuel);
			ThreatIncConfig.log("Offensive: saving for " + campaign.size() + " of " + prongs.size() + " target(s) " + where
					+ ", " + (int) cost + " FP from the fund with " + (int) spare + " FP of spare swarms, " + (int) fuel + " fuel and " + (int) supplies + " supplies (" + (!sustained ? "not yet kept, " : "") + (int) suppliesStock + " free, +" + (int) suppliesFlow + "/mo spare); fund " + (int) fund + " FP (+" + (int) perMonth
					+ "/mo), fuel " + (int) fuelStock + " (+" + (int) ThreatFuel.perMonth() + "/mo), launch in "
					+ (int) Math.ceil(Math.max(Math.max(0f, cost - fund) / Math.max(1f, perMonth),
							Math.max(0f, fuel - fuelStock) / Math.max(1f, ThreatFuel.perMonth())))
					+ " month(s), deadline in " + (int) monthsLeft + "; first " + names(campaign, 4));
			return;
		}

		// launch: every prong of the campaign the fund pays, the most valuable first - the farthest
		// sails today and the rest wait so all arrive together (the user, 2026-10-07): a prong's
		// bill leaves the fund now and comes back the day it sails (poll)
		float arriveIn = 0f;
		for (Prong p : campaign) arriveIn = Math.max(arriveIn, arrival(p.ly));
		List<Prong> go = new ArrayList<Prong>();
		float fundLeft = ThreatColonyManager.strikeFund(), fuelLeft = ThreatFuel.stock();
		for (Prong p : campaign) {
			if (p.cost > fundLeft + 0.5f || p.fuel > fuelLeft + 0.5f) continue;
			if (IncursionManager.isActiveStrikeTarget(p.target)) continue;
			fundLeft -= p.cost;
			fuelLeft -= p.fuel;
			go.add(p);
		}
		int sent = 0, held = 0;
		float sentFP = 0f, sentSpare = 0f;
		StringBuilder at = new StringBuilder();
		try {
			// the held prongs first: their bills, fuel and garrison swarms set aside until their day (earmarked);
			// then each prong that sails now, with the spare the others leave it
			PENDING.clear();
			for (Prong p : go) {
				if (arriveIn - arrival(p.ly) < 1f) addEarmark(PENDING, p.plan, 1);
			}
			for (int round = 0; round < 2; round++) {
				for (Prong p : go) {
					float wait = arriveIn - arrival(p.ly);
					if ((wait >= 1f) != (round == 0)) continue;
					if (wait >= 1f) {
						// its bill and its passage fuel are set aside until its day (neither spent nor demanded yet)
						ThreatColonyManager.addStrikeFund(-p.cost);
						ThreatFuel.setStock(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, ThreatFuel.stock() - p.fuel);
						schedule().add(p.target.getId() + "|" + p.staging.getId() + "|" + p.source.getId() + "|"
								+ (day + wait) + "|" + p.cost + "|" + p.fuel + "|" + p.expected + "|" + earmark(p.plan));
						held++;
					} else {
						addEarmark(PENDING, p.plan, -1);
						if (im.launchStrike(p.staging, p.source, p.target, p.expected) == null) continue;
						if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(p.source.getId());
						sent++;
					}
					sentFP += p.fp;
					sentSpare += p.plan != null ? p.plan.spareFP : 0f;
					if (at.length() > 0) at.append(", ");
					at.append(p.target.getName()).append(" (").append(p.target.getFactionId()).append(", ").append((int) p.ly)
							.append(" ly, ").append((int) p.fp).append(" FP, ").append((int) p.cost).append(" from the fund, for ")
							.append((int) p.expected).append(" expected").append(wait >= 1f ? ", in " + (int) wait + " d" : "")
							.append(")");
				}
			}
		} finally {
			PENDING.clear();
		}
		if (sent + held == 0) {
			// nothing sailed (each prong's own launch says why): the campaign stands, saved for, until its deadline
			if (day >= deadline) setStartDay(day);
			ThreatIncConfig.log("Offensive: none of " + campaign.size() + " planned prong(s) could sail today (" + where
					+ "); fund " + (int) ThreatColonyManager.strikeFund() + " FP; first " + names(campaign, 4));
			return;
		}
		setStartDay(day);
		ThreatIncConfig.log("Offensive launched: " + sent + " strike(s) now and " + held + " to follow, of " + campaign.size()
				+ " planned, " + (int) sentFP + " FP (" + (int) sentSpare + " of spare swarms), arriving together in ~"
				+ (int) arriveIn + " days (" + where
				+ (day >= deadline && fund < cost ? ", at the deadline" : "") + "); fund left "
				+ (int) ThreatColonyManager.strikeFund() + " FP; at " + at);
	}

	/**
	 * Prices the prong for the answer it expects: the day's defence seen there, plus the faction's
	 * navy seen elsewhere (ThreatSwarmIntel.knownNavyFP) shared among the nAtFaction prongs at that
	 * faction, times what the faction's worlds have met earlier strikes with over what they were
	 * sized for (responseRatio); stagedPlan against an unlimited bank and the spare the pass leaves
	 * it (Spares). False when nothing pays it.
	 */
	protected static boolean price(IncursionManager im, Prong p, Map<String, float[]> memo, Integer nAtFaction, Spares spares) {
		String f = p.target.getFactionId();
		int n = nAtFaction != null ? Math.max(1, nAtFaction) : 1;
		float navy = ThreatSwarmIntel.knownNavyFP(f, p.target.getStarSystem().getId());
		p.expected = (p.def + navy / n) * ThreatSwarmIntel.responseRatio(f);
		IncursionManager.StagedPlan plan = im.stagedPlan(p.staging, p.source, p.target, memo, null, spares.left(p.source),
				Float.MAX_VALUE, p.expected);
		// a prong of spare swarms alone can cost the fund nothing
		if (plan == null || plan.fp <= 0f) return false;
		p.plan = plan;
		p.cost = plan.bankFP;
		p.fp = plan.fp;
		p.fuel = ThreatFuel.passage(plan.fp, p.ly, true);
		p.days = ThreatReach.strikeDays(p.ly);
		return true;
	}

	// ------------------------------------------------------------------
	// the garrison swarms a prong will take, set aside until it sails
	// ------------------------------------------------------------------

	/** During a launch: the garrison fleets the prongs yet to sail today were given (hive system id -> fleets). */
	protected static final Map<String, Integer> PENDING = new HashMap<String, Integer>();

	/** "systemId:fleets;..." - the garrison fleets the plan takes from each hive system. */
	protected static String earmark(IncursionManager.StagedPlan plan) {
		StringBuilder sb = new StringBuilder();
		if (plan == null) return "";
		for (int i = 0; i < plan.from.size(); i++) {
			if (plan.from.get(i).getStarSystem() == null || plan.counts.get(i) <= 0) continue;
			if (sb.length() > 0) sb.append(';');
			sb.append(plan.from.get(i).getStarSystem().getId()).append(':').append(plan.counts.get(i));
		}
		return sb.toString();
	}

	/** Adds (sign 1) or takes back (-1) the plan's garrison fleets in a hive system id -> fleets map. */
	protected static void addEarmark(Map<String, Integer> to, IncursionManager.StagedPlan plan, int sign) {
		if (plan == null) return;
		for (int i = 0; i < plan.from.size(); i++) {
			if (plan.from.get(i).getStarSystem() == null) continue;
			String id = plan.from.get(i).getStarSystem().getId();
			Integer had = to.get(id);
			int n = (had != null ? had : 0) + sign * plan.counts.get(i);
			if (n > 0) to.put(id, n);
			else to.remove(id);
		}
	}

	/**
	 * Garrison fleets of a hive system's spare that the held prongs will take on their day (and,
	 * during a launch, the prongs yet to sail): no other strike musters them meanwhile
	 * (IncursionManager.stagedSpares). A held prong re-plans on its day with what is there.
	 */
	public static int earmarked(String systemId) {
		if (systemId == null) return 0;
		Integer pending = PENDING.get(systemId);
		int n = pending != null ? pending : 0;
		for (String e : schedule()) {
			String[] f = e.split("\\|");
			if (f.length < 8 || f[7].isEmpty()) continue;
			for (String part : f[7].split(";")) {
				int c = part.lastIndexOf(':');
				if (c <= 0 || !systemId.equals(part.substring(0, c))) continue;
				try {
					n += Integer.parseInt(part.substring(c + 1));
				} catch (NumberFormatException x) {
					// a malformed earmark sets nothing aside
				}
			}
		}
		return n;
	}

	/** Days from launch to the target: the muster and the crossing at the board's estimated speed. */
	protected static float arrival(float ly) {
		return ThreatReach.STRIKE_PREP_DAYS + ThreatReach.days(ly);
	}

	// ------------------------------------------------------------------
	// the prongs held back to arrive with the farthest
	// ------------------------------------------------------------------

	protected static final String KEY_SCHEDULE = "threatinc_offensiveSchedule";

	/** "targetId|stagingId|sourceSystemId|launchDay|cost|fuel|expected|earmark" per prong waiting its day (earmark: earmarked). */
	@SuppressWarnings("unchecked")
	protected static List<String> schedule() {
		Object v = Global.getSector().getPersistentData().get(KEY_SCHEDULE);
		if (v instanceof List) return (List<String>) v;
		List<String> list = new ArrayList<String>();
		Global.getSector().getPersistentData().put(KEY_SCHEDULE, list);
		return list;
	}

	/** Whether a prong at the world waits its day (no second strike is planned at it). */
	public static boolean scheduled(MarketAPI market) {
		if (market == null) return false;
		for (String e : schedule()) {
			if (e.startsWith(market.getId() + "|")) return true;
		}
		return false;
	}

	/** Daily (IncursionManager.advance): every prong whose day has come sails, its bill back in the fund for launchStrike to draw. */
	public static void poll() {
		if (IncursionManager.instance == null || schedule().isEmpty()) return;
		float day = ThreatPosture.today();
		for (String e : new ArrayList<String>(schedule())) {
			String[] f = e.split("\\|");
			if (f.length < 5) {
				schedule().remove(e);
				continue;
			}
			float launchDay, cost, fuel, expected;
			try {
				launchDay = Float.parseFloat(f[3]);
				cost = Float.parseFloat(f[4]);
				fuel = f.length > 5 ? Float.parseFloat(f[5]) : 0f;
				expected = f.length > 6 ? Float.parseFloat(f[6]) : Float.NaN;
			} catch (NumberFormatException x) {
				schedule().remove(e);
				continue;
			}
			if (day < launchDay) continue;
			schedule().remove(e);
			ThreatColonyManager.addStrikeFund(cost);
			if (fuel > 0f) ThreatFuel.setStock(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, ThreatFuel.stock() + fuel);
			MarketAPI target = Global.getSector().getEconomy().getMarket(f[0]);
			MarketAPI staging = Global.getSector().getEconomy().getMarket(f[1]);
			StarSystemAPI source = Global.getSector().getStarSystem(f[2]);
			String name = target != null ? target.getName() : f[0];
			if (target == null || staging == null || source == null || target.getStarSystem() == null
					|| !IncursionManager.isStrikeableWorld(target) || IncursionManager.isActiveStrikeTarget(target)
					|| !ThreatIncData.colonyMarkets().containsKey(source.getId())) {
				ThreatIncConfig.log("Offensive: the prong at " + name + " is off - target or staging gone; " + (int) cost
						+ " FP back in the fund");
				continue;
			}
			if (IncursionManager.instance.launchStrike(staging, source, target, expected) == null) {
				ThreatIncConfig.log("Offensive: the prong at " + name + " cannot sail today; " + (int) cost
						+ " FP back in the fund");
				continue;
			}
			if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(source.getId());
			ThreatIncConfig.log("Offensive: the prong at " + name + " sails on its day");
		}
	}

	/** The hive system nearest the target that can stage a strike, or null. */
	protected static String nearest(MarketAPI target, Map<String, StarSystemAPI> sources) {
		if (target.getStarSystem() == null) return null;
		String best = null;
		float bestLY = Float.MAX_VALUE;
		for (Map.Entry<String, StarSystemAPI> e : sources.entrySet()) {
			float ly = Misc.getDistanceLY(e.getValue().getLocation(), target.getStarSystem().getLocation());
			if (ly < bestLY) {
				bestLY = ly;
				best = e.getKey();
			}
		}
		return best;
	}

	protected static String names(List<Prong> prongs, int max) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < prongs.size() && i < max; i++) {
			if (i > 0) sb.append(", ");
			sb.append(prongs.get(i).target.getName()).append(" ").append((int) prongs.get(i).cost);
		}
		if (prongs.size() > max) sb.append(" +").append(prongs.size() - max);
		return sb.toString();
	}
}
