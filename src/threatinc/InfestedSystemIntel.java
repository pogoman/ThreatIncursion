package threatinc;

import java.util.LinkedHashSet;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * One marker per infested system: appears under the "Threat Incursion" intel
 * tab and as an icon on the sector map at the system's location, so the
 * spread is visible at a glance.
 */
public class InfestedSystemIntel extends BaseIntelPlugin {

	protected String systemId;

	public InfestedSystemIntel(String systemId) {
		this.systemId = systemId;
	}

	public String getSystemId() {
		return systemId;
	}

	/**
	 * Kept for the commission machinery and as a map anchor, but no longer
	 * listed: The Threat War Effort board covers everything these entries said.
	 */
	@Override
	public boolean isHidden() {
		return true;
	}

	protected StarSystemAPI getSystem() {
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system.getId().equals(systemId)) return system;
		}
		return null;
	}

	protected String getStage() {
		String stage = ThreatIncData.stages().get(systemId);
		return stage != null ? stage : "unknown";
	}

	@Override
	public String getName() {
		StarSystemAPI system = getSystem();
		String name = system != null ? system.getBaseName() : systemId;
		return "Threat Infestation - " + name;
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		return tags;
	}

	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		StarSystemAPI system = getSystem();
		if (system != null) return system.getHyperspaceAnchor();
		return null;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);

		Color t = Misc.getTextColor();
		String stage = getStage();
		Color c = ThreatIncData.STAGE_SEEDED.equals(stage)
				? Misc.getHighlightColor() : Misc.getNegativeHighlightColor();
		info.addPara("Stage: %s", 3f, t, c, stage);
		if (ThreatIncData.STAGE_COLONY.equals(stage)) {
			java.util.List<MarketAPI> markets = ThreatIncData.getLiveColonyMarkets(systemId);
			int total = 0;
			for (MarketAPI market : markets) total += market.getSize();
			if (markets.size() == 1) {
				info.addPara("Colony size: %s", 0f, t, c, "" + total);
			} else if (markets.size() > 1) {
				info.addPara("Colonies: %s, total size %s", 0f, t, c,
						"" + markets.size(), "" + total);
			}
		}
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();

		StarSystemAPI system = getSystem();
		String name = system != null ? system.getNameWithLowercaseType() : systemId;
		String stage = getStage();

		if (ThreatIncData.STAGE_SEEDED.equals(stage)) {
			info.addPara("Threat fabrication signatures have been detected in the " + name + ". "
					+ "The system has been marked by the swarm - a %s will be dispatched to "
					+ "found a colony here.", opad, neg, "Seeding Swarm");
			info.addPara("No colony exists yet. Destroying the Seeding Swarm when it arrives "
					+ "will keep it that way.", opad, pos, "Destroying the Seeding Swarm");
		} else if (ThreatIncData.STAGE_COLONIZING.equals(stage)) {
			info.addPara("A Threat %s is in transit to the " + name + ", carrying the fabricator "
					+ "core of a new colony.", opad, neg, "Seeding Swarm");
			info.addPara("Destroy it before it makes planetfall and no colony will take root.",
					opad, pos, "Destroy it before it makes planetfall");
		} else if (ThreatIncData.STAGE_COLONY.equals(stage)) {
			java.util.List<MarketAPI> markets = ThreatIncData.getLiveColonyMarkets(systemId);
			int total = 0;
			for (MarketAPI market : markets) total += market.getSize();

			if (markets.size() == 1) {
				info.addPara("A Threat fabrication colony of size %s is entrenched in the " + name
						+ ". It mines, refines, and forges for the hive - and everything it "
						+ "produces feeds the swarm's fleets.", opad, neg, "" + total);
			} else {
				info.addPara("The swarm holds %s worlds in the " + name + " with a combined "
						+ "fabrication mass of %s. They mine, refine, and forge for the hive - "
						+ "and everything they produce feeds the swarm's fleets.", opad, neg,
						"" + markets.size(), "" + total);
			}

			int totalGarrison = 0;
			for (MarketAPI market : markets) {
				// per-highlight colors: healthy industries in the standard
				// highlight, disrupted ones in red with their downtime - the
				// same read the debug vitals give
				java.util.List<String> hl = new java.util.ArrayList<String>();
				java.util.List<Color> hlColors = new java.util.ArrayList<Color>();
				StringBuilder line = new StringBuilder(
						BULLET + market.getName() + " (size " + market.getSize() + "): ");
				boolean first = true;
				for (com.fs.starfarer.api.campaign.econ.Industry ind : market.getIndustries()) {
					if (!first) line.append(", ");
					first = false;
					line.append("%s");
					if (ind.isDisrupted()) {
						hl.add(ind.getCurrentName() + " (disrupted "
								+ (int) ind.getDisruptedDays() + "d)");
						hlColors.add(neg);
					} else {
						hl.add(ind.getCurrentName());
						hlColors.add(h);
					}
				}
				int garrison = ThreatColonyManager.countLiveGarrison(market.getId());
				totalGarrison += garrison;

				// "output" = the swarm's real fabrication output: ship hulls.
				// A starved colony reads critical; a healthy young colony with no
				// forge output yet is developing; a forging colony grades on how
				// well its hulls are supplied.
				boolean healthy = ThreatColonyManager.isEconomicallyHealthy(market);
				float health = ThreatColonyManager.computeHealth(market);
				float shipsAvail = ThreatColonyManager.shipsAvailable(market);
				String output;
				if (health < ThreatIncConfig.declineHealthThreshold()) {
					output = "collapsing";
				} else if (!healthy) {
					output = "critical";
				} else if (shipsAvail <= 0f) {
					output = "developing";
				} else if (ThreatColonyManager.shipSupplyMult(market) >= 0.75f) {
					output = "nominal";
				} else {
					output = "strained";
				}
				line.append(". Hive Status %s, Defense Swarms %s");
				hl.add(output + " (vitality " + (int) (health * 100f) + "%)");
				hlColors.add("critical".equals(output) || "strained".equals(output)
						|| "collapsing".equals(output) ? neg : h);
				hl.add("" + garrison);
				hlColors.add(h);
				// swarms mustered for a strike still fabricating in orbit: no
				// longer garrison, but very much still here until departure
				int strikeSwarms = IncursionManager.preparingStrikeFleetCount(market);
				if (strikeSwarms > 0) {
					line.append(", Strike Swarms %s");
					hl.add("" + strikeSwarms);
					hlColors.add(neg);
				}
				line.append(".");

				com.fs.starfarer.api.campaign.econ.Industry nexus =
						market.getIndustry(ThreatColonyManager.SWARM_NEXUS);
				if (nexus != null && nexus.isDisrupted()) {
					line.append(" %s");
					hl.add("Nexus silenced " + (int) nexus.getDisruptedDays()
							+ "d - fabricating no fleets.");
					hlColors.add(neg);
				}
				float decline = ThreatIncData.declineProgress(market.getId());
				if (decline > 0f) {
					line.append(" %s");
					hl.add("Decline: " + (int) (decline * 100f)
							+ "% of the way to losing a population stratum.");
					hlColors.add(neg);
				}
				info.addPara(line.toString(), 3f,
						hlColors.toArray(new Color[0]), hl.toArray(new String[0]));
			}

			info.addPara("The hive is one economy - cutting its supply lines and destroying "
					+ "its link colonies starves every world in the network.", opad);

			if (totalGarrison > 0) {
				info.addPara("Counterplay: the hive lives %s behind defenses anchored to its "
						+ "size - no bombardment can reduce its population, and saturating a "
						+ "world costs fuel equal to its full defense strength for mere days "
						+ "of disruption. The machines cannot be occupied, and cannot be "
						+ "bombed away - only starved and suppressed until the hive itself "
						+ "withers.", opad, pos, "deep underground");
				info.addPara("The efficient siege: %s craters the exposed war-strata (ground "
						+ "defenses, batteries, the nexus), halving their defensive effect - "
						+ "then %s cut deepest, disrupting a chosen organ for months at a "
						+ "heavy cost in casualties. A colony whose Fabrication Core is down, "
						+ "or whose supply lines are cut, stops growing and begins to %s - "
						+ "losing population faster the longer it stays suppressed, until it "
						+ "collapses entirely.", opad, pos, "tactical bombardment",
						"marine raids", "decline");
				info.addPara("A colony's fleets are fabricated by its %s: raid or bombard it "
						+ "into disruption and the colony grows no new Defense Swarms and "
						+ "stages no expeditions until it recovers - the swarms already in "
						+ "orbit fight on, but nothing replaces them.", opad, pos,
						"Swarm Nexus");
			} else {
				info.addPara("Every colony here lies %s - the garrisons have been destroyed. "
						+ "Until replacement swarms are fabricated, its worlds can be bombarded "
						+ "and raided unopposed.", opad, pos, "open to attack");
			}
		}

		float days = ThreatIncData.daysInStage(systemId);
		info.addPara("Time in current stage: %s days.", opad, h, "" + (int) days);

		// strike reach: fuel-bought, network-fed
		MarketAPI staging = ThreatColonyManager.pickStrikeStaging(systemId, false);
		if (staging != null) {
			float range = ThreatColonyManager.fuelRangeLY(staging);
			if (range > 0f) {
				info.addPara("Strike reach from this system: %s, bought with the fuel its "
						+ "staging colony draws from the hive network. Cut the swarm's fuel - "
						+ "or isolate this system - and its reach collapses.",
						opad, neg, (int) range + " light-years");
			}
		}

		// ---- player-commissioned purge expedition ----
		addCommissionSection(info, width, opad);

		// ---- debug mode: full per-colony economic vitals + purge tool ----
		if (ThreatIncConfig.debugMode() && ThreatIncData.STAGE_COLONY.equals(stage)) {
			info.addSectionHeading("DEBUG - hive vitals", com.fs.starfarer.api.ui.Alignment.MID, opad);
			for (MarketAPI market : ThreatIncData.getLiveColonyMarkets(systemId)) {
				float access = market.getAccessibilityMod().computeEffective(0f);
				int shipPct = (int) (ThreatColonyManager.shipSupplyMult(market) * 100);
				float fuelRange = ThreatColonyManager.fuelRangeLY(market);
				int healthPct = (int) (ThreatIncData.lastHealth(market.getId()) * 100);
				int declinePct = (int) (ThreatIncData.declineProgress(market.getId()) * 100);
				info.addPara(market.getName() + " (size " + market.getSize() + "): "
						+ "access %s, hull output %s, fuel reach %s, health %s, decline %s",
						opad, h, (int) (access * 100) + "%", shipPct + "%",
						(int) fuelRange + " ly", healthPct + "%", declinePct + "%");

				String disrupted = "";
				for (com.fs.starfarer.api.campaign.econ.Industry ind : market.getIndustries()) {
					if (!ind.isDisrupted()) continue;
					if (disrupted.length() > 0) disrupted += ", ";
					disrupted += ind.getCurrentName() + " (" + (int) ind.getDisruptedDays() + "d)";
				}
				if (disrupted.length() > 0) {
					info.addPara("  Disrupted: %s", 3f, neg, disrupted);
				}

				// available/demand for every gated input + ships, raw
				info.addPara("  " + ThreatColonyManager.econDebugSummary(market), 3f,
						Misc.getGrayColor(), "");
			}

			addGenericButton(info, width, "DEBUG: purge this system", BUTTON_PURGE);
		}
	}

	protected static final String BUTTON_PURGE = "threatinc_debug_purge";
	protected static final String BUTTON_COMMISSION = "threatinc_commission_purge";

	/**
	 * The player's mirror of tryPurgeBombardments: from a player military
	 * colony within response range, hire the exact expedition NPC navies run -
	 * sized to the job (target colonies + live garrisons) and priced by fleet
	 * points plus distance. Everything is recomputed live wherever it's shown
	 * or spent, so the quoted bill, the confirmation, and the launch always
	 * agree with the current state of the system.
	 */
	protected void addCommissionSection(TooltipMakerAPI info, float width, float opad) {
		addCommissionSectionFor(this, info, width, opad, systemId, BUTTON_COMMISSION);
	}

	/**
	 * Everything the commission needs, recomputed live from the system's
	 * current state. Null when the system holds no live colony; {@code base}
	 * null when no player military colony is in range.
	 */
	public static class CommissionQuote {
		public StarSystemAPI system;
		public MarketAPI base;
		public java.util.List<MarketAPI> targets;
		public java.util.List<Integer> fleetSizes;
		public boolean anyGarrisoned;
		public int difficulty;
		public int cost;
		/** Ground strength the flotilla is expected to land, and what the defenses demand. */
		public float raidStrEstimate;
		public float raidStrNeeded;
		public ThreatPurgeFGI existing;
	}

	public static CommissionQuote quote(String systemId) {
		if (systemId == null) return null;
		if (!ThreatIncData.STAGE_COLONY.equals(ThreatIncData.stages().get(systemId))) return null;
		StarSystemAPI system = ThreatWarBoard.getSystem(systemId);
		if (system == null) return null;
		java.util.List<MarketAPI> targets = IncursionManager.collectSiegeTargets(null, system);
		if (targets.isEmpty()) return null;

		CommissionQuote q = new CommissionQuote();
		q.system = system;
		q.targets = targets;
		q.base = IncursionManager.findPlayerExpeditionBase(system);
		q.existing = IncursionManager.findPlayerExpeditionAgainst(systemId);
		if (q.base == null) return q;
		q.anyGarrisoned = IncursionManager.anyTargetGarrisoned(targets);
		boolean heavyAssault = q.anyGarrisoned && anyTargetEntrenched(targets);
		q.difficulty = IncursionManager.computeSiegeDifficulty(targets, q.anyGarrisoned);
		q.fleetSizes = IncursionManager.siegeFleetSizes(q.difficulty, q.anyGarrisoned,
				heavyAssault, targets);
		q.raidStrEstimate = IncursionManager.siegeRaidStrEstimate(q.fleetSizes);
		q.raidStrNeeded = IncursionManager.siegeRaidStrNeeded(targets);
		q.cost = IncursionManager.commissionCost(q.base, system, q.fleetSizes);
		return q;
	}

	/**
	 * The commission section, usable from any intel: the owner supplies the
	 * button styling and receives the press under {@code buttonId}.
	 */
	public static void addCommissionSectionFor(BaseIntelPlugin owner, TooltipMakerAPI info,
			float width, float opad, String systemId, Object buttonId) {
		if (!ThreatIncConfig.enabled() || !ThreatIncConfig.commissionEnabled()) return;
		CommissionQuote q = quote(systemId);
		if (q == null) return;

		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color gray = Misc.getGrayColor();

		info.addSectionHeading("Commission a purge expedition",
				com.fs.starfarer.api.ui.Alignment.MID, opad);

		if (q.base == null) {
			info.addPara("None of your colonies with a military structure (Patrol HQ, "
					+ "Military Base, or High Command) can reach this system: expeditions range "
					+ "%s light-years per unit of fuel they carry - the smaller of the fuel "
					+ "available at the colony and what its fleets can lift - the same rule the "
					+ "swarm's strikes run on.", opad, h,
					"" + (int) ThreatIncConfig.strikeLYPerFuel());
			return;
		}

		float dist = Misc.getDistanceLY(q.base.getStarSystem().getLocation(),
				q.system.getLocation());

		info.addPara("Your colony %s (" + (int) Math.ceil(dist) + " light-years out) can "
				+ "muster a %s expedition sized to this system's defenses"
				+ (q.anyGarrisoned ? " - including escorts to fight through the live "
						+ "Defense Swarms" : "")
				+ ". It runs the full siege playbook autonomously and reports back when "
				+ "done. The fee covers fleets and distance, paid up front - no refunds.",
				opad, h, q.base.getName(), q.fleetSizes.size() + "-fleet");
		boolean enough = q.raidStrEstimate >= q.raidStrNeeded;
		info.addPara("Landing force: about %s ground strength against the %s a commando raid "
				+ "needs to disrupt an organ here"
				+ (enough ? "." : " - the most your colony can field, and short of it: expect "
						+ "the tactical bombardment to land and the raids to be repulsed."),
				3f, enough ? h : Misc.getNegativeHighlightColor(),
				Misc.getWithDGS(Math.round(q.raidStrEstimate)), Misc.getWithDGS(Math.round(q.raidStrNeeded)));

		com.fs.starfarer.api.ui.ButtonAPI button = owner.addGenericButton(info, width,
				"Commission purge expedition (" + Misc.getDGSCredits(q.cost) + ")", buttonId);

		int credits = (int) Global.getSector().getPlayerFleet().getCargo().getCredits().get();
		if (q.existing != null) {
			button.setEnabled(false);
			info.addPara("An expedition you commissioned is already operating against this "
					+ "system.", 3f, gray);
		} else if (credits < q.cost) {
			button.setEnabled(false);
			info.addPara("You cannot afford the fee - you have %s.", 3f, neg,
					Misc.getDGSCredits(credits));
		}
	}

	/** The confirmation dialog text for a commission against a system. */
	public static void addCommissionPrompt(TooltipMakerAPI prompt, String systemId) {
		CommissionQuote q = quote(systemId);
		if (q == null || q.base == null) {
			prompt.addPara("The situation has changed - the expedition can no longer be "
					+ "mustered.", 0f);
			return;
		}
		prompt.addPara("Commission a " + q.fleetSizes.size() + "-fleet purge expedition from "
				+ q.base.getName() + " against the " + q.targets.size() + " Threat "
				+ (q.targets.size() > 1 ? "colonies" : "colony") + " of the "
				+ q.system.getNameWithLowercaseType() + " for %s?", 0f,
				Misc.getHighlightColor(), Misc.getDGSCredits(q.cost));
		prompt.addPara("The fee is paid up front. Once mustered, the expedition is "
				+ "autonomous - it cannot be recalled and does not refund its fee, even "
				+ "if the colonies are destroyed by other means first.",
				Misc.getGrayColor(), 10f);
	}

	/**
	 * Spends the fee and launches; authoritative recomputation at spend time.
	 * @return true if an expedition was mustered
	 */
	public static boolean commissionExpedition(String systemId) {
		CommissionQuote q = quote(systemId);
		if (q == null || q.base == null || q.existing != null) return false;
		if (Global.getSector().getPlayerFleet().getCargo().getCredits().get() < q.cost) return false;

		Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(q.cost);
		IncursionManager.launchSiegeExpedition(q.base, Global.getSector().getPlayerFaction(),
				q.system, q.targets, q.fleetSizes, true, new java.util.Random());

		ThreatColonyManager.announceAlways("A purge expedition you commissioned is "
				+ "mustering at " + q.base.getName() + ", bound for the "
				+ q.system.getNameWithLowercaseType() + " (" + Misc.getDGSCredits(q.cost)
				+ " paid).", Misc.getHighlightColor());
		ThreatIncConfig.log("Player commissioned purge expedition vs " + q.system.getName()
				+ " from " + q.base.getName() + " - " + q.fleetSizes.size()
				+ " fleets, difficulty " + q.difficulty + ", cost " + q.cost);
		return true;
	}

	/**
	 * Whether any target colony is big enough that the expedition sails with
	 * the second escort fleet - same bar as the NPC heavy-assault rule (above
	 * the preemptive-purge size).
	 */
	protected static boolean anyTargetEntrenched(java.util.List<MarketAPI> targets) {
		for (MarketAPI target : targets) {
			if (target.getSize() > ThreatIncConfig.purgePreemptMaxSize()) return true;
		}
		return false;
	}

	@Override
	public boolean doesButtonHaveConfirmDialog(Object buttonId) {
		if (BUTTON_COMMISSION.equals(buttonId)) return true;
		return super.doesButtonHaveConfirmDialog(buttonId);
	}

	@Override
	public void createConfirmationPrompt(Object buttonId, TooltipMakerAPI prompt) {
		if (!BUTTON_COMMISSION.equals(buttonId)) {
			super.createConfirmationPrompt(buttonId, prompt);
			return;
		}
		addCommissionPrompt(prompt, systemId);
	}

	@Override
	public void buttonPressConfirmed(Object buttonId, com.fs.starfarer.api.ui.IntelUIAPI ui) {
		if (BUTTON_COMMISSION.equals(buttonId)) {
			commissionExpedition(ui);
			return;
		}
		if (BUTTON_PURGE.equals(buttonId)) {
			ThreatColonyManager.purgeSystemDebug(systemId);
			endAfterDelay(0.1f);
			ui.recreateIntelUI();
			return;
		}
		super.buttonPressConfirmed(buttonId, ui);
	}

	protected void commissionExpedition(com.fs.starfarer.api.ui.IntelUIAPI ui) {
		commissionExpedition(systemId);
		ui.updateUIForItem(this);
	}

	// NOTE: no getArrowData here on purpose. Vanilla uses map arrows solely to
	// mean "a fleet operation is underway toward this target" - a reach/range
	// display in the same visual language read as one system attacking the
	// whole sector. Reach is stated in the description text instead; only real
	// operations (seeding swarms in transit, strike FGIs) draw arrows.
}
