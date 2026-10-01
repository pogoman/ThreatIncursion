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
 * One marker per infested system: appears under the "Abyssal War" intel
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
	 * Kept as a map anchor, but no longer listed: The Abyssal War board
	 * covers everything these entries said, and sieges are ordered from its
	 * faction view (the purge commission that lived here was removed
	 * 2026-09-05 - it was a second UI over the same launch as the Siege order).
	 */
	/** Threat news titles in the Threat's colour; ended entries keep vanilla's grey. */
	@Override
	public java.awt.Color getTitleColor(com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode mode) {
		java.awt.Color c = super.getTitleColor(mode);
		if (isEnded() || Misc.getGrayColor().equals(c)) return c;
		return ThreatNotice.threatColor();
	}

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
		Color pos = ThreatNotice.goodColor();

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

			// the swarms as the player last saw them (ThreatIntel, the fog of
			// war, 2026-10-01): one report for the system, never the live garrisons
			ThreatIntel.Report seen = ThreatIntel.report(Factions.PLAYER, systemId);
			float seenFP = 0f;
			boolean everySeen = seen != null;
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
				ThreatIntel.Report over = ThreatWarBoard.seenOver(seen, market);
				float worldFP = over != null ? over.worldFP(market.getId()) : 0f;
				seenFP += worldFP;
				if (over == null) everySeen = false;

				line.append(". Hive Status %s, Swarm FP %s");
				if (ThreatColonyUpkeep.enabled()) {
					// size upkeep (2026-09-30): graded by the share of its
					// upkeep paid - starving below break-even, else growing or
					// holding - and the share itself (none below size 3)
					int state = ThreatWarBoard.fedState(market);
					boolean owes = ThreatColonyUpkeep.perMonth(market.getSize()) > 0f;
					hl.add(ThreatWarBoard.fedStateName(state) + (owes ? " (fed "
							+ ThreatWarBoard.pct(ThreatColonyUpkeep.fedShare(market)) + ")" : ""));
					hlColors.add(state == ThreatWarBoard.STARVING ? neg : h);
				} else {
					// "output" = the swarm's real fabrication output: ship hulls.
					// A starved colony reads critical; a healthy young colony with no
					// forge output yet is developing; a forging colony grades on how
					// well its hulls are supplied.
					boolean healthy = ThreatColonyManager.isEconomicallyHealthy(market);
					float health = ThreatColonyManager.computeHealth(market);
					float shipsAvail = ThreatColonyManager.shipsAvailable(market);
					String output;
					if (health < ThreatColonyManager.CRITICAL_HEALTH) {
						output = "failing";
					} else if (!healthy) {
						output = "critical";
					} else if (shipsAvail <= 0f) {
						output = "developing";
					} else if (ThreatColonyManager.shipSupplyMult(market) >= 0.75f) {
						output = "nominal";
					} else {
						output = "strained";
					}
					hl.add(output + " (vitality " + (int) (health * 100f) + "%)");
					hlColors.add("critical".equals(output) || "strained".equals(output)
							|| "failing".equals(output) ? neg : h);
				}
				// "3,400 (41 d)", "Unknown" never seen
				hl.add(ThreatIntel.figure(over, worldFP));
				hlColors.add(over != null ? h : Misc.getGrayColor());
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
				ThreatGroundFronts.GroundFront front =
						ThreatGroundFronts.getFront(market.getId());
				if (front != null) {
					line.append(" %s");
					hl.add("Ground war: " + front.strataHeld + " of " + market.getSize()
							+ " strata taken.");
					hlColors.add(neg);
				}
				info.addPara(line.toString(), 3f,
						hlColors.toArray(new Color[0]), hl.toArray(new String[0]));
			}

			info.addPara("The hive is one economy - cutting its supply lines and destroying "
					+ "its link colonies starves every world in the network.", opad);

			if (!everySeen || seenFP > 0f) {
				info.addPara("Counterplay: the hive lives %s behind defenses anchored to its "
						+ "size - no bombardment can reduce its population, and saturating a "
						+ "world costs fuel equal to its full defense strength for mere days "
						+ "of disruption. Bombardment and starvation only weaken a hive; it "
						+ "dies to a ground victory alone.", opad, pos, "deep underground");
				info.addPara("The efficient siege: %s craters the exposed war-strata (ground "
						+ "defenses, batteries, the nexus), halving their defensive effect - "
						+ "then %s a ground front (marines and heavy armaments, from the "
						+ "Ground operations menu) to take the hive stratum by stratum and "
						+ "destroy the %s at its center. Starved, suppressed colonies "
						+ "counter-attack feebly and fall cheaply.", opad, pos,
						"tactical bombardment", "land", "Fabrication Core");
				info.addPara("A colony's fleets are fabricated by its %s: raid or bombard it "
						+ "into disruption and the colony grows no new Defense Swarms and "
						+ "stages no expeditions until it recovers - the swarms already in "
						+ "orbit fight on, but nothing replaces them.", opad, pos,
						"Swarm Nexus");
			} else {
				// what the player last saw, and when: the garrisons may have regrown since
				info.addPara("Every colony here lay %s as last seen %s: no Defense Swarm held "
						+ "its orbit.", opad, new Color[] {pos, h}, "open to attack",
						ThreatIntel.when(seen));
			}
		}

		float days = ThreatIncData.daysInStage(systemId);
		info.addPara("Time in current stage: %s days.", opad, h, "" + (int) days);

		// strike reach: under billed reach (2026-09-30) the world the system
		// would strike first, or that the hive can keep no swarm away from it;
		// with it off, the fuel radius
		MarketAPI staging = ThreatColonyManager.pickStrikeStaging(systemId, false);
		if (ThreatReach.enabled()) {
			MarketAPI source = ThreatIncData.STAGE_COLONY.equals(stage)
					? ThreatWarBoard.swarmSource(ThreatIncData.getLiveColonyMarkets(systemId), staging) : null;
			String faced = system != null ? ThreatReach.facedFaction(system) : null;
			if (source != null && ThreatWarBoard.grounded(source)) {
				info.addPara("The hive can keep %s away from this system.", opad, pos, "no swarm");
			} else if (staging != null && faced != null) {
				info.addPara("First target from this system: %s, %s away.", opad,
						new Color[] {ThreatWarBoard.factionColor(faced), neg}, ThreatWarState.displayName(faced),
						(int) Math.ceil(ThreatReach.facedLY(system)) + " light-years");
			}
		} else if (staging != null) {
			float range = ThreatColonyManager.fuelRangeLY(staging);
			if (range > 0f) {
				info.addPara("Strike reach from this system: %s, bought with the fuel its "
						+ "staging colony draws from the hive network. Cut the swarm's fuel - "
						+ "or isolate this system - and its reach collapses.",
						opad, neg, (int) range + " light-years");
			}
		}

		// ---- debug mode: full per-colony economic vitals + purge tool ----
		if (ThreatIncConfig.debugMode() && ThreatIncData.STAGE_COLONY.equals(stage)) {
			info.addSectionHeading("DEBUG - hive vitals", com.fs.starfarer.api.ui.Alignment.MID, opad);
			boolean billed = ThreatReach.enabled();
			boolean sized = ThreatColonyUpkeep.enabled();
			String faced = billed && system != null ? ThreatReach.facedFaction(system) : null;
			for (MarketAPI market : ThreatIncData.getLiveColonyMarkets(systemId)) {
				float access = market.getAccessibilityMod().computeEffective(0f);
				int shipPct = (int) (ThreatColonyManager.shipSupplyMult(market) * 100);
				// billed reach: the world struck first, or no swarm the hive can
				// keep away; else the fuel radius
				String reach;
				if (!billed) reach = (int) ThreatColonyManager.fuelRangeLY(market) + " ly";
				else if (ThreatWarBoard.grounded(market)) reach = "no swarm away";
				else if (faced == null) reach = "none known";
				else reach = ThreatWarState.displayName(faced) + " "
						+ (int) Math.ceil(ThreatReach.facedLY(system)) + " ly";
				// size upkeep: the share of its upkeep paid; else vitality
				String fed = sized ? ThreatWarBoard.pct(ThreatColonyUpkeep.fedShare(market))
						: (int) (ThreatIncData.lastHealth(market.getId()) * 100) + "%";
				int strataHeld = ThreatGroundFronts.strataHeld(market.getId());
				info.addPara(market.getName() + " (size " + market.getSize() + "): "
						+ "access %s, hull output %s, " + (billed ? "first strike" : "fuel reach") + " %s, "
						+ (sized ? "fed" : "health") + " %s, strata taken %s",
						opad, h, (int) (access * 100) + "%", shipPct + "%",
						reach, fed, "" + strataHeld);

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
	@Override
	public void buttonPressConfirmed(Object buttonId, com.fs.starfarer.api.ui.IntelUIAPI ui) {
		if (BUTTON_PURGE.equals(buttonId)) {
			ThreatColonyManager.purgeSystemDebug(systemId);
			endAfterDelay(0.1f);
			ui.recreateIntelUI();
			return;
		}
		super.buttonPressConfirmed(buttonId, ui);
	}

	// NOTE: no getArrowData here on purpose. Vanilla uses map arrows solely to
	// mean "a fleet operation is underway toward this target" - a reach/range
	// display in the same visual language read as one system attacking the
	// whole sector. Reach is stated in the description text instead; only real
	// operations (seeding swarms in transit, strike FGIs) draw arrows.
}
