package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivIntel;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The permanent intel card of a world the war destroyed, in place of vanilla's
 * "Name - Destroyed" (the user, 2026-10-31: "is there a way to have an
 * indication of who destroyed whose colony?"). Vanilla's {@link DecivIntel}
 * knows the market and its former owner alone; this one carries the destroyer
 * and how: "Killa - Destroyed by Hegemony", "Hanan Pacha - Lost to the Threat",
 * "Name - Starved". Same tag, sort, icon and map pin as vanilla's, so the Intel
 * screen files it with the rest. Vanilla's own stability decivs, which have no
 * destroyer, keep vanilla's card.
 *
 * <p>Built BEFORE the teardown (the owner is read off the market) and posted by
 * {@link #destroy}, which runs the teardown with vanilla's card switched off.
 */
public class ThreatDecivIntel extends DecivIntel {

	/** The destroyer's faction id; null when the world starved. */
	protected String byFactionId;
	/** "ground assault", "bombardment", "starved": the one-word how. */
	protected String how;

	public ThreatDecivIntel(MarketAPI market, String byFactionId, String how) {
		super(market, market.getPrimaryEntity(), true, false);
		this.byFactionId = byFactionId;
		this.how = how != null ? how : "destroyed";
	}

	/**
	 * Runs vanilla's full teardown of {@code market} with vanilla's card off and
	 * files this one instead. {@code byFactionId} null = starved, no destroyer.
	 */
	public static void destroy(MarketAPI market, String byFactionId, String how) {
		if (market == null) return;
		ThreatDecivIntel intel = new ThreatDecivIntel(market, byFactionId, how);
		// fullDestroy bypasses NO_DECIV_KEY (verified against 0.98a source)
		DecivTracker.decivilize(market, true, false);
		// no card when the teardown did not run (a discoverable entity)
		if (market.isPlanetConditionMarketOnly()) Global.getSector().getIntelManager().addIntel(intel);
	}

	protected FactionAPI by() {
		return byFactionId != null ? Global.getSector().getFaction(byFactionId) : null;
	}

	/** "Hegemony", the player's own faction name, "the Threat". */
	protected String byName() {
		FactionAPI f = by();
		if (f == null) return byFactionId != null ? byFactionId : "nobody";
		if (Factions.THREAT.equals(f.getId())) return "the Threat";
		return f.isPlayerFaction() ? f.getDisplayName() : Misc.ucFirst(f.getDisplayName());
	}

	protected Color byColor() {
		FactionAPI f = by();
		return f != null ? f.getBaseUIColor() : Misc.getGrayColor();
	}

	@Override
	public String getName() {
		if (byFactionId == null) return name + " - Starved";
		if (Factions.THREAT.equals(byFactionId)) return name + " - Lost to the Threat";
		return name + " - Destroyed by " + byName();
	}

	@Override
	public String getSortString() {
		return "Decivilized " + name;
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		FactionAPI crest = by() != null ? by() : faction;
		if (crest != null) info.addImage(crest.getLogo(), width, 128, opad);
		String owner = faction != null ? Misc.ucFirst(faction.getDisplayName()) : "nobody";
		Color ownerColor = faction != null ? faction.getBaseUIColor() : Misc.getGrayColor();
		info.addPara("Owner: %s", opad, ownerColor, owner);
		if (byFactionId == null) {
			info.addPara("Starved: the strata fell to nothing.", opad);
		} else {
			info.addPara("Destroyed by: %s", opad, byColor(), byName());
			info.addPara("How: %s", opad, Misc.getHighlightColor(), how);
		}
	}
}
