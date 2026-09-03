package threatinc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.RuleBasedDialog;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.CustomRepImpact;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.DebugFlags;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;

/**
 * MarketCMD override implementing the hive-siege rules for Threat colonies.
 *
 * <p>Every hive lives deep underground behind ground defenses anchored to
 * colony size (see {@link SwarmNexus}: hiveDefensePerSize per size, immune to
 * unrest, multiplied by the defense industries) - so bombardment is priced off
 * a number that stays punishing for the colony's whole life:
 *
 * <ul>
 * <li><b>Saturation</b>: fuel cost = the full ground-defense strength.
 * Disrupts every industry for only ~hiveSatDisruptDays (the buried strata
 * reknit fast) and NEVER touches colony size - bombardment cannot shrink or
 * destroy a hive. Its role is suppression: keep the organs down and the
 * decline engine (ThreatColonyManager) does the killing.</li>
 * <li><b>Tactical</b>: costs only hiveTacCostFraction of the defense figure
 * and disrupts the exposed war-strata for ~hiveTacDisruptDays - the efficient
 * way to suppress defenses (which fire at half effect while disrupted).</li>
 * <li><b>Marine raids</b>: vanilla - the deepest cut, at a casualty price.</li>
 * </ul>
 *
 * <p>The only way a Threat colony dies is decline: its population falling to
 * size 1 under sustained disruption or shortages.
 *
 * <p>Also waives the saturation-bombardment atrocity penalty when the bombed
 * colony belongs to the Threat: vanilla {@code bombardSaturation} builds
 * {@code temp.willBecomeHostile} from each faction's caresAboutAtrocities
 * flag, never checking who owned the target.
 *
 * <p>Wired from this mod's {@code rules.csv}: higher-scored overrides of the
 * vanilla {@code mktBombard*} rules invoke this class instead
 * ({@code DialogOptionSelected} fires only the best-scoring rule). For
 * non-Threat targets - or with the mod off - everything delegates to vanilla.
 *
 * <p>{@code temp} is shared state stored in market memory ($MarketCMD_temp), so
 * what one rule invocation writes (e.g. the tactical cost set in
 * {@code bombardTactical}) is exactly what a later invocation's
 * {@code bombardConfirm} reads. The confirm-screen "Never mind" routes through
 * VANILLA {@code bombardMenu} (its rule is not overridden), which resets
 * {@code temp.bombardCost} to base - harmless, because re-selecting either
 * bombardment type always re-enters our overrides, which recompute it.
 */
public class ThreatincMarketCMD extends MarketCMD {

	protected boolean isThreatTarget() {
		return ThreatIncConfig.enabled() && market != null
				&& Factions.THREAT.equals(market.getFactionId());
	}

	protected boolean waiveAtrocity() {
		return ThreatIncConfig.enabled() && ThreatIncConfig.bombardNoAtrocity()
				&& market != null && market.getFaction() != null
				&& Factions.THREAT.equals(market.getFaction().getId());
	}

	/** Saturation bill: the full defense strength (times the config scale). */
	protected int satCost() {
		int base = getBombardmentCost(market, playerFleet);
		return Math.max(2, Math.round(base * ThreatIncConfig.hiveBombardCostMult()));
	}

	/** Tactical bill: a fraction of the defense strength. */
	protected int tacCost() {
		int base = getBombardmentCost(market, playerFleet);
		return Math.max(2, Math.round(base * ThreatIncConfig.hiveTacCostFraction()));
	}

	@Override
	protected void bombardMenu() {
		super.bombardMenu();
		if (!isThreatTarget()) return;

		int tac = tacCost();
		text.addPara("Surface returns are thin. Beneath the crust the auspex paints "
				+ "kilometers of fabrication strata - the hive lives %s, and no "
				+ "bombardment can burn it out. A tactical strike on the exposed "
				+ "war-strata would cost only %s fuel and suppress its defenses; "
				+ "saturating the whole world buys days of disruption at the full "
				+ "price above.", Misc.getHighlightColor(), "deep underground",
				"" + tac);

		// vanilla gates both options at the FULL cost; tactical is cheaper
		// against the hive, so re-open it when the fleet can afford that much
		int fuel = (int) playerFleet.getCargo().getFuel();
		if (fuel >= tac || DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(BOMBARD_TACTICAL, true);
			options.setTooltip(BOMBARD_TACTICAL, null);
		}
	}

