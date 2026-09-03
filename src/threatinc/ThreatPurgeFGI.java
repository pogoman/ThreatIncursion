package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;

/**
 * NPC siege expedition against Threat colonies - the successor to the plain
 * saturation-purge {@code GenericRaidFGI}. Leaves {@code params.bombardment}
 * null so {@code FGRaidAction.performRaid} routes every pass (live-fleet and
 * autoresolve alike) through {@link #doCustomRaidAction}, which applies the
 * siege doctrine:
 *
 * <ul>
 * <li>While the war-strata still fight above the soften floor
 * (siegeDefenseSoftenFloor) - repeated tactical bombardment, each pass a
 * hive-short disruption that STACKS with the last (the same additive softening
 * a player's passes achieve), wearing the defenses further every time
 * (ThreatColonyManager.disruptedDefenseResilience) until they drop below the
 * floor - opening the door for...</li>
 * <li>...COMMANDO RAIDS against whatever on the world takes the most from
 * the hive ({@link #pickRaidTarget}: the Core, the Nexus, a port the world
 * imports through, or an economy industry that is the hive's best source of
 * something), the same targeted disruption a player raid applies - feeding
 * {@link ThreatColonyManager#computeHealth}'s decline engine with no extra
 * plumbing.</li>
 * </ul>
 *
 * <p>No saturation, ever: bombardment cannot reduce a hive's population. The
 * expedition's job is to suppress the colony's organs until decline - the only
 * way a Threat colony dies - grinds it to collapse.
 *
 * Every pass is recorded; when the expedition ends (for any reason) a
 * {@link ThreatSiegeReportIntel} sitrep is posted detailing what was done to
 * each planet, estimated marine losses, and the colonies' current state.
 */
public class ThreatPurgeFGI extends GenericRaidFGI {

	/** One ground action taken by the expedition, for the after-action report. */
	public static class SiegeActionRecord {
		public String marketId;
		public String marketName;
		/** Player-facing action name, e.g. "Saturation bombardment". */
		public String action;
		/** Industries hit, comma-joined; null for saturation passes. */
		public String targets;
		/** Approximate days of disruption inflicted (0 if none). */
		public int disruptDays;
		public boolean success;
		/** Estimated marine casualties - NPC ground actions are abstract. */
		public int estMarinesLost;
		public int sizeBefore;
		public boolean destroyed;
		/** Campaign timestamp of the action; the sitrep posts weeks later, on the fleets' return. */
		public long timestamp;
	}

	protected List<SiegeActionRecord> siegeActions = new ArrayList<SiegeActionRecord>();
	protected boolean reportPosted = false;
	/** True for an expedition the player paid for (player-faction fleets). */
	protected boolean playerCommissioned = false;

	public ThreatPurgeFGI(GenericRaidParams params, boolean playerCommissioned) {
		super(params);
		this.playerCommissioned = playerCommissioned;
	}

	public boolean isPlayerCommissioned() {
		return playerCommissioned;
	}

	@Override
	protected Object readResolve() {
		super.readResolve();
		if (siegeActions == null) siegeActions = new ArrayList<SiegeActionRecord>();
		return this;
	}

	/**
	 * The player faction defines no personNamePrefix, so the vanilla name
	 * ("Your Your purge expedition...") and description come out mangled -
	 * both get bespoke player-facing text.
	 */
	@Override
	public String getBaseName() {
		if (playerCommissioned) return "Commissioned Purge Expedition";
		return super.getBaseName();
	}

	@Override
	protected void addBasicDescription(com.fs.starfarer.api.ui.TooltipMakerAPI info,
			float width, float height, float opad) {
		if (!playerCommissioned) {
			super.addBasicDescription(info, width, height, opad);
			return;
		}
		info.addImage(getFaction().getLogo(), width, 128, opad);
		com.fs.starfarer.api.campaign.StarSystemAPI system =
				params != null && params.raidParams != null ? params.raidParams.where : null;
		String where = system != null ? "the " + system.getNameWithLowercaseTypeShort()
				: "an infested system";
		String from = params != null && params.source != null
				? params.source.getName() : "your colony";
		info.addPara("A %s you commissioned from " + from + ", operating against the Threat "
				+ "colonies of " + where + ". The expedition is autonomous: it fights with "
				+ "your faction's doctrine and blueprints, and does not refund its fee.",
				opad, com.fs.starfarer.api.util.Misc.getHighlightColor(), getNoun());
	}

