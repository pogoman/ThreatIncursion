package threatinc;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

/**
 * BASES - one handle over the two kinds of place the war layer ships to and
 * from: a COLONY (a real market, with a reserve that accrues from its vanilla
 * surplus, a garrison floor and War footing demand) and an OUTPOST
 * ({@link ThreatOutposts} - a station in orbit over a dead world, with a
 * stockpile and nothing else).
 *
 * <p>The stockpile is the same {@link ThreatReserves} map: an outpost is keyed
 * by its station ENTITY id instead of a market id, so every deposit/draw the
 * layer already does works unchanged. What differs is the FLOOR - an outpost
 * has none. It banks nothing on its own (there is no economy to bank from) and
 * carries no War footing, so everything in it was carried or won there, and
 * all of it is available to a sortie. When the world under it is colonised the
 * stock comes ashore with the station ({@link ThreatOutposts#carryOver}).
 */
public class ThreatBases {

	/** A colony or an outpost, whichever holds this reserve. */
	public static class Base {
		public final MarketAPI market;
		public final ThreatOutposts.Outpost outpost;

		protected Base(MarketAPI market, ThreatOutposts.Outpost outpost) {
			this.market = market;
			this.outpost = outpost;
		}

		public boolean isOutpost() {
			return outpost != null;
		}

		/** The reserve key: a market id, or the outpost's station entity id. */
		public String id() {
			return outpost != null ? ThreatOutposts.stockId(outpost) : market.getId();
		}

		public String name() {
			return outpost != null ? outpost.planetName() + " Outpost" : market.getName();
		}

		public String factionId() {
			return outpost != null ? outpost.factionId : market.getFactionId();
		}

		/** Where fleets spawn and returning fleets aim: the station, or the colony's planet. */
		public SectorEntityToken entity() {
			return outpost != null ? outpost.entity : market.getPrimaryEntity();
		}

		public StarSystemAPI starSystem() {
			SectorEntityToken e = entity();
			if (e != null && e.getStarSystem() != null) return e.getStarSystem();
			return market != null ? market.getStarSystem() : null;
		}

		public Vector2f hyperLoc() {
			SectorEntityToken e = entity();
			if (e != null) return e.getLocationInHyperspace();
			return market != null ? market.getLocationInHyperspace() : null;
		}

		/**
		 * The market FleetParamsV3 should build from (quality, fleet-size
		 * stat). Null for an outpost - it has no economy to build from.
		 */
		public MarketAPI sourceMarket() {
			return market;
		}
	}

	public static Base of(MarketAPI market) {
		return market == null ? null : new Base(market, null);
	}

	public static Base of(ThreatOutposts.Outpost outpost) {
		if (outpost == null || ThreatOutposts.stockId(outpost) == null) return null;
		return new Base(null, outpost);
	}

	/** Resolves a reserve key to the place that holds it: a colony first, else an outpost. */
	public static Base of(String id) {
		if (id == null) return null;
		MarketAPI market = Global.getSector().getEconomy().getMarket(id);
		if (market != null) return new Base(market, null);
		return of(ThreatOutposts.byStockId(id));
	}

	/** Whether this reserve key belongs to an outpost rather than a colony. */
	public static boolean isOutpostId(String id) {
		Base b = of(id);
		return b != null && b.isOutpost();
	}

	/**
	 * What a sortie may actually take: a colony's stock above its garrison
	 * floor, or the whole of an outpost's stockpile (it has no floor - a
	 * station in orbit is not a home garrison with a militia to keep).
	 */
	public static float available(Base base, String commodityId) {
		if (base == null) return 0f;
		if (base.isOutpost()) return ThreatReserves.stock(base.id(), commodityId);
		return ThreatReserves.available(base.market, commodityId);
	}

	public static float available(String id, String commodityId) {
		return available(of(id), commodityId);
	}

	/** Takes up to {@code amount}, never below a colony's floor; returns what was taken. */
	public static float draw(Base base, String commodityId, float amount) {
		if (base == null || amount <= 0f) return 0f;
		if (base.isOutpost()) return ThreatReserves.draw(base.id(), commodityId, amount);
		return ThreatReserves.drawAbove(base.market, commodityId, amount);
	}

	public static void deposit(Base base, String commodityId, float amount) {
		if (base == null) return;
		ThreatReserves.deposit(base.id(), commodityId, amount);
	}

	/** Display name of a reserve key that may be either kind of base. */
	public static String nameOf(String id) {
		Base b = of(id);
		return b != null ? b.name() : id;
	}
}
