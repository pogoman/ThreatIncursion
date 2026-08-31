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
				float shipsAvail = ThreatColonyManager.shipsAvailable(market);
				String output;
				if (!healthy) {
					output = "critical";
				} else if (shipsAvail <= 0f) {
					output = "developing";
				} else if (ThreatColonyManager.shipSupplyMult(market) >= 0.75f) {
					output = "nominal";
				} else {
					output = "strained";
				}
				line.append(". Hive Status %s, Defense Swarms %s");
				hl.add(output);
				hlColors.add("critical".equals(output) || "strained".equals(output) ? neg : h);
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
				info.addPara(line.toString(), 3f,
						hlColors.toArray(new Color[0]), hl.toArray(new String[0]));
			}

			info.addPara("The hive is one economy - cutting its supply lines and destroying "
					+ "its link colonies starves every world in the network.", opad);

			if (totalGarrison > 0) {
				info.addPara("Counterplay: defeat the Defense Swarms orbiting a colony, then "
						+ "%s burns the fabrication strata away - each pass shrinks the colony, "
						+ "and small colonies are destroyed outright. Raiding its ground defenses "
						+ "first makes the bombardment cheaper. The machines cannot be occupied - "
						+ "only erased.", opad, pos, "saturation bombardment");
				if (ThreatIncConfig.fragmentShieldEnabled()) {
					info.addPara("But no bombardment of yours will land while the colony's %s "
							+ "runs: its fragment screen unmakes ordnance faster than you can "
							+ "deliver it. Raid the Fabrication Core and steal the fabricator "
							+ "first - the hive defends its heart above all else - and the sky "
							+ "opens permanently.", opad, pos, "Fragment Fabricator");
				}
				info.addPara("A colony's fleets are fabricated by its %s: raid or bombard it "
						+ "into disruption and the colony grows no new Defense Swarms and "
						+ "stages no expeditions until it recovers - the swarms already in "
						+ "orbit fight on, but nothing replaces them.", opad, pos,
						"Swarm Nexus");
			} else {
				info.addPara("Every colony here lies %s - the garrisons have been destroyed. "
						+ "Until replacement swarms are fabricated, saturation bombardment can "
						+ "burn them down unopposed.", opad, pos, "open to bombardment");
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

		// ---- debug mode: full per-colony economic vitals + purge tool ----
		if (ThreatIncConfig.debugMode() && ThreatIncData.STAGE_COLONY.equals(stage)) {
			info.addSectionHeading("DEBUG - hive vitals", com.fs.starfarer.api.ui.Alignment.MID, opad);
			for (MarketAPI market : ThreatIncData.getLiveColonyMarkets(systemId)) {
				float access = market.getAccessibilityMod().computeEffective(0f);
				int shipPct = (int) (ThreatColonyManager.shipSupplyMult(market) * 100);
				float fuelRange = ThreatColonyManager.fuelRangeLY(market);
				info.addPara(market.getName() + " (size " + market.getSize() + "): "
						+ "access %s, hull output %s, fuel reach %s",
						opad, h, (int) (access * 100) + "%", shipPct + "%",
						(int) fuelRange + " ly");

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
		}
	}

	// NOTE: no getArrowData here on purpose. Vanilla uses map arrows solely to
	// mean "a fleet operation is underway toward this target" - a reach/range
	// display in the same visual language read as one system attacking the
	// whole sector. Reach is stated in the description text instead; only real
	// operations (seeding swarms in transit, strike FGIs) draw arrows.
}
