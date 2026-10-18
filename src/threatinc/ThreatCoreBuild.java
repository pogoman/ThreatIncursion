package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.util.Misc;

/**
 * The mobilised core builds up over the war (2026-10-18, the user: forward bases
 * are staging depots and nothing else; "once mobilised human factions start
 * growing and building up all their existing colonies ... upgrade and improve
 * and add military structures but all these should take time and cost
 * supplies. This way humans have a ceiling"). Replaces the yards-at-home trial
 * of 2026-10-08 (homeYards: a Heavy Industry at a core world when hulls ran
 * short) with the general case, and takes over what the links used to build
 * (ThreatFrontlines.buildStep before this date: producers and conversions).
 *
 * Daily (ThreatFrontlines.poll), one start per faction per
 * ThreatFactionStock.SHORT_DAYS, at its core colonies (no forward base, not the
 * player's, size 3 up) with no project running:
 * <ol>
 * <li>the shortage's answer first - the producer of the stock the faction is
 * shortest of (ThreatFactionStock.shortest: hulls, fuel or supplies) at the
 * largest colony with a free slot whose inputs can be imported
 * (canSupplyProducer) - so lost yards and fuel plants are rebuilt at home;</li>
 * <li>else the military step the largest colony lacks, in order: a Patrol HQ
 * (size 3, a free slot), Ground Defenses, Patrol HQ to Military Base (size 4),
 * the station a tier up (Battlestation at 4, Star Fortress at 6), Ground
 * Defenses to Heavy Batteries (size 5), Military Base to High Command (size 6).</li>
 * </ol>
 * Every start pays vanilla's cost in supplies (ThreatBuildCost, payBuild: the
 * colony's own depot above its floor, then the faction's markets in reach) and
 * takes vanilla's build time, or holds the supplies it is short
 * (ThreatFactionStock.hold). Growth is vanilla's: nothing here changes a size.
 * The ceiling is vanilla's too - a fixed set of colonies, finite slots, the depot
 * the only purse. Knob threatinc_coreBuild.
 */
public class ThreatCoreBuild {

	protected static final String KEY_LAST = "threatinc_coreBuildLast";

	public static boolean enabled() {
		return ThreatIncConfig.coreBuild() && ThreatBuildCost.enabled();
	}

	protected static Map<String, Object> lastStarts() {
		return ThreatIncData.map(KEY_LAST);
	}

	/** Whether the faction may start another project now: one per SHORT_DAYS. */
	protected static boolean mayStart(String fid) {
		Object last = lastStarts().get(fid);
		if (!(last instanceof Long)) return true;
		return Global.getSector().getClock().getElapsedDaysSince((Long) last) >= ThreatFactionStock.SHORT_DAYS;
	}

	protected static void started(String fid) {
		lastStarts().put(fid, Global.getSector().getClock().getTimestamp());
	}

