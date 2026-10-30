package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE LEDGER'S NOTES (the user, 2026-10-30 16:30: "Each entry should read Worlds: x, Mass: y Remark: Size 8
 * Fuel Production or Remark: Starving, Remark: Feeding ... dont worry about isolated etc player can figure
 * that out via map"). Everything here reads the player's report of the system (ThreatIntel: what a scout,
 * an allied fleet or the player's own fleet last saw there), never the live world. The cell
 * ({@link #cellText}): the worlds, the strata mass as seen, and one remark ({@link #remark}) - "Scout
 * lost" (a party sent there lost since the last look, ThreatIntel.lostDay), "Never scouted", "Strike
 * staging" (swarms massing for a strike when seen), "Starving" (a Core starving when seen), the system's
 * largest producer as "Size 8 Fuel Production" (fuel before metals before ore at a size), "Feeding" (a
 * Core fed in full and growing when seen). The Age column is days since the newest of the report and a
 * loss ({@link #ageText}); the row tooltip lists each world as seen.
 */
public class ThreatObjectives {

	/** The cell: "Worlds: 3, Mass: 24,000, Size 8 Fuel Production"; a seeded system its stage alone. */
	public static String cellText(ThreatWarBoard.Entry e) {
		if (!e.isColony()) {
			return ThreatIncData.STAGE_COLONIZING.equals(e.stage) ? "Colonizing" : "Seeded";
		}
		float mass = massSeen(e);
		// no "Remark:" label (the user, 2026-10-30 17:00)
		return "Worlds: " + e.markets.size() + ", Mass: " + (mass >= 0f ? n(mass) : "?") + ", " + remark(e);
	}

	/** The one remark, the most important first. */
	public static String remark(ThreatWarBoard.Entry e) {
		float lost = ThreatIntel.lostDay(Factions.PLAYER, e.systemId);
		float seenDay = e.seen != null ? e.seen.day : -1f;
		if (lost >= 0f && lost > seenDay) return "Scout lost";
		if (e.seen == null) return "Never scouted";
		boolean starving = false, feeding = false, staging = false;
		for (MarketAPI m : e.markets) {
			ThreatIntel.Report s = ThreatWarBoard.seenOver(e.seen, m);
			if (s == null) continue;
			if (s.worldStaging(m.getId()) > 0f) staging = true;
			int fed = s.worldFed(m.getId());
			if (fed == ThreatWarBoard.STARVING) starving = true;
			if (fed == ThreatWarBoard.GROWING) feeding = true;
		}
		if (staging) return "Strike staging";
		if (starving) return "Starving";
		String best = bestMade(e);
		if (best != null) return best;
		if (feeding) return "Feeding";
		return "-";
	}

	/** The cell's colour: a lost party in the loss colour, a strike staging in the Threat's, nothing known grey, else the highlight. */
	public static Color noteColor(ThreatWarBoard.Entry e, Color bright, Color h, Color gray, Color loss) {
		String r = e.isColony() ? remark(e) : "";
		if ("Never scouted".equals(r) || "-".equals(r)) return gray;
		if ("Scout lost".equals(r)) return loss;
		if ("Strike staging".equals(r)) return bright;
		return h;
	}

	/** The Age cell: days since the newest of the player's report and a lost party, "today", "-" never. */
	public static String ageText(ThreatWarBoard.Entry e) {
		float lost = ThreatIntel.lostDay(Factions.PLAYER, e.systemId);
		float seenDay = e.seen != null ? e.seen.day : -1f;
		float latest = Math.max(lost, seenDay);
		if (ThreatIncConfig.debugMode()) {
			// the Age column read "-" on every row at 16:55 while the Notes beside it read the same report; the figures
			ThreatIncConfig.log("Age " + e.systemId + ": seen " + (e.seen != null ? e.seen.day + " " + e.seen.source + " by " + e.seen.seenBy : "null")
					+ ", lost " + lost + ", today " + ThreatPosture.today());
		}
		if (latest < 0f) return "-";
		int days = (int) Math.max(0f, ThreatPosture.today() - latest);
		return days < 1 ? "today" : days + " d";
	}

	/** The row tooltip's lines: the loss, the last look, then each world as the report saw it. */
	public static void addTooltip(TooltipMakerAPI tooltip, ThreatWarBoard.Entry e, float pad) {
		Color h = Misc.getHighlightColor();
		float lost = ThreatIntel.lostDay(Factions.PLAYER, e.systemId);
		float seenDay = e.seen != null ? e.seen.day : -1f;
		float first = pad;
		if (lost >= 0f && lost > seenDay) {
			tooltip.addPara("A scouting party sent there was lost %s days ago; nothing newer is known.", first, h,
					"" + (int) Math.max(0f, ThreatPosture.today() - lost));
			first = 4f;
		}
		if (e.seen == null) {
			tooltip.addPara("Never scouted.", first);
			return;
		}
		tooltip.addPara("Last look " + ThreatIntel.when(e.seen) + ".", first);
		for (MarketAPI m : e.markets) {
			ThreatIntel.Report s = ThreatWarBoard.seenOver(e.seen, m);
			if (s == null) continue;
			int size = s.worldSize(m.getId());
			float strata = s.worldStrata(m.getId());
			StringBuilder b = new StringBuilder(m.getName() + ": ");
			b.append(size > 0 ? "size " + size : "size ?");
			b.append(", ").append(strata >= 0f ? n(strata) + " strata" : "strata ?");
			for (String ind : s.worldMade(m.getId())) b.append(", ").append(what(ind));
			int fed = s.worldFed(m.getId());
			if (fed == ThreatWarBoard.STARVING) b.append(", Core starving");
			else if (fed == ThreatWarBoard.GROWING) b.append(", Core fed in full");
			else if (fed == ThreatWarBoard.HOLDING) b.append(", Core holding");
			float massing = s.worldStaging(m.getId());
			if (massing > 0f) b.append(", ").append(n(massing)).append(" FP massing for a strike");
			tooltip.addPara(b.toString() + ".", 4f);
		}
	}

	/** Strata over the system's worlds as the report saw them, -1 when none recorded. */
	protected static float massSeen(ThreatWarBoard.Entry e) {
		float mass = 0f;
		boolean any = false;
		for (MarketAPI m : e.markets) {
			ThreatIntel.Report s = ThreatWarBoard.seenOver(e.seen, m);
			float st = s != null ? s.worldStrata(m.getId()) : -1f;
			if (st < 0f) continue;
			mass += st;
			any = true;
		}
		return any ? mass : -1f;
	}

	/** "Size 8 Fuel Production": the largest producer the report saw in the system (fuel before metals before ore at a size); null with none. */
	protected static String bestMade(ThreatWarBoard.Entry e) {
		String bestInd = null;
		int bestSize = -1, bestRank = 0;
		for (MarketAPI m : e.markets) {
			ThreatIntel.Report s = ThreatWarBoard.seenOver(e.seen, m);
			if (s == null) continue;
			int size = s.worldSize(m.getId());
			for (String ind : s.worldMade(m.getId())) {
				int rank = Industries.FUELPROD.equals(ind) ? 3 : Industries.REFINING.equals(ind) ? 2 : 1;
				if (size > bestSize || (size == bestSize && rank > bestRank)) {
					bestSize = size;
					bestRank = rank;
					bestInd = ind;
				}
			}
		}
		if (bestInd == null) return null;
		return (bestSize > 0 ? "Size " + bestSize + " " : "") + what(bestInd);
	}

	protected static String what(String industry) {
		return Industries.FUELPROD.equals(industry) ? "Fuel Production" : Industries.REFINING.equals(industry) ? "Refining" : "Mining";
	}

	protected static String n(float v) {
		return Misc.getWithDGS(Math.round(v));
	}
}
