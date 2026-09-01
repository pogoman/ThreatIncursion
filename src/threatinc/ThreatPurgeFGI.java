package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
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
 * <li>While the war-strata fight at full effect - tactical bombardment,
 * clamped to the hive-short disruption the player's passes also achieve;
 * disrupted defenses fire at half effect, opening the door for...</li>
 * <li>...COMMANDO RAIDS against the hive's organs (Nexus, Fabrication Core,
 * port, forge), the same targeted disruption a player raid applies - feeding
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

		// tactical pass while the war-strata still fight at full effect
		float tacDays = ThreatIncConfig.hiveTacDisruptDays();
		Map<Industry, Float> tagged = new LinkedHashMap<Industry, Float>();
		boolean needTac = false;
		for (Industry ind : market.getIndustries()) {
			if (!ind.getSpec().hasTag(Industries.TAG_TACTICAL_BOMBARDMENT)) continue;
			tagged.put(ind, ind.getDisruptedDays());
			if (ind.getDisruptedDays() < tacDays * 0.5f) needTac = true;
		}
		if (needTac && !tagged.isEmpty()) {
			new MarketCMD(market.getPrimaryEntity())
					.doBombardment(getFaction(), BombardType.TACTICAL);
			// the static path writes vanilla's 365-day overwrite; clamp to the
			// hive-short duration without erasing longer existing disruption
			StringBuilder names = new StringBuilder();
			for (Map.Entry<Industry, Float> entry : tagged.entrySet()) {
				float dur = tacDays * StarSystemGenerator.getNormalRandom(getRandom(), 1f, 1.25f);
				entry.getKey().setDisrupted(Math.max(entry.getValue(), dur));
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

	/**
	 * The organ whose disruption hurts the hive most, favoring ones not
	 * already down: the Nexus (silences fabrication and staging), then the
	 * Fabrication Core (starves the whole machinery chain and forces decline),
	 * then the port (cuts imports network-wide), then the forge.
	 */
	protected Industry pickRaidTarget(MarketAPI market) {
		List<Industry> priority = new ArrayList<Industry>();
		priority.add(market.getIndustry(ThreatColonyManager.SWARM_NEXUS));
		priority.add(market.getIndustry(ThreatColonyManager.FABRICATION_CORE));
		Industry port = market.getIndustry(Industries.MEGAPORT);
		if (port == null) port = market.getIndustry(Industries.SPACEPORT);
		priority.add(port);
		priority.add(ThreatColonyManager.getForge(market));

		// first organ still (mostly) functional...
		for (Industry ind : priority) {
			if (ind == null) continue;
			if (ind.getDisruptedDays() < 30f) return ind;
		}
		// ...else the one closest to recovering
		Industry best = null;
		for (Industry ind : priority) {
			if (ind == null) continue;
			if (best == null || ind.getDisruptedDays() < best.getDisruptedDays()) best = ind;
		}
		return best;
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