	/**
	 * Tactical bombardment against the hive: reduced fuel cost and hive-specific
	 * disruption duration. Unlike vanilla, there is NO already-disrupted skip
	 * window - already-disrupted war-strata stay valid targets so repeat passes
	 * can grind the defenses down. The disruption STACKS additively (see
	 * bombardConfirm), so each pass drives getDisruptedDays() higher and, via
	 * ThreatColonyManager.disruptedDefenseResilience, wears the defensive bonus
	 * further toward zero. A player with the fuel can plan a sustained
	 * suppression campaign; the size-anchored base defense (SwarmNexus) still
	 * remains, so bombardment never becomes free.
	 */
	@Override
	protected void bombardTactical() {
		if (!isThreatTarget()) {
			super.bombardTactical();
			return;
		}

		temp.bombardType = BombardType.TACTICAL;
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);

		int dur = (int) ThreatIncConfig.hiveTacDisruptDays();

		// no already-disrupted skip: repeat passes are the intended way to soften
		// the war-strata, and their disruption stacks (see bombardConfirm)
		List<Industry> targets = new ArrayList<Industry>();
		for (Industry ind : market.getIndustries()) {
			if (ind.getSpec().hasTag(Industries.TAG_TACTICAL_BOMBARDMENT)) {
				targets.add(ind);
			}
		}
		temp.bombardmentTargets.clear();
		temp.bombardmentTargets.addAll(targets);

		if (targets.isEmpty()) {
			text.addPara(market.getName() + " does not have any military targets "
					+ "that would be affected by a tactical bombardment.");
			addBombardNeverMindOption();
			return;
		}

		// tactical is the cheap, surgical option against the hive; recomputed
		// here on every entry (see class doc re the never-mind cost reset)
		temp.bombardCost = tacCost();

		int fuel = (int) playerFleet.getCargo().getFuel();
		text.addPara("A tactical bombardment will crater the hive's exposed war-strata, "
				+ "disrupting the following military targets for approximately %s days - "
				+ "and while they are silenced, the colony's ground defenses slacken with "
				+ "them:", Misc.getHighlightColor(), "" + dur);
		for (Industry ind : targets) {
			text.addPara("    " + ind.getCurrentName());
		}
		text.addPara("The bombardment requires %s fuel. You have %s fuel.",
				Misc.getHighlightColor(), "" + temp.bombardCost, "" + fuel);

		addBombardConfirmOptions();

