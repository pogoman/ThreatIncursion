package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
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
 * Daily (ThreatFrontlines.poll), at its core colonies (no forward base, not the
 * player's, size 3 up) with no project running:
 * <ol>
 * <li>YARDS FIRST (the user, 2026-10-19: "heavy industry should be the primary
 * objective ... focus 100% on hull production first where possible", after
 * hw209/hw210 read every postponed human siege hull-bound with months of
 * materials banked): a Heavy Industry at the largest colony without one that
 * has a free slot and can import its inputs, then Heavy Industry to Orbital
 * Works - every pass, not one a month, started or its price held at the colony
 * (yardStep). While a yard is held the faction builds nothing else, and
 * ThreatFactionStock.yardWant keeps that price from founding kits and siege
 * provisions too. Knob threatinc_yardsFirst.</li>
 * <li>then, one start per faction per ThreatFactionStock.SHORT_DAYS: the
 * shortage's answer - the producer of the stock the faction is
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
	/** HOME FIRST: fid|since (first hold), fid|done (the core complete, or the hold lapsed) - timestamps. */
	protected static final String KEY_HOME = "threatinc_coreBuildHome";

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
			List<MarketAPI> sites = sites(fid);
			if (sites.isEmpty()) continue;
			ThreatFactionStock.watch(fid);
			deliverLevy(fid);
			// marines follow the war footing (the user, 2026-10-19 18:50): hulls out-covering marines, the pass levies
			// the shortfall from supplies; a levy raised is the faction's whole pass
			if (marineLevy(fid, sites)) continue;
			// the marine step (the user, 2026-10-19): a faction whose landings wait on marines raises its military
			// ladder before another yard; a step started or held is the faction's whole pass
			if (marineStep(fid, sites)) continue;
			// yards first, every pass: a yard started or held is the faction's whole pass
			if (yardStep(fid, sites)) continue;
			// HOME FIRST (the user, 2026-10-20): while the hold stands the core build is the faction's whole
			// war - every site a pass, no 30-day throttle, an unpaid step holds its price (holdMilitary)
			if (homeFirstHolds(fid)) {
				answerShortage(fid, sites);
				for (MarketAPI m : sites) {
					String[] next = militaryNext(m);
					if (next == null) continue;
					if (militaryStep(m)) started(fid);
					else holdMilitary(m, fid, next[1]);
				}
				continue;
			}
			if (!mayStart(fid)) continue;
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
	 * YARDS FIRST (the user, 2026-10-19): the faction's hull production before anything else. A Heavy
	 * Industry at the largest core colony without one (Orbital Works counts) that has a free industry
	 * slot and can import metals and transplutonics (ThreatFrontlines.canSupplyProducer), else a Heavy
	 * Industry standing idle upgraded to Orbital Works - paid now (payBuild: the colony's depot above
	 * its floor, then the faction's markets in reach) or its price held at the colony
	 * (ThreatFactionStock.hold, so upkeep and hunts leave it and yardWant keeps it from kits and
	 * sieges). True when a yard started or is held - the faction starts nothing else this pass; false
	 * when every colony that can site a yard has one and none can be upgraded.
	 */
	protected static boolean yardStep(String fid, List<MarketAPI> sites) {
		if (!ThreatIncConfig.yardsFirst() || !ThreatIncConfig.frontlineHeavyIndustry()) return false;
		// the balance (the user, 2026-10-19 18:50): no further yard while the hulls already out-cover the marines
		if (ThreatIncConfig.marineLevy() && ThreatFactionStock.hullsOutcoverMarines(fid)) {
			ThreatIncConfig.logQuiet("yardpause:" + fid, "Core build: " + fid + " yards paused - hulls cover "
					+ String.format("%.2f", ThreatFactionStock.hullCoverage(fid)) + " of their siege, marines "
					+ String.format("%.2f", ThreatFactionStock.marineCoverage(fid)) + " of their landing ("
					+ (int) ThreatFactionStock.marineShort(fid) + " short)");
			return false;
		}
		String yard = Industries.HEAVYINDUSTRY;
		for (MarketAPI m : sites) {
			if (!ThreatFrontlines.wantsProducer(m, yard)) continue;
			if (Misc.getNumIndustries(m) >= Misc.getMaxIndustries(m)) continue;
			if (!ThreatFrontlines.canSupplyProducer(m, m.getSize(), yard)) continue;
			ThreatFactionStock.release(m);
			if (ThreatFrontlines.startNew(m, yard)) {
				started(fid);
				ThreatIncConfig.log("Core build: " + m.getName() + " (" + fid + ") builds " + yard + " (yards first) - "
						+ ThreatFactionStock.describe(fid));
				return true;
			}
			return holdYard(m, fid, yard);
		}
		for (MarketAPI m : sites) {
			Industry hi = m.getIndustry(yard);
			if (hi == null || hi.isBuilding() || hi.isUpgrading() || hi.getSpec().getUpgrade() == null) continue;
			String works = hi.getSpec().getUpgrade();
			ThreatFactionStock.release(m);
			if (ThreatFrontlines.payBuild(m, works)) {
				hi.startUpgrading();
				started(fid);
				ThreatIncConfig.log("Core build: " + m.getName() + " (" + fid + ") upgrades " + yard + " to " + works
						+ " (yards first) - " + ThreatFactionStock.describe(fid));
				return true;
			}
			return holdYard(m, fid, works);
		}
		return false;
	}

	/** Holds the yard's price at the colony (ThreatFactionStock.hold) and logs it once; true (the faction waits on its yards). */
	protected static boolean holdYard(MarketAPI m, String fid, String id) {
		float cost = ThreatBuildCost.supplies(id), funds = ThreatFrontlines.buildFunds(m);
		if (cost > funds) {
			ThreatFactionStock.hold(m, id, cost - funds);
			ThreatIncConfig.logQuiet("cb_hold_" + m.getId(), "Core build: " + m.getName() + " holds "
					+ (int) (cost - funds) + " supplies for a " + id + " (" + fid + " yards first)");
		}
		return true;
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

	/**
	 * THE MARINE STEP (the user, 2026-10-19: "marines come from military base / high command ... build those
	 * buildings when short instead of more orbital works"). While the faction's invade pass notes landings it
	 * cannot man (ThreatFactionStock.marineShort, from ThreatPlays.payingBase), the pass raises the ladder that
	 * produces marines at the largest core colony with a step left: Patrol HQ upgraded to Military Base,
	 * Military Base to High Command (one a faction, as vanilla has it), a Patrol HQ started where none of the
	 * three stands and a slot is free. Paid now (ThreatFrontlines.payBuild) or its price held at the colony
	 * (ThreatFactionStock.hold, as a yard's is), ahead of the yard step. True when a step started or is held.
	 * hw219a / hw220c: the Hegemony sat on 35k / 15k FP free while 1,095 / 522 of its refused systems were
	 * "marines" - a landing wants 1,100-2,700, its markets raise 40-77 a month each.
	 */
	protected static boolean marineStep(String fid, List<MarketAPI> sites) {
		if (!ThreatIncConfig.marineStep()) return false;
		float short_ = ThreatFactionStock.marineShort(fid);
		if (short_ <= 0f) return false;
		boolean highCommand = hasHighCommand(fid);
		for (MarketAPI m : sites) {
			Industry hq = m.getIndustry(Industries.PATROLHQ);
			Industry mil = m.getIndustry(Industries.MILITARYBASE);
			Industry high = m.getIndustry(Industries.HIGHCOMMAND);
			if (high != null) continue;
			Industry step = mil != null && !highCommand ? mil : hq;
			if (step != null) {
				if (step.isBuilding() || step.isUpgrading() || step.getSpec().getUpgrade() == null) continue;
				String to = step.getSpec().getUpgrade();
				ThreatFactionStock.release(m);
				if (ThreatFrontlines.payBuild(m, to)) {
					step.startUpgrading();
					started(fid);
					ThreatIncConfig.log("Core build: " + m.getName() + " (" + fid + ") upgrades " + step.getId() + " to " + to
							+ " (marine step: " + (int) short_ + " marines short) - " + ThreatFactionStock.describe(fid));
					return true;
				}
				return holdStep(m, fid, to, short_);
			}
			if (mil == null && Misc.getNumIndustries(m) < Misc.getMaxIndustries(m)) {
				ThreatFactionStock.release(m);
				if (ThreatFrontlines.startNew(m, Industries.PATROLHQ)) {
					started(fid);
					ThreatIncConfig.log("Core build: " + m.getName() + " (" + fid + ") builds " + Industries.PATROLHQ
							+ " (marine step: " + (int) short_ + " marines short) - " + ThreatFactionStock.describe(fid));
					return true;
				}
				return holdStep(m, fid, Industries.PATROLHQ, short_);
			}
		}
		return false;
	}

	protected static final String KEY_LEVY = "threatinc_marineLevy";

	protected static Map<String, Object> levies() {
		return ThreatIncData.map(KEY_LEVY);
	}

	/**
	 * MARINES FOLLOW THE WAR FOOTING (the user, 2026-10-19 18:50: "do option 1 and 3" - "equally short on hulls as
	 * marines, both in balance"). While the faction's landings wait on marines and its free hulls cover their
	 * siege better than its marines cover the landing (ThreatFactionStock.hullsOutcoverMarines), the pass levies
	 * the shortfall: the core colonies pay marineLevySuppliesEach supplies a marine from their spendable supplies
	 * (the largest first), the marines train marineLevyDays and then stand at the colonies that paid
	 * (deliverLevy), for the convoys to carry on. One levy a faction at a time. True when a levy was raised -
	 * the faction's whole pass. hw231a: the Hegemony's ladder took its marine income 360 -> 836 a month while a
	 * landing's want rose 2,666 -> 3,309, and 16.8k of 19.8k FP of hulls sat free.
	 */
	protected static boolean marineLevy(String fid, List<MarketAPI> sites) {
		if (!ThreatIncConfig.marineLevy()) return false;
		if (levies().containsKey(fid + "|readyAt")) return false;
		if (!ThreatFactionStock.hullsOutcoverMarines(fid)) return false;
		// the want net of the marines the faction already holds (hw235: read as the base's shortfall the levy
		// raised 20-90k marines a faction that stood at home, and every core world became a fortress)
		float want = ThreatFactionStock.marineLevyWant(fid);
		if (want < 1f) {
			ThreatIncConfig.logQuiet("levyhome:" + fid, "Marine levy: " + fid + " holds " + (int) ThreatFactionStock.factionMarines(fid)
					+ " marines against its landing's want of " + (int) ThreatFactionStock.marineNeed(fid)
					+ " - the base is short, not the faction; the convoys' job, no levy");
			return false;
		}
		float each = Math.max(0.01f, ThreatIncConfig.marineLevySuppliesEach());
		float raised = 0f, paid = 0f;
		StringBuilder where = new StringBuilder();
		for (MarketAPI m : sites) {
			float left = want - raised;
			if (left < 1f) break;
			float got = ThreatReserves.drawSpendable(m, Commodities.SUPPLIES, left * each);
			if (got < each) continue;
			float marines = (float) Math.floor(got / each);
			levies().put(fid + "|m:" + m.getId(), marines);
			raised += marines;
			paid += got;
			if (where.length() > 0) where.append(", ");
			where.append(m.getName()).append(' ').append((int) marines);
		}
		if (raised < 1f) {
			ThreatIncConfig.logQuiet("levywait:" + fid, "Marine levy: " + fid + " cannot pay a levy of " + (int) want
					+ " marines (" + (int) (want * each) + " supplies) from its core colonies' spendable supplies");
			return false;
		}
		levies().put(fid + "|readyAt", Global.getSector().getClock().getTimestamp());
		levies().put(fid + "|marines", raised);
		started(fid);
		ThreatIncConfig.log("Marine levy: " + fid + " raises " + (int) raised + " of " + (int) want + " marines for " + (int) paid
				+ " supplies at " + where + " (holds " + (int) ThreatFactionStock.factionMarines(fid) + " of the landing's "
				+ (int) ThreatFactionStock.marineNeed(fid) + "; ready in " + (int) ThreatIncConfig.marineLevyDays() + " d; hulls cover "
				+ String.format("%.2f", ThreatFactionStock.hullCoverage(fid)) + ", marines "
				+ String.format("%.2f", ThreatFactionStock.marineCoverage(fid)) + ") - " + ThreatFactionStock.describe(fid));
		return true;
	}

	/** A levy that has trained marineLevyDays stands at the colonies that paid it (ThreatReserves.deposit). */
	protected static void deliverLevy(String fid) {
		Object at = levies().get(fid + "|readyAt");
		if (!(at instanceof Long)) return;
		if (Global.getSector().getClock().getElapsedDaysSince((Long) at) < ThreatIncConfig.marineLevyDays()) return;
		List<String> keys = new ArrayList<String>(levies().keySet());
		float landed = 0f;
		StringBuilder where = new StringBuilder();
		for (String k : keys) {
			if (!k.startsWith(fid + "|m:")) continue;
			Object n = levies().remove(k);
			String marketId = k.substring((fid + "|m:").length());
			MarketAPI m = Global.getSector().getEconomy().getMarket(marketId);
			if (!(n instanceof Float) || m == null || !fid.equals(m.getFactionId())) continue;
			float marines = (Float) n;
			ThreatReserves.deposit(marketId, Commodities.MARINES, marines);
			landed += marines;
			if (where.length() > 0) where.append(", ");
			where.append(m.getName()).append(' ').append((int) marines);
		}
		levies().remove(fid + "|readyAt");
		levies().remove(fid + "|marines");
		if (landed > 0f) ThreatIncConfig.log("Marine levy landed: " + fid + " " + (int) landed + " marines stand at " + where);
	}

	/** Whether the faction has (or is raising) its one High Command. */
	protected static boolean hasHighCommand(String fid) {
		for (MarketAPI m : ThreatReserves.marketsOf(fid)) {
			if (m == null || !m.isInEconomy()) continue;
			if (m.getIndustry(Industries.HIGHCOMMAND) != null) return true;
			Industry mil = m.getIndustry(Industries.MILITARYBASE);
			if (mil != null && mil.isUpgrading()) return true;
		}
		return false;
	}

	/** Holds the marine step's price at the colony (ThreatFactionStock.hold) and logs it once; true (the faction waits on it). */
	protected static boolean holdStep(MarketAPI m, String fid, String id, float short_) {
		float cost = ThreatBuildCost.supplies(id), funds = ThreatFrontlines.buildFunds(m);
		if (cost > funds) {
			ThreatFactionStock.hold(m, id, cost - funds);
			ThreatIncConfig.logQuiet("cb_hold_" + m.getId(), "Core build: " + m.getName() + " holds "
					+ (int) (cost - funds) + " supplies for a " + id + " (" + fid + " marine step, " + (int) short_ + " short)");
		}
		return true;
	}

	/**
	 * The one military step the colony lacks, by size, without starting it: {kind, id} where kind is "start"
	 * (id the industry to build) or "upgrade" (id the industry standing, its spec's upgrade is the target), or
	 * null when the ladder is complete for the colony's size. Ground defences need a slot like any industry
	 * (2026-10-20: started without one they failed forever and would have held HOME FIRST on it).
	 */
	protected static String[] militaryNext(MarketAPI m) {
		int s = m.getSize();
		boolean slot = Misc.getNumIndustries(m) < Misc.getMaxIndustries(m);
		Industry hq = m.getIndustry(Industries.PATROLHQ);
		Industry mil = m.getIndustry(Industries.MILITARYBASE);
		Industry high = m.getIndustry(Industries.HIGHCOMMAND);
		if (hq == null && mil == null && high == null && slot) {
			return new String[] { "start", Industries.PATROLHQ };
		}
		Industry ground = m.getIndustry(Industries.GROUNDDEFENSES);
		Industry heavy = m.getIndustry(Industries.HEAVYBATTERIES);
		if (ground == null && heavy == null && slot) {
			return new String[] { "start", Industries.GROUNDDEFENSES };
		}
		if (s >= 4 && upgradable(hq)) return new String[] { "upgrade", hq.getId() };
		Industry station = ThreatFrontlines.stationOf(m);
		if (station == null) {
			return new String[] { "start", ThreatFrontlines.orbitalStationFor(m.getFaction()) };
		}
		int tier = ThreatFrontlines.stationTier(station);
		if (s >= 4 && tier == 1 && upgradable(station)) return new String[] { "upgrade", station.getId() };
		if (s >= 5 && upgradable(ground)) return new String[] { "upgrade", ground.getId() };
		if (s >= 6 && tier == 2 && upgradable(station)) return new String[] { "upgrade", station.getId() };
		if (s >= 6 && upgradable(mil)) return new String[] { "upgrade", mil.getId() };
		return null;
	}

	/** Whether the industry stands, has an upgrade and is not building or upgrading now. */
	protected static boolean upgradable(Industry ind) {
		return ind != null && ind.getSpec().getUpgrade() != null && !ind.isBuilding() && !ind.isUpgrading();
	}

	/** The one military step the colony lacks, by size (militaryNext); true when a build or upgrade started. */
	protected static boolean militaryStep(MarketAPI m) {
		String[] next = militaryNext(m);
		if (next == null) return false;
		if ("start".equals(next[0])) return start(m, next[1]);
		return upgrade(m, m.getIndustry(next[1]));
	}

	/** The price the step could not pay, held at the colony (ThreatFactionStock.hold) so upkeep and hunts leave it; logged once a colony. */
	protected static void holdMilitary(MarketAPI m, String fid, String standingId) {
		Industry ind = m.getIndustry(standingId);
		String target = ind != null && ind.getSpec().getUpgrade() != null ? ind.getSpec().getUpgrade() : standingId;
		float cost = ThreatBuildCost.supplies(target), funds = ThreatFrontlines.buildFunds(m);
		if (cost <= funds) return;
		ThreatFactionStock.hold(m, target, cost - funds);
		ThreatIncConfig.logQuiet("cb_hold_" + m.getId(), "Core build: " + m.getName() + " holds " + (int) (cost - funds)
				+ " supplies for a " + target + " (" + fid + " home first)");
	}

	// ------------------------------------------------------------------
	// HOME FIRST (the user, 2026-10-20)
	// ------------------------------------------------------------------

	protected static Map<String, Object> home() {
		return ThreatIncData.map(KEY_HOME);
	}

	/**
	 * HOME FIRST (the user, 2026-10-20: "the human response to first strike should be consolidate ... only
	 * start striking and founding forward bases once they exhausted internal investment options"): whether the
	 * mobilised faction still holds its war at home. True from its mobilisation until its core is complete
	 * (coreComplete: every core colony's military ladder at its size's top, yards built, nothing building) -
	 * then done for the war, never re-entered when a colony grows (it would stop the war mid-way). The
	 * stance reads CONSOLIDATE while it holds (ThreatFactionStance), the founding pass and the council's
	 * plan skip the faction, and the core build runs every site a pass. homeFirstMaxDays (0 = never) ends a
	 * hold the stockpiles never pay off, logged - a deadlock hatch, not a pace.
	 */
	public static boolean homeFirstHolds(String fid) {
		if (fid == null || !enabled() || !ThreatIncConfig.homeFirst()) return false;
		if (Factions.PLAYER.equals(fid) || Factions.THREAT.equals(fid)) return false;
		if (!ThreatWarState.warFactionIds().contains(fid) || ThreatWarState.excluded(fid)) return false;
		Map<String, Object> h = home();
		if (h.containsKey(fid + "|done")) return false;
		long now = Global.getSector().getClock().getTimestamp();
		Object since = h.get(fid + "|since");
		if (!(since instanceof Long)) {
			h.put(fid + "|since", now);
			ThreatIncConfig.log("Home first: " + fid + " holds its war at home until its core is complete - " + homeFirstStatus(fid));
			return true;
		}
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) since);
		if (coreComplete(fid)) {
			h.put(fid + "|done", now);
			ThreatIncConfig.log("Home first: " + fid + " core complete after " + (int) days + " d - founding and plays resume ("
					+ homeFirstStatus(fid) + ")");
			return false;
		}
		float max = ThreatIncConfig.homeFirstMaxDays();
		if (max > 0f && days > max) {
			h.put(fid + "|done", now);
			ThreatIncConfig.log("Home first: " + fid + " hold lapsed after " + (int) days + " d with its core incomplete - "
					+ homeFirstStatus(fid) + "; founding and plays resume");
			return false;
		}
		return true;
	}

	/** A faction's core colonies for HOME FIRST: its own, in the economy, no forward base, not the player's, size 3 up - building or not. */
	protected static List<MarketAPI> coreSites(String fid) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatReserves.marketsOf(fid)) {
			if (m == null || !m.isInEconomy() || ThreatFrontlines.isOutpost(m) || m.isPlayerOwned()) continue;
			if (m.getSize() < 3) continue;
			out.add(m);
		}
		return out;
	}

	/** Whether every core colony's ladder is at its size's top (militaryNext null), its yard built where one can stand (yardNeeded), and nothing is building. */
	protected static boolean coreComplete(String fid) {
		for (MarketAPI m : coreSites(fid)) {
			if (ThreatFrontlines.projectRunning(m)) return false;
			if (militaryNext(m) != null) return false;
			if (yardNeeded(fid, m)) return false;
		}
		return true;
	}

	/**
	 * Whether yardStep would still act at the colony: no yard where one can stand (a free slot, the inputs
	 * importable), or a Heavy Industry standing that Orbital Works could replace. False while the yards are
	 * paused for the marines (the balance), or with yards first off.
	 */
	protected static boolean yardNeeded(String fid, MarketAPI m) {
		if (!ThreatIncConfig.yardsFirst() || !ThreatIncConfig.frontlineHeavyIndustry()) return false;
		if (ThreatIncConfig.marineLevy() && ThreatFactionStock.hullsOutcoverMarines(fid)) return false;
		String yard = Industries.HEAVYINDUSTRY;
		Industry hi = m.getIndustry(yard);
		if (hi == null) {
			if (m.getIndustry(Industries.ORBITALWORKS) != null) return false;
			return ThreatFrontlines.wantsProducer(m, yard) && Misc.getNumIndustries(m) < Misc.getMaxIndustries(m)
					&& ThreatFrontlines.canSupplyProducer(m, m.getSize(), yard);
		}
		return hi.getSpec().getUpgrade() != null;
	}

	/** One phrase for the log: colonies complete of the core, building, steps and yards still wanted. */
	public static String homeFirstStatus(String fid) {
		List<MarketAPI> core = coreSites(fid);
		int complete = 0, building = 0, steps = 0, yards = 0;
		for (MarketAPI m : core) {
			boolean b = ThreatFrontlines.projectRunning(m);
			boolean st = militaryNext(m) != null, y = yardNeeded(fid, m);
			if (b) building++;
			if (st) steps++;
			if (y) yards++;
			if (!b && !st && !y) complete++;
		}
		return complete + " of " + core.size() + " core colonies complete, " + building + " building, " + steps
				+ " military steps and " + yards + " yards wanted";
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