	@Override
	public boolean hasCustomRaidAction() {
		return true;
	}

	@Override
	public void doCustomRaidAction(CampaignFleetAPI fleet, MarketAPI market, float raidStr) {
		if (market == null || market.getPrimaryEntity() == null || !market.isInEconomy()) return;
		if (ThreatIncData.resolveColonyMarket(market.getId()) == null) return;

		SiegeActionRecord rec = new SiegeActionRecord();
		rec.marketId = market.getId();
		rec.marketName = market.getName();
		rec.sizeBefore = market.getSize();
		rec.timestamp = Global.getSector().getClock().getTimestamp();

		// Tactical passes soften the war-strata: keep bombing (their disruption
		// now STACKS, so each pass wears the defenses further via
		// disruptedDefenseResilience) while any defense structure still fights
		// above the soften floor. Once bombed below it - or, with wear disabled,
		// once a single pass has done all a bomb can - the expedition lands troops.
		float tacDays = ThreatIncConfig.hiveTacDisruptDays();
		float floor = ThreatIncConfig.siegeDefenseSoftenFloor();
		boolean wearActive = ThreatIncConfig.defenseWearDays() > 0f;
		Map<Industry, Float> tagged = new LinkedHashMap<Industry, Float>();
		boolean needTac = false;
		for (Industry ind : market.getIndustries()) {
			if (!ind.getSpec().hasTag(Industries.TAG_TACTICAL_BOMBARDMENT)) continue;
			tagged.put(ind, ind.getDisruptedDays());
			// a further pass only lowers resilience if wear is active, or the organ
			// is not yet disrupted (the first pass drops it to disruptedDefenseFraction);
			// without that guard a wear-off config would loop tactical passes forever
			boolean canSoftenMore = !ind.isDisrupted() || wearActive;
			if (canSoftenMore
					&& ThreatColonyManager.disruptedDefenseResilience(ind) > floor) {
				needTac = true;
			}
		}
		if (needTac && !tagged.isEmpty()) {
			new MarketCMD(market.getPrimaryEntity())
					.doBombardment(getFaction(), BombardType.TACTICAL);
			// the static doBombardment writes vanilla's 365-day overwrite; replace
			// it with an ADDITIVE hive-short pass so repeat siege bombardments
			// accumulate, exactly as a player's tactical passes now do
			StringBuilder names = new StringBuilder();
			for (Map.Entry<Industry, Float> entry : tagged.entrySet()) {
				float dur = tacDays * StarSystemGenerator.getNormalRandom(getRandom(), 1f, 1.25f);
				entry.getKey().setDisrupted(entry.getValue() + dur);
				if (names.length() > 0) names.append(", ");
				names.append(entry.getKey().getCurrentName());
			}
			market.reapplyIndustries();
			rec.action = "Tactical bombardment";
			rec.targets = names.toString();
			rec.disruptDays = (int) tacDays;
			rec.success = true;
			siegeActions.add(rec);
			ThreatIncConfig.log("Siege pass (tactical) vs " + rec.marketName
					+ ": " + rec.targets);
			return;
		}

		// defenses suppressed: put troops on the ground against the hive's organs.
		// COMBINED ARMS: the landing draws on every expedition fleet in-system,
		// not just the one whose orbit triggered the pass - one operation, one
		// ground force. Without this, no single fleet clears the 25 percent
		// effectiveness floor against a mature hive's defenses and every raid
		// pass is repulsed.
		float groundStr = combinedRaidStr(market, raidStr);
		Industry target = pickRaidTarget(market);
		if (target == null) return;
		float before = target.getDisruptedDays();
		float durMult = Global.getSettings().getFloat("punitiveExpeditionDisruptDurationMult");
		boolean ok = new MarketCMD(market.getPrimaryEntity())
				.doIndustryRaid(getFaction(), groundStr, target, durMult);
		rec.action = "Commando raid";
		rec.targets = target.getCurrentName();
		rec.success = ok;
		rec.disruptDays = ok ? (int) (target.getDisruptedDays() - before) : 0;
		rec.estMarinesLost = estimateMarineLosses(market, groundStr, ok);
		siegeActions.add(rec);
		ThreatIncConfig.log("Siege pass (raid) vs " + rec.marketName + ": "
				+ rec.targets + " (ground str " + (int) groundStr + ")"
				+ (ok ? " +" + rec.disruptDays + "d" : " - repulsed"));
	}