		if (fuel < temp.bombardCost && !DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(BOMBARD_CONFIRM, false);
			options.setTooltip(BOMBARD_CONFIRM, "Not enough fuel.");
		}
	}

	@Override
	protected void bombardSaturation() {
		if (!isThreatTarget()) {
			super.bombardSaturation();
			return;
		}

		temp.bombardType = BombardType.SATURATION;

		// hostile list: owner-only when the atrocity waiver is on; otherwise the
		// vanilla caresAboutAtrocities sweep
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);
		List<FactionAPI> nonHostile = new ArrayList<FactionAPI>();
		if (!waiveAtrocity()) {
			for (FactionAPI other : Global.getSector().getAllFactions()) {
				if (temp.willBecomeHostile.contains(other)) continue;
				if (other.getCustomBoolean(Factions.CUSTOM_CARES_ABOUT_ATROCITIES)) {
					temp.willBecomeHostile.add(other);
					if (!other.isHostileTo(Factions.PLAYER)) nonHostile.add(other);
				}
			}
		}

		// disruption from a pass is short against the buried hive, so the
		// already-disrupted skip window must be short too or one pass would
		// blank the target list for a year
		int dur = (int) ThreatIncConfig.hiveSatDisruptDays();
		List<Industry> targets = new ArrayList<Industry>();
		for (Industry ind : market.getIndustries()) {
			if (!ind.getSpec().hasTag(Industries.TAG_NO_SATURATION_BOMBARDMENT)) {
				if (ind.getDisruptedDays() >= dur * 0.8f) continue;
				targets.add(ind);
			}
		}
		temp.bombardmentTargets.clear();
		temp.bombardmentTargets.addAll(targets);

		// the full defense bill; recomputed on every entry, which also
		// neutralizes the never-mind cost-reset bypass
		temp.bombardCost = satCost();

		int fuel = (int) playerFleet.getCargo().getFuel();
		text.addPara("The hive is buried too deep for any bombardment to kill or even "
				+ "thin its population. A saturation pass will disrupt every surface "
				+ "operation for a matter of %s - the strata below reknit quickly. To "
				+ "destroy this colony, keep its organs suppressed or its supply lines "
				+ "cut until the hive itself withers.", Misc.getHighlightColor(), "days");

		if (waiveAtrocity()) {
			text.addPara("An atrocity by any other measure - but no power in the civilized "
					+ "sector mourns the swarm. Only the machines themselves will mark the "
					+ "loss.");
		} else if (nonHostile.isEmpty()) {
			text.addPara("An atrocity of this scale can not be hidden, but any factions that "
					+ "would be dismayed by such actions are already hostile to you.");
		} else {
			text.addPara("An atrocity of this scale can not be hidden, and will make the "
					+ "following factions hostile:");
			for (FactionAPI fac : nonHostile) {
				text.addPara("    " + Misc.ucFirst(fac.getDisplayName()), fac.getBaseUIColor());
			}
		}

		text.addPara("The bombardment requires %s fuel. You have %s fuel.",
				Misc.getHighlightColor(), "" + temp.bombardCost, "" + fuel);

		addBombardConfirmOptions();

		if (fuel < temp.bombardCost && !DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(BOMBARD_CONFIRM, false);
			options.setTooltip(BOMBARD_CONFIRM, "Not enough fuel.");
		}
	}

	@Override
	protected void bombardConfirm() {
		// defense in depth: even if some other path populated the hostile list
		// (e.g. vanilla bombardSaturation ran via a mod conflict), strip every
		// third party before the reputation hit is applied
		if (waiveAtrocity() && temp.bombardType == BombardType.SATURATION
				&& temp.willBecomeHostile != null) {
			for (Iterator<FactionAPI> it = temp.willBecomeHostile.iterator(); it.hasNext();) {
				FactionAPI curr = it.next();
				if (curr == null || !Factions.THREAT.equals(curr.getId())) it.remove();
			}
			if (temp.willBecomeHostile.isEmpty()) {
				temp.willBecomeHostile.add(Global.getSector().getFaction(Factions.THREAT));
			}
			ThreatIncConfig.log("Waived atrocity penalty for saturation bombardment of "
					+ market.getName() + ".");
		}

		if (isThreatTarget() && temp.bombardType == BombardType.SATURATION) {
			threatSatConfirm();
			return;
		}

		if (isThreatTarget() && temp.bombardType == BombardType.TACTICAL) {
			// full vanilla confirm flow (fuel, rep, unrest, listener), then replace
			// the 365-day disruption it wrote down with an ADDITIVE hive-short pass:
			// each bomb STACKS its duration on top of whatever disruption already
			// stood (a prior pass or a raid), so repeat passes drive the disruption
			// clock - and thus disruptedDefenseResilience - steadily toward zero.
			// Uncapped by design: enough fuel buys total suppression. reapply below
			// makes the softened defenses take effect immediately, not on the next
			// colony poll.
			Map<Industry, Float> pre = new LinkedHashMap<Industry, Float>();
			for (Industry ind : temp.bombardmentTargets) {
				pre.put(ind, ind.getDisruptedDays());
			}
			super.bombardConfirm();
			for (Map.Entry<Industry, Float> entry : pre.entrySet()) {
				float dur = ThreatIncConfig.hiveTacDisruptDays()
						* StarSystemGenerator.getNormalRandom(getRandom(), 1f, 1.25f);
				entry.getKey().setDisrupted(entry.getValue() + dur);
			}
			market.reapplyIndustries();
			return;
		}

		super.bombardConfirm();
	}

	/**
	 * Saturation bombardment of a hive. Mirrors vanilla
	 * {@code MarketCMD.bombardConfirm} (0.98a) with the siege differences:
	 * disruption is hive-short (and never erases longer existing disruption),
	 * and there is NO size reduction and NO destroy branch - bombardment
	 * cannot kill a hive; only decline can. The atrocity counters are skipped
	 * under the waiver.
	 */
	protected void threatSatConfirm() {
		if (temp.bombardType == null) {
			bombardNeverMind();
			return;
		}

		dialog.getVisualPanel().showImagePortion("illustrations", "bombard_saturation_result",
				640, 400, 0, 0, 480, 300);

		java.util.Random random = getRandom();

		if (!DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			float timeout = SATURATION_BOMBARD_TIMEOUT_DAYS;
			Misc.increaseMarketHostileTimeout(market, timeout);
			timeout *= 0.7f;
			for (MarketAPI curr : Global.getSector().getEconomy()
					.getMarkets(market.getContainingLocation())) {
				if (curr == market) continue;
				boolean cares = curr.getFaction()
						.getCustomBoolean(Factions.CUSTOM_CARES_ABOUT_ATROCITIES);
				if (curr.getFaction().isNeutralFaction()) continue;
				if (curr.getFaction().isPlayerFaction()) continue;
				if (curr.getFaction().isHostileTo(market.getFaction()) && !cares) continue;
				Misc.increaseMarketHostileTimeout(curr, timeout);
			}
		}

		addMilitaryResponse();

		playerFleet.getCargo().removeFuel(temp.bombardCost);
		AddRemoveCommodity.addCommodityLossText(Commodities.FUEL, temp.bombardCost, text);

		for (FactionAPI curr : temp.willBecomeHostile) {
			CustomRepImpact impact = new CustomRepImpact();
			impact.delta = market.getSize() * -0.01f;
			impact.ensureAtBest = RepLevel.HOSTILE;
			if (curr == faction) {
				impact.ensureAtBest = RepLevel.VENGEFUL;
			}
			Global.getSector().adjustPlayerReputation(
					new RepActionEnvelope(RepActions.CUSTOM, impact, null, text, true, true),
					curr.getId());
		}

		// no war-crime bookkeeping for exterminating the swarm
		if (!waiveAtrocity()) {
			int atrocities = (int) Global.getSector().getCharacterData()
					.getMemoryWithoutUpdate().getFloat(MemFlags.PLAYER_ATROCITIES);
			atrocities++;
			Global.getSector().getCharacterData().getMemoryWithoutUpdate()
					.set(MemFlags.PLAYER_ATROCITIES, atrocities);
			if (market.getFaction() != null) {
				com.fs.starfarer.api.campaign.rules.MemoryAPI mem =
						market.getFaction().getMemoryWithoutUpdate();
				mem.set(MemFlags.FACTION_SATURATION_BOMBARED_BY_PLAYER,
						mem.getInt(MemFlags.FACTION_SATURATION_BOMBARED_BY_PLAYER) + 1);
			}
		}

		// unrest is flavor only - hive defenses are stability-immune (SwarmNexus)
		int stabilityPenalty = getSaturationBombardmentStabilityPenalty();
		if (stabilityPenalty > 0) {
			String reason = "Recently bombarded";
			if (Misc.isPlayerFactionSetUp()) {
				reason = playerFaction.getDisplayName() + " bombardment";
			}
			RecentUnrest.get(market).add(stabilityPenalty, reason);
		}

		if (market.hasCondition(Conditions.HABITABLE)
				&& !market.hasCondition(Conditions.POLLUTION)) {
			market.addCondition(Conditions.POLLUTION);
		}

		// short disruption, and never shorter than what a raid already earned
		for (Industry curr : temp.bombardmentTargets) {
			float dur = ThreatIncConfig.hiveSatDisruptDays()
					* StarSystemGenerator.getNormalRandom(random, 1f, 1.25f);
			curr.setDisrupted(Math.max(curr.getDisruptedDays(), dur));
		}
		market.reapplyIndustries();

		text.addPara("Surface operations disrupted for a handful of days. The deep "
				+ "strata absorb the rest - the hive's population is untouched.");
		float health = ThreatIncData.lastHealth(market.getId());
		float progress = ThreatIncData.declineProgress(market.getId());
		if (progress > 0f || health < ThreatIncConfig.declineHealthThreshold()) {
			text.addPara("The colony is wounded where it matters: %s of the way to losing "
					+ "a population stratum. Keep its organs down and it will wither.",
					Misc.getNegativeHighlightColor(), (int) (progress * 100f) + "%");
		}

		// fired manually since vanilla bombardConfirm was bypassed - this keeps
		// strike recall and the atrocity rep-restore listener working
		ListenerUtil.reportSaturationBombardmentFinished(dialog, market, temp);

		if (dialog != null && dialog.getPlugin() instanceof RuleBasedDialog) {
			if (dialog.getInteractionTarget() != null
					&& dialog.getInteractionTarget().getMarket() != null) {
				Global.getSector().setPaused(false);
				dialog.getInteractionTarget().getMarket().getMemoryWithoutUpdate()
						.advance(0.0001f);
				Global.getSector().setPaused(true);
			}
			((RuleBasedDialog) dialog.getPlugin()).updateMemory();
		}

		Misc.setFlagWithReason(market.getMemoryWithoutUpdate(), MemFlags.RECENTLY_BOMBARDED,
				Factions.PLAYER, true, 30f);

		addBombardVisual(market.getPrimaryEntity());

		addBombardContinueOption();
	}
}
