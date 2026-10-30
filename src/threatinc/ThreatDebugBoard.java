package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE DEBUG BOARD (the user, 2026-10-30: "when debug mode is on can you add a special threat
 * strategy board that shows me exactly what its doing/planning, where its sending strata etc ...
 * doesnt have to be pretty just informative"). A tab of the war board, debug mode only
 * (ThreatIncConfig.debugMode, ThreatFactionView.selectorIds): the swarm's whole state, nothing
 * veiled by the fog - the stance and its reasoning, the offensive's last verdict, what it saves
 * for and holds back, every strike order and strike in flight, every shift and budding order,
 * every swarm flying between hive worlds, every hive system's posture and every hive world's
 * strata, keep, Core state and flow. Text tables; the figures are the live ones.
 */
public class ThreatDebugBoard {

	public static final String VIEW_DEBUG = "debug";

	public static void render(ThreatIncursionIntel intel, TooltipMakerAPI main, float width, float opad) {
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
		Color bright = threat.getBaseUIColor(), dark = threat.getDarkUIColor();
		Color text = Misc.getTextColor(), hl = Misc.getHighlightColor(), bad = Misc.getNegativeHighlightColor(),
				grey = Misc.getGrayColor(), good = ThreatNotice.goodColor();
		float day = ThreatPosture.today();
		List<MarketAPI> hives = ThreatIncData.getAllLiveColonyMarkets();
		float rowH = ThreatWarBoard.ROW_H;

		// ---- the hive ----
		main.addSectionHeading("Debug - the hive", bright, dark, Alignment.MID, opad);
		float strata = 0f, spare = 0f, staged = 0f, standing = 0f;
		int systems = ThreatIncData.colonyMarkets().size();
		for (MarketAPI m : hives) {
			strata += ThreatStrata.of(m);
			spare += ThreatColonyManager.strikeSpareFP(m);
			staged += ThreatColonyManager.staged(m.getId());
			standing += ThreatColonyManager.ownedFleetFP(m, ThreatIncData.garrisonsFor(m.getId()));
		}
		main.addPara("Day " + (int) day + ". Stance " + ThreatStance.stanceName() + " - " + ThreatStance.summary, text, opad);
		main.addPara("Losing: " + ThreatStance.losingSummary(), text, 3f);
		main.addPara("Worlds " + hives.size() + " in " + systems + " systems. Strata " + n(strata) + " (" + n(ThreatStrata.fpOf(strata))
				+ " FP), spare above the keeps " + n(spare) + " FP, staged " + n(staged) + " FP, swarms standing or flying home " + n(standing)
				+ " FP. Strike fund " + n(ThreatColonyManager.strikeFund()) + " FP (escrow " + n(ThreatColonyManager.strikeEscrow())
				+ ", budded toward strikes " + n(ThreatBudding.strikeBound()) + "), Cores grow " + n(ThreatColonyManager.hiveFabricationPerMonth())
				+ " FP a month. Fuel " + n(ThreatFuel.stock()) + " (" + (ThreatFuel.perMonth() >= 0 ? "+" : "") + n(ThreatFuel.perMonth())
				+ "/mo), supplies " + n(ThreatFuel.stock(Commodities.SUPPLIES)) + ".", text, 3f);
		main.addPara("Defence short: " + ThreatPosture.defenceShortSummary(), text, 3f);
		StringBuilder crit = new StringBuilder();
		for (String id : ThreatColonyManager.criticalProducers()) {
			MarketAPI m = ThreatIncData.resolveColonyMarket(id);
			if (m == null) continue;
			if (crit.length() > 0) crit.append(", ");
			crit.append(m.getName()).append(" (").append(m.getSize()).append(")");
		}
		main.addPara("Critical producers (keep their size from strikes and shifts): " + (crit.length() > 0 ? crit.toString() : "none"), text, 3f);

		// ---- the offensive ----
		main.addSectionHeading("The offensive", bright, dark, Alignment.MID, opad);
		String[] last = ThreatOffensive.lastPassNote();
		main.addPara(last != null ? "Last pass, day " + last[0] + ": " + last[1] : "No pass yet.", text, opad);
		main.addPara("Cheapest priced prong " + n(ThreatOffensive.cheapestProngCost()) + " FP; campaign start day "
				+ (Float.isNaN(ThreatOffensive.startDay()) ? "none" : "" + (int) ThreatOffensive.startDay()) + ".", text, 3f);

		Map<String, Float> saving = ThreatOffensive.savingStagings();
		main.addPara("Saving for (the shift fills these stagings):", hl, opad);
		if (saving.isEmpty()) {
			main.addPara("nothing", grey, 3f);
		} else {
			main.beginTable2(threat, rowH, true, true, "Staging", w(width, 0.22f), "System", w(width, 0.22f), "Bill FP", w(width, 0.14f),
					"Held FP", w(width, 0.14f), "Short FP", w(width, 0.14f), "Room FP", w(width, 0.14f));
			for (Map.Entry<String, Float> e : saving.entrySet()) {
				MarketAPI s = ThreatIncData.resolveColonyMarket(e.getKey());
				if (s == null || s.getStarSystem() == null) continue;
				float held = heldFP(s.getStarSystem().getId());
				float room = 0f;
				for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(s.getStarSystem().getId())) room += ThreatStrata.fpOf(ThreatStrata.room(c));
				main.addRow(Alignment.LMID, text, s.getName(), Alignment.LMID, text, s.getStarSystem().getBaseName(),
						Alignment.MID, hl, n(e.getValue()), Alignment.MID, text, n(held),
						Alignment.MID, e.getValue() - held > 0 ? bad : good, n(Math.max(0f, e.getValue() - held)), Alignment.MID, text, n(room));
			}
			main.addTable("none", -1, 3f);
		}

		List<String> schedule = ThreatOffensive.schedule();
		main.addPara("Held prongs (waiting their day so the campaign arrives together):", hl, opad);
		if (schedule.isEmpty()) {
			main.addPara("none", grey, 3f);
		} else {
			main.beginTable2(threat, rowH, true, true, "Target", w(width, 0.25f), "Staging", w(width, 0.25f), "Sails day", w(width, 0.12f),
					"Cost FP", w(width, 0.12f), "Fuel", w(width, 0.12f), "Expected", w(width, 0.14f));
			for (String e : schedule) {
				String[] f = e.split("\\|");
				if (f.length < 7) continue;
				MarketAPI target = Global.getSector().getEconomy().getMarket(f[0]);
				MarketAPI stagingM = Global.getSector().getEconomy().getMarket(f[1]);
				main.addRow(Alignment.LMID, text, target != null ? target.getName() : f[0], Alignment.LMID, text, stagingM != null ? stagingM.getName() : f[1],
						Alignment.MID, text, f[3].length() > 6 ? f[3].substring(0, 6) : f[3], Alignment.MID, hl, nf(f[4]), Alignment.MID, text, nf(f[5]),
						Alignment.MID, text, nf(f[6]));
			}
			main.addTable("none", -1, 3f);
		}

		main.addPara("Strike orders (bills budding at their staging):", hl, opad);
		List<MarketAPI> ordered = new ArrayList<MarketAPI>();
		for (MarketAPI m : hives) if (ThreatBudding.hasStrikeOrder(m)) ordered.add(m);
		if (ordered.isEmpty()) {
			main.addPara("none", grey, 3f);
		} else {
			main.beginTable2(threat, rowH, true, true, "Staging", w(width, 0.2f), "Target", w(width, 0.2f), "Want FP", w(width, 0.12f),
					"Staged", w(width, 0.12f), "On the way", w(width, 0.12f), "Age d", w(width, 0.1f), "Sys flow/d", w(width, 0.14f));
			for (MarketAPI m : ordered) {
				MemoryAPI mem = m.getMemoryWithoutUpdate();
				String tid = mem.getString(ThreatBudding.KEY_STRIKE_TARGET);
				MarketAPI target = tid != null ? Global.getSector().getEconomy().getMarket(tid) : null;
				float want = mem.getFloat(ThreatBudding.KEY_STRIKE_WANT), st = ThreatColonyManager.staged(m.getId()), in = ThreatBudding.strikeInbound(m);
				main.addRow(Alignment.LMID, text, m.getName(), Alignment.LMID, text, target != null ? target.getName() : "?",
						Alignment.MID, hl, n(want), Alignment.MID, st >= want - 0.5f ? good : text, n(st), Alignment.MID, text, n(in),
						Alignment.MID, text, "" + (int) (day - mem.getFloat(ThreatBudding.KEY_STRIKE_SINCE)),
						Alignment.MID, text, n(ThreatBudding.flowPerDay(m.getStarSystem())));
			}
			main.addTable("none", -1, 3f);
		}

		main.addPara("Strikes in flight:", hl, opad);
		List<ThreatStrikeFGI> strikes = new ArrayList<ThreatStrikeFGI>();
		for (Object o : IncursionManager.getStrikeList()) {
			if (o instanceof ThreatStrikeFGI && !((ThreatStrikeFGI) o).isEnded()) strikes.add((ThreatStrikeFGI) o);
		}
		if (strikes.isEmpty()) {
			main.addPara("none", grey, 3f);
		} else {
			main.beginTable2(threat, rowH, true, true, "Targets", w(width, 0.4f), "From", w(width, 0.22f), "Fleets", w(width, 0.12f),
					"FP", w(width, 0.12f), "Ending", w(width, 0.14f));
			for (ThreatStrikeFGI s : strikes) {
				StringBuilder t = new StringBuilder();
				if (s.getParams() != null && s.getParams().raidParams != null) {
					for (MarketAPI m : s.getParams().raidParams.allowedTargets) {
						if (t.length() > 0) t.append(", ");
						t.append(m.getName());
					}
				}
				float fp = 0f;
				int fleets = 0;
				for (CampaignFleetAPI f : s.getFleets()) {
					if (f == null || !f.isAlive()) continue;
					fleets++;
					fp += f.getFleetPoints();
				}
				main.addRow(Alignment.LMID, text, main.shortenString(t.toString(), w(width, 0.4f) - 10f),
						Alignment.LMID, text, s.getParams() != null && s.getParams().source != null ? s.getParams().source.getName() : "?",
						Alignment.MID, text, "" + fleets, Alignment.MID, hl, n(fp), Alignment.MID, text, s.isEnding() ? "yes" : "no");
			}
			main.addTable("none", -1, 3f);
		}

		// ---- shifts and budding ----
		main.addSectionHeading("Shifts and budding", bright, dark, Alignment.MID, opad);
		main.addPara("Orders (a world's pool fills at its flow, then spawns whole; a shift flies to its receiver):", hl, opad);
		List<MarketAPI> budding = new ArrayList<MarketAPI>();
		for (MarketAPI m : hives) if (ThreatBudding.hasOrder(m)) budding.add(m);
		if (budding.isEmpty()) {
			main.addPara("none", grey, 3f);
		} else {
			main.beginTable2(threat, rowH, true, true, "World", w(width, 0.18f), "System", w(width, 0.16f), "Kind", w(width, 0.08f),
					"Want FP", w(width, 0.1f), "Pool", w(width, 0.1f), "Flow/d", w(width, 0.08f), "To", w(width, 0.18f), "Age d", w(width, 0.12f));
			for (MarketAPI m : budding) {
				MemoryAPI mem = m.getMemoryWithoutUpdate();
				MarketAPI to = ThreatBudding.receiverOf(m);
				String forStrike = mem.getString(ThreatBudding.KEY_FOR_STRIKE);
				main.addRow(Alignment.LMID, text, m.getName(), Alignment.LMID, text, m.getStarSystem() != null ? m.getStarSystem().getBaseName() : "?",
						Alignment.MID, text, ThreatBudding.kind(m) + (forStrike != null ? "*" : ""), Alignment.MID, hl, n(ThreatBudding.want(m)),
						Alignment.MID, text, n(ThreatBudding.pool(m)), Alignment.MID, text, n(ThreatBudding.flowPerDay(m)),
						Alignment.LMID, text, to != null ? to.getName() : "-",
						Alignment.MID, text, mem.contains(ThreatBudding.KEY_SINCE) ? "" + (int) (day - mem.getFloat(ThreatBudding.KEY_SINCE)) : "?");
			}
			main.addTable("none", -1, 3f);
			main.addPara("* a shift budding a strike's share: staged whole at the receiver, not absorbed.", grey, 2f);
		}

		main.addPara("Swarms flying between hive worlds:", hl, opad);
		List<CampaignFleetAPI> flying = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI f : ThreatIncData.reinforcementFleets().values()) if (f != null && f.isAlive()) flying.add(f);
		if (flying.isEmpty()) {
			main.addPara("none", grey, 3f);
		} else {
			main.beginTable2(threat, rowH, true, true, "Fleet", w(width, 0.2f), "FP", w(width, 0.1f), "From", w(width, 0.22f),
					"To", w(width, 0.22f), "For strike", w(width, 0.12f), "Days out", w(width, 0.14f));
			for (CampaignFleetAPI f : flying) {
				MemoryAPI fm = f.getMemoryWithoutUpdate();
				MarketAPI from = resolve(fm.getString(ThreatColonyManager.REINFORCE_FROM_KEY));
				MarketAPI to = resolve(fm.getString(ThreatColonyManager.REINFORCE_TARGET_KEY));
				main.addRow(Alignment.LMID, text, f.getName(), Alignment.MID, hl, n(f.getFleetPoints()),
						Alignment.LMID, text, from != null ? from.getName() : "?", Alignment.LMID, text, to != null ? to.getName() : "?",
						Alignment.MID, text, fm.contains(ThreatBudding.FOR_STRIKE) ? "yes" : "no",
						Alignment.MID, text, fm.contains(ThreatColonyManager.REINFORCE_DAY_KEY) ? "" + (int) (day - fm.getFloat(ThreatColonyManager.REINFORCE_DAY_KEY)) : "?");
			}
			main.addTable("none", -1, 3f);
		}

		// ---- systems ----
		main.addSectionHeading("Systems (posture)", bright, dark, Alignment.MID, opad);
		Map<String, float[]> state = ThreatPosture.state();
		List<String> systemIds = new ArrayList<String>(ThreatIncData.colonyMarkets().keySet());
		final Map<String, float[]> st = state;
		Collections.sort(systemIds, new Comparator<String>() {
			public int compare(String a, String b) {
				float[] sa = st.get(a), sb = st.get(b);
				return Float.compare(sb != null ? sb[0] : 0f, sa != null ? sa[0] : 0f);
			}
		});
		main.beginTable2(threat, rowH, true, true, "System", w(width, 0.18f), "Mode", w(width, 0.1f), "Pressure", w(width, 0.09f),
				"Want", w(width, 0.08f), "Need", w(width, 0.08f), "Standing", w(width, 0.09f), "Exposure", w(width, 0.08f),
				"Worlds", w(width, 0.07f), "Strata", w(width, 0.1f), "Attacked", w(width, 0.07f), "Human ly", w(width, 0.06f));
		for (String id : systemIds) {
			List<MarketAPI> worlds = ThreatIncData.getLiveColonyMarkets(id);
			if (worlds.isEmpty()) continue;
			StarSystemAPI system = ThreatScoutRoute.systemById(id);
			float[] s = state.get(id);
			float sumStrata = 0f, held = 0f;
			for (MarketAPI c : worlds) {
				sumStrata += ThreatStrata.of(c);
				held += ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId()));
			}
			int mode = s != null ? (int) s[4] : 0;
			boolean attacked = system != null && ThreatPosture.underAttack(system);
			float ly = system != null ? ThreatPosture.nearestKnownHumanLY(system) : Float.MAX_VALUE;
			main.addRow(Alignment.LMID, text, system != null ? system.getBaseName() : id,
					Alignment.MID, mode >= 2 ? bad : mode == 1 ? hl : text, mode >= 0 && mode < ThreatPosture.MODE_NAMES.length ? ThreatPosture.MODE_NAMES[mode] : "?",
					Alignment.MID, text, s != null ? n(s[0]) : "-", Alignment.MID, text, s != null ? n(s[1]) : "-",
					Alignment.MID, text, s != null ? n(s[5]) : "-", Alignment.MID, text, n(held),
					Alignment.MID, text, s != null ? String.format("%.2f", s[8]) : "-", Alignment.MID, text, "" + worlds.size(),
					Alignment.MID, hl, n(sumStrata), Alignment.MID, attacked ? bad : text, attacked ? "yes" : "no",
					Alignment.MID, text, ly < Float.MAX_VALUE / 2f ? "" + (int) ly : "-");
		}
		main.addTable("none", -1, 3f);

		// ---- worlds ----
		main.addSectionHeading("Worlds (strata)", bright, dark, Alignment.MID, opad);
		List<MarketAPI> sorted = new ArrayList<MarketAPI>(hives);
		Collections.sort(sorted, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				String sa = a.getStarSystem() != null ? a.getStarSystem().getBaseName() : "", sb = b.getStarSystem() != null ? b.getStarSystem().getBaseName() : "";
				int c = sa.compareTo(sb);
				return c != 0 ? c : Float.compare(ThreatStrata.of(b), ThreatStrata.of(a));
			}
		});
		main.beginTable2(threat, rowH, true, true, "World", w(width, 0.14f), "System", w(width, 0.12f), "Size", w(width, 0.04f),
				"Strata", w(width, 0.08f), "Keep", w(width, 0.07f), "Spare FP", w(width, 0.07f), "Staged", w(width, 0.06f),
				"Core", w(width, 0.17f), "Strata/d", w(width, 0.06f), "Flow/d", w(width, 0.06f), "Standing", w(width, 0.06f),
				"Over", w(width, 0.05f), "Doing", w(width, 0.12f));
		for (MarketAPI m : sorted) {
			ThreatColonyManager.CoreState cs = ThreatColonyManager.coreState(m);
			String core = cs.coreDown ? "no Core" : cs.consuming ? "consuming" : cs.word() + " m" + cs.metalsHave + "/" + cs.metalsDemand
					+ " f" + cs.fuelHave + "/" + cs.fuelDemand + (cs.famine ? " famine" : "") + (cs.nextSizeShort ? " next short" : "");
			float keep = ThreatColonyManager.keepStrata(m);
			boolean critical = ThreatColonyManager.criticalProducers().contains(m.getId());
			String doing = ThreatBudding.flowText(m);
			float over = ThreatPosture.forceOver(m);
			main.addRow(Alignment.LMID, critical ? hl : text, m.getName() + (critical ? "*" : ""),
					Alignment.LMID, text, m.getStarSystem() != null ? m.getStarSystem().getBaseName() : "?",
					Alignment.MID, text, "" + m.getSize(), Alignment.MID, hl, n(ThreatStrata.of(m)), Alignment.MID, text, n(keep),
					Alignment.MID, text, n(ThreatColonyManager.strikeSpareFP(m)), Alignment.MID, text, n(ThreatColonyManager.staged(m.getId())),
					Alignment.LMID, cs.starving() ? bad : cs.growing() ? good : text, core,
					Alignment.MID, cs.starving() ? bad : text, cs.strataPerDay == 0f ? "0" : String.format("%+.0f", cs.strataPerDay),
					Alignment.MID, text, n(ThreatBudding.flowPerDay(m)),
					Alignment.MID, text, n(ThreatColonyManager.ownedFleetFP(m, ThreatIncData.garrisonsFor(m.getId()))),
					Alignment.MID, over > 0f ? bad : text, n(over), Alignment.LMID, text, doing != null ? doing : "-");
		}
		main.addTable("none", -1, 3f);
		main.addPara("* critical producer. Keep: strata a strike or shift leaves. Core: m metals, f fuel, have/demand. Over: hostile FP over the world.", grey, 2f);
	}

	/** Fleet points a staging system holds toward a bill, as the shift counts them (ThreatBudding.shift). */
	protected static float heldFP(String systemId) {
		float held = 0f;
		for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(systemId)) {
			held += ThreatColonyManager.strikeSpareFP(c) + ThreatColonyManager.staged(c.getId())
					+ ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId())) + ThreatBudding.pendingFP(c);
		}
		return held;
	}

	protected static MarketAPI resolve(String marketId) {
		if (marketId == null) return null;
		MarketAPI m = ThreatIncData.resolveColonyMarket(marketId);
		return m != null ? m : Global.getSector().getEconomy().getMarket(marketId);
	}

	protected static float w(float width, float frac) {
		return (float) Math.floor(width * frac);
	}

	protected static String n(float v) {
		return String.format("%,d", (int) v);
	}

	protected static String nf(String s) {
		try {
			return n(Float.parseFloat(s));
		} catch (NumberFormatException e) {
			return s;
		}
	}
}