	/** A core colony this pass builds at: the faction's, no forward base, not the player's, size 3 up, nothing building. */
	protected static List<MarketAPI> sites(String fid) {
		List<MarketAPI> sites = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatReserves.marketsOf(fid)) {
			if (m == null || !m.isInEconomy() || ThreatFrontlines.isOutpost(m) || m.isPlayerOwned()) continue;
			if (m.getSize() < 3 || ThreatFrontlines.projectRunning(m)) continue;
			sites.add(m);
		}
		Collections.sort(sites, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				if (a.getSize() != b.getSize()) return b.getSize() - a.getSize();
				return Float.compare(ThreatFrontlines.buildFunds(b), ThreatFrontlines.buildFunds(a));
			}
		});
		return sites;
	}

	/** The daily pass: for every mobilised NPC faction, one start at most. */
	public static void poll() {
		if (!enabled()) return;
		for (String fid : ThreatWarState.warFactionIds()) {
			if (ThreatWarState.excluded(fid)) continue;
			if (!mayStart(fid)) continue;
			List<MarketAPI> sites = sites(fid);
			if (sites.isEmpty()) continue;
			ThreatFactionStock.watch(fid);
			if (answerShortage(fid, sites)) continue;
			for (MarketAPI m : sites) {
				if (militaryStep(m)) {
					started(fid);
					break;
				}
			}
		}
	}

	/**
	 * The shortage's answer at home: the producer of the faction's shortest stock at
	 * the largest colony with a free slot that can import its inputs; paid, or its
	 * price held there (ThreatFactionStock.hold) so upkeep and hunts leave it be.
	 */
	protected static boolean answerShortage(String fid, List<MarketAPI> sites) {
		String want = ThreatFactionStock.shortest(fid);
		if (want == null || !ThreatFactionStock.mayAnswer(fid, want)) return false;
		String producer = ThreatFactionStock.producerId(want);
		for (MarketAPI m : sites) {
			if (!ThreatFrontlines.wantsProducer(m, producer)) continue;
			if (Misc.getNumIndustries(m) >= Misc.getMaxIndustries(m)) continue;
			if (!ThreatFrontlines.canSupplyProducer(m, m.getSize(), producer)) continue;
			ThreatFactionStock.release(m);
			if (ThreatFrontlines.startNew(m, producer)) {
				ThreatFactionStock.answered(fid, want);
				started(fid);
				ThreatIncConfig.log("Core build: " + m.getName() + " answers " + want + " with a " + producer + " - " + fid
						+ ": " + ThreatFactionStock.describe(fid));
				return true;
			}
			float cost = ThreatBuildCost.supplies(producer), funds = ThreatFrontlines.buildFunds(m);
			if (cost > funds) {
				ThreatFactionStock.hold(m, producer, cost - funds);
				ThreatIncConfig.logQuiet("cb_hold_" + m.getId(), "Core build: " + m.getName() + " holds "
						+ (int) (cost - funds) + " supplies for a " + producer + " (" + fid + " " + want + " short)");
			}
			return false;
		}
		return false;
	}

	/** The one military step the colony lacks, by size; true when a build or upgrade started. */
	protected static boolean militaryStep(MarketAPI m) {
		int s = m.getSize();
		boolean slot = Misc.getNumIndustries(m) < Misc.getMaxIndustries(m);
		Industry hq = m.getIndustry(Industries.PATROLHQ);
		Industry mil = m.getIndustry(Industries.MILITARYBASE);
		Industry high = m.getIndustry(Industries.HIGHCOMMAND);
		if (hq == null && mil == null && high == null && slot) {
			return start(m, Industries.PATROLHQ);
		}
		Industry ground = m.getIndustry(Industries.GROUNDDEFENSES);
		Industry heavy = m.getIndustry(Industries.HEAVYBATTERIES);
		if (ground == null && heavy == null) {
			return start(m, Industries.GROUNDDEFENSES);
		}
		if (s >= 4 && hq != null) return upgrade(m, hq);
		Industry station = ThreatFrontlines.stationOf(m);
		if (station == null) {
			return start(m, ThreatFrontlines.orbitalStationFor(m.getFaction()));
		}
		int tier = ThreatFrontlines.stationTier(station);
		if (s >= 4 && tier == 1) return upgrade(m, station);
		if (s >= 5 && ground != null) return upgrade(m, ground);
		if (s >= 6 && tier == 2) return upgrade(m, station);
		if (s >= 6 && mil != null) return upgrade(m, mil);
		return false;
	}

	protected static boolean start(MarketAPI m, String id) {
		if (ThreatFrontlines.startNew(m, id)) {
			ThreatIncConfig.log("Core build: " + m.getName() + " (" + m.getFactionId() + ") builds " + id);
			return true;
		}
		return false;
	}

	protected static boolean upgrade(MarketAPI m, Industry ind) {
		if (ind == null || ind.getSpec().getUpgrade() == null) return false;
		if (ind.isBuilding() || ind.isUpgrading()) return false;
		if (!ThreatFrontlines.payBuild(m, ind.getSpec().getUpgrade())) return false;
		ind.startUpgrading();
		ThreatIncConfig.log("Core build: " + m.getName() + " (" + m.getFactionId() + ") upgrades " + ind.getId() + " to "
				+ ind.getSpec().getUpgrade());
		return true;
	}
}