	/**
	 * The expedition's total ground strength against a market: summed over
	 * every live expedition fleet in the same location. Falls back to the
	 * per-fleet figure the caller received when no fleets are spawned (the
	 * autoresolve path, whose strength-derived figure is already whole-
	 * expedition scale).
	 */
	protected float combinedRaidStr(MarketAPI market, float fallback) {
		float total = 0f;
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet == null || !fleet.isAlive()) continue;
			if (market.getContainingLocation() != null
					&& fleet.getContainingLocation() != market.getContainingLocation()) {
				continue;
			}
			total += MarketCMD.getRaidStr(fleet);
		}
		if (total <= 0f) return fallback;
		return Math.max(total, fallback);
	}

	/** Raid value of a world's Fabrication Core: the kill, always the top prize. */
	public static final float RAID_VALUE_CORE = 100f;
	/** Raid value of its Swarm Nexus: silences garrison regrowth and staging. */
	public static final float RAID_VALUE_NEXUS = 60f;
	/** Raid value of its port, per growth input the world ships in (the trickle rule starves it). */
	public static final float RAID_VALUE_PORT_PER_IMPORT = 12f;

	/**
	 * What one unit of hive-wide availability of each commodity is worth
	 * taking away. Fuel is reach and hulls are garrisons; metals feed the
	 * forges; the raw inputs sit further up the chain.
	 */
	protected static float raidWeight(String commodityId) {
		if (Commodities.FUEL.equals(commodityId) || Commodities.SHIPS.equals(commodityId)) return 6f;
		if (Commodities.METALS.equals(commodityId) || Commodities.RARE_METALS.equals(commodityId)) return 4f;
		if (Commodities.HEAVY_MACHINERY.equals(commodityId)) return 2f;
		return 3f; // ore, rare ore, volatiles
	}

	/**
	 * The industry whose disruption takes the most from the hive, scored on
	 * this world right now - the same reasoning the mission board applies:
	 *
	 * <ul>
	 * <li>Fabrication Core: the kill (RAID_VALUE_CORE). Swarm Nexus: the
	 * garrison and staging (RAID_VALUE_NEXUS).</li>
	 * <li>Port: worth what the world ships in - each growth input it demands
	 * but does not make (RAID_VALUE_PORT_PER_IMPORT), since a disrupted port
	 * cuts shipping to a trickle. High on a forge world importing its metals,
	 * nothing on a self-sufficient mine.</li>
	 * <li>Economy industries: worth what they take from the hive. Availability
	 * is best-single-source, so a mine, refinery, fuel plant or forge only
	 * matters when THIS world is the hive's largest producer of something
	 * another hive world wants: value = weight x the gap to the second-best
	 * producer, doubled when there is no second. One of ten equal mines scores
	 * zero and is left alone; the only fuel plant, or the one big one, is hunted
	 * - which is exactly what the hive's redundancy is there to blunt.</li>
	 * </ul>
	 *
	 * Anything already carrying fresh disruption is skipped, so damage spreads
	 * across the world's organs instead of stacking on one; when every
	 * candidate is down, the one closest to recovering is hit again.
	 */
	protected Industry pickRaidTarget(MarketAPI market) {
		Industry best = null;
		float bestScore = -1f;
		Industry soonest = null;
		for (Industry ind : market.getIndustries()) {
			float score = raidValue(market, ind);
			if (score <= 0f) continue;
			if (ind.getDisruptedDays() >= 1f) {
				if (soonest == null || ind.getDisruptedDays() < soonest.getDisruptedDays()) soonest = ind;
				continue;
			}
			if (score > bestScore) {
				bestScore = score;
				best = ind;
			}
		}
		return best != null ? best : soonest;
	}

	/** This industry's raid value on this world, 0 for anything not worth a landing. */
	protected float raidValue(MarketAPI market, Industry ind) {
		if (ind == null || ind.isBuilding()) return 0f;
		String id = ind.getId();
		if (ThreatColonyManager.FABRICATION_CORE.equals(id)) return RAID_VALUE_CORE;
		if (ThreatColonyManager.SWARM_NEXUS.equals(id)) return RAID_VALUE_NEXUS;
		if (Industries.SPACEPORT.equals(id) || Industries.MEGAPORT.equals(id)) {
			int imports = 0;
			for (String input : ThreatColonyManager.growthInputs()) {
				CommodityOnMarketAPI com = market.getCommodityData(input);
				if (com != null && com.getMaxDemand() > 0 && com.getMaxSupply() <= 0) imports++;
			}
			return imports * RAID_VALUE_PORT_PER_IMPORT;
		}
		// economy industries: what the hive loses when this world's output drops out
		float value = 0f;
		for (MutableCommodityQuantity q : ind.getAllSupply()) {
			int made = q.getQuantity().getModifiedInt();
			if (made <= 0) continue;
			String c = q.getCommodityId();
			int secondBest = 0;
			boolean wanted = false;
			for (MarketAPI other : ThreatIncData.getAllLiveColonyMarkets()) {
				if (other == market) continue;
				CommodityOnMarketAPI com = other.getCommodityData(c);
				if (com == null) continue;
				if (com.getMaxDemand() > 0) wanted = true;
				secondBest = Math.max(secondBest, com.getMaxSupply());
			}
			if (!wanted || secondBest >= made) continue;
			float gap = secondBest > 0 ? made - secondBest : made * 2f;
			value += raidWeight(c) * gap;
		}
		return value;
	}

	/**
	 * Casualty ESTIMATE for the after-action report - NPC ground actions are
	 * abstract (no marines are actually simulated), so this mirrors the shape
	 * of the player-side loss math: committed strength, raid effectiveness
	 * against the hive's defenses, hazard, and the hive counter-swarm
	 * multiplier.
	 */
	protected int estimateMarineLosses(MarketAPI market, float raidStr, boolean success) {
		float marines = raidStr * 3f; // marine-equivalents behind this ground strength
		float eff = MarketCMD.getRaidEffectiveness(market, raidStr);
		float frac = 0.16f * Math.max(1f, market.getHazardValue())
				* ThreatIncConfig.hiveMarineLossMult();
		frac *= 1.5f - Math.min(1f, eff); // harder fights bleed more
		if (!success) frac *= 0.5f; // repulsed at the perimeter, not in the depths
		if (frac > 0.8f) frac = 0.8f;
		if (frac < 0f) frac = 0f;
		return Math.round(marines * frac);
	}

	/**
	 * The sitrep goes out the moment the siege operations finish - when the
	 * payload action completes and the fleets turn for home - not when they
	 * arrive weeks later. Posted on return, every clock it described had run
	 * out before anyone read it ("disrupted ~15 days" beside a colony that
	 * was already nominal again). An expedition aborted or destroyed before
	 * finishing still reports from notifyEnding.
	 */
	@Override
	protected void notifyActionFinished(com.fs.starfarer.api.impl.campaign.intel.group.FGAction action) {
		super.notifyActionFinished(action);
		if (action != null && action == raidAction && !isAborted() && !isFailed()) {
			postSiegeReport();
		}
	}

	@Override
	protected void notifyEnding() {
		super.notifyEnding();
		postSiegeReport();
	}

	/** Posts the after-action sitrep once, however the expedition ended. */
	protected void postSiegeReport() {
		if (reportPosted) return;
		reportPosted = true;

		String outcome;
		if (isAborted()) {
			outcome = "aborted";
		} else if (isFailed()) {
			outcome = "destroyed";
		} else {
			outcome = "completed";
		}

		String systemName = params != null && params.raidParams != null
				&& params.raidParams.where != null
				? params.raidParams.where.getNameWithLowercaseType() : "an infested system";

		ThreatSiegeReportIntel report = new ThreatSiegeReportIntel(
				getFaction() != null ? getFaction().getId() : null, systemName,
				new ArrayList<SiegeActionRecord>(siegeActions), outcome);
		Global.getSector().getIntelManager().addIntel(report);
		ThreatIncConfig.log("Siege expedition " + outcome + " (" + siegeActions.size()
				+ " ground actions) - sitrep posted.");
	}
}
