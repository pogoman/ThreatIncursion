package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;

/**
 * CALLS FOR AID AND THE OFFERS THAT ANSWER THEM (the user, 2026-10-31: "buttons below Requests
 * table header for each type like we have on domains, but these are just on/off toggles. when
 * toggle active that counts as you asking the sector for the given commodity. the table then will
 * have responses from other factions which you can accept or ignore"; docs/war-effort-rework.md
 * section 3). A {@link Call} is a standing toggle on the war board's Requests row: one Domain entry
 * (a colony of the player's, an outpost, or a hive world their landing attacks - the Colony button
 * cycles them) and one good (Fuel, Supplies, Marines, Arms, Hulls). While it stands every candidate
 * faction (Favourable or better, not hostile, {@link #candidates}) looks at it once a day on its
 * own clock ({@link #check}, aidRequestCheckDays) and OFFERS what it can send ({@link #quote}): all
 * that one colony of its spares of the good (the colony sparing the most in reach, {@link #donors}),
 * at least {@link #minSpare}, cut to the standing the player can spend with it without dropping
 * below Favourable. An {@link Offer} stands on the table until the player ACCEPTS it ({@link #accept}:
 * the convoy sails now - a plain convoy to a colony or outpost, a supply run to a front - with an
 * ETA, {@link #eta}) or REJECTS it ({@link #reject}: the faction offers nothing more on that call
 * until it is toggled off and on). An accepted offer can be CANCELLED while at sea ({@link #cancel}:
 * the convoy turns home, nothing charged). The price is standing, a WHOLE number of points charged
 * on receipt ({@link #charge}: the goods' value at the player's prices over aidRepPerCredits times
 * aidAskPriceMax), never below Favourable, set straight on the relationship so vanilla plays
 * neither its message nor its sound. A convoy lost at sea costs nothing ({@link #settleLost}). A
 * delivered offer leaves the table; with the call still on the faction may offer again.
 *
 * <p>Before this (2026-10-29 evening to 2026-10-30) a request was logged from a Transfer or Deploy
 * short of the colony's needs, one {@link Request} a world with a {@link Part} a good, and the
 * factions accepted and sailed on their own; those classes stay so an older save loads, and its
 * open parts become calls, its parts at sea accepted offers ({@link #migrateOld}).
 *
 * <p>THE SECTOR'S ASKS OF THE PLAYER (the user, 2026-10-31 evening: "if a faction is requesting something we
 * need to assume its directed at the player and with knowledge of the players availability ... the amount asked
 * should always be an amount the player can field. and the player can only send or ignore the request ... any
 * standing requests by a faction have a grace period of a few days where they ask the player first. but after
 * that period other factions can fulfill the request if they have capacity and are friendly with each other.
 * in which case the request should be logged as answered by that faction and should stay there with the ETA
 * etc. all faction war trade should go via this route"). The other way round from the calls above: an NPC
 * world's need - on the ledger (ThreatNeeds), its faction's stock plan short of fuel or supplies, its navy
 * short of hulls - is an {@link Ask} of the player. The coalition's loops raise it ({@link #sectorMayAnswer})
 * before any faction answers it, and for aidAskGraceDays from its first raise the ask is the player's alone;
 * the faction's tab shows it from the first raise at what the player can field (ThreatFactionView.addFactionNeeds)
 * with Send or nothing. After the grace every friendly faction with the capacity answers it, each sailing
 * recorded as an {@link Answer} ({@link #answered}: who, what, from where, the ETA) that the convoy or order
 * carries as its requestId and that stays on the tab until aidAnswerShownDays after it lands
 * ({@link #onAnswerLanded}, {@link #tickAsks}). Every raise, answer, landing and loss is logged. The
 * factions' own sharing between their colonies (ThreatConvoys.planLogistics) runs before the coalition, so a
 * need a faction can cover itself is never asked.
 *
 * <p>The mission board is gone since 2026-10-23: until then this class posted vanilla missions
 * ({@link ThreatAidMissionIntel}) for the same shortages. Its readers of a colony's state -
 * {@link #shortageStanding}, {@link #requestItems}, {@link #needsDefence} - feed the ledger.
 */
public class ThreatAidRequests {

	/** Persistent: "marketId:commodityId" -> when its deficit was first seen. */
	public static final String KEY_SHORTAGE = "threatinc_aidShortageSince";
	/** Persistent until 2026-10-31: the player's requests (Request / Part); read once to migrate. */
	public static final String KEY_CALLS = "threatinc_aidCalls";
	/** Persistent until 2026-10-29 evening (the one-request-a-world cooldown); cleared on the first tick. */
	public static final String KEY_ASKED = "threatinc_aidAskedAt";
	/** Persistent: the standing calls (2026-10-31). */
	public static final String KEY_STANDING = "threatinc_aidStanding";
	/** Persistent: the offers, open and accepted (2026-10-31). */
	public static final String KEY_OFFERS = "threatinc_aidOffers";

	/** A call's target kind: a colony of the player's, their outpost, or the hive world their landing attacks. */
	public static final String KIND_COLONY = "colony", KIND_OUTPOST = "outpost", KIND_FRONT = "front";

	/** One part of an older save's request: one good, or the task force ({@link ThreatNeeds#FLEETS}), at a lever's tier. Read only to migrate. */
	public static class Part {
		public String good;
		public int tier;
		public String factionId;
		public float amount;
		public String fromName;
		public String fromMarketId;
		public long acceptedAt;
		public float etaDays;
		public boolean delivered;
		public int price;
		public Map<String, String> reasons = new HashMap<String, String>();

		public boolean open() { return factionId == null; }
		public boolean atSea() { return factionId != null && !delivered; }
		public boolean isFleets() { return ThreatNeeds.FLEETS.equals(good); }
	}

	/** An older save's request (2026-10-23 to 2026-10-30). Read only to migrate. */
	public static class Request {
		public String id;
		public String marketId;
		public String purpose;
		public String good;
		public int tier;
		public int[] tiers;
		public boolean fleets;
		public int fleetTier;
		public boolean goodsDone;
		public boolean fleetsDone;
		public long since;
		public boolean noAnswerNotified;
		public float sent;
		public int senders;
		public List<Part> parts;
		public Map<String, Long> checkedAt;
		public boolean withdrawn;

		public List<Part> parts() {
			if (parts == null) parts = new ArrayList<Part>();
			return parts;
		}
	}

	/** A standing call: one Domain entry asking the sector for one good, from when it was toggled on. */
	public static class Call {
		/** The reserve key of the target (ThreatBases.Base.id: a market id or an outpost's station id). */
		public String targetId;
		/** {@link #KIND_COLONY}, {@link #KIND_OUTPOST} or {@link #KIND_FRONT}. */
		public String kind;
		/** A commodity id (ThreatReserves.AID_COMMODITIES). */
		public String good;
		public long since;
		/** The factions whose offer the player rejected or cancelled: they offer nothing more until the call is toggled again. */
		public List<String> declined;
		/** factionId -> when it last looked. */
		public Map<String, Long> checkedAt;

		public List<String> declined() {
			if (declined == null) declined = new ArrayList<String>();
			return declined;
		}

		public Map<String, Long> checkedAt() {
			if (checkedAt == null) checkedAt = new LinkedHashMap<String, Long>();
			return checkedAt;
		}
	}

	/** One faction's answer to a call: what it would send from where at what price; accepted, what sails. */
	public static class Offer {
		/** Unique; a convoy or order carries it (requestId) once accepted. */
		public String id;
		public String targetId;
		public String good;
		public String factionId;
		public String fromMarketId;
		public String fromName;
		/** Units offered; once accepted, what sails. */
		public float amount;
		/** Days the passage is estimated at ({@link #eta}), from acceptance. */
		public float etaDays;
		/** Standing points charged on receipt - a whole number. */
		public int price;
		public long offeredAt;
		/** 0 while the offer waits on the player. */
		public long acceptedAt;
		public boolean delivered;

		public boolean accepted() { return acceptedAt != 0L; }
		public boolean atSea() { return accepted() && !delivered; }
		public boolean isFleets() { return ThreatNeeds.FLEETS.equals(good); }
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Long> shortageSince() {
		Object val = Global.getSector().getPersistentData().get(KEY_SHORTAGE);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Long>();
			Global.getSector().getPersistentData().put(KEY_SHORTAGE, val);
		}
		return (Map<String, Long>) val;
	}

	/** An older save's requests, migrated and emptied on the first tick. */
	@SuppressWarnings("unchecked")
	public static List<Request> requests() {
		Object val = Global.getSector().getPersistentData().get(KEY_CALLS);
		if (!(val instanceof List)) {
			val = new ArrayList<Request>();
			Global.getSector().getPersistentData().put(KEY_CALLS, val);
		}
		return (List<Request>) val;
	}

	@SuppressWarnings("unchecked")
	public static List<Call> calls() {
		Object val = Global.getSector().getPersistentData().get(KEY_STANDING);
		if (!(val instanceof List)) {
			val = new ArrayList<Call>();
			Global.getSector().getPersistentData().put(KEY_STANDING, val);
		}
		return (List<Call>) val;
	}

	@SuppressWarnings("unchecked")
	public static List<Offer> offers() {
		Object val = Global.getSector().getPersistentData().get(KEY_OFFERS);
		if (!(val instanceof List)) {
			val = new ArrayList<Offer>();
			Global.getSector().getPersistentData().put(KEY_OFFERS, val);
		}
		return (List<Offer>) val;
	}

	protected static String key(MarketAPI market, String commodityId) {
		return market.getId() + ":" + commodityId;
	}

	// ------------------------------------------------------------------
	// the sector's asks of the player, and the answers (2026-10-31 evening)
	// ------------------------------------------------------------------

	/** Persistent: the asks NPC worlds make of the player (2026-10-31 evening). */
	public static final String KEY_ASKS = "threatinc_aidAsks";
	/** Persistent: the other factions' answers to them, at sea and lately landed. */
	public static final String KEY_ANSWERS = "threatinc_aidAnswers";

	/**
	 * An NPC world's standing ask of the player: raised by the coalition's loops every tick the need
	 * stands ({@link #sectorMayAnswer}), the player's alone for aidAskGraceDays from its first raise,
	 * then open to every friendly faction with the capacity. Gone three days after its last raise.
	 */
	public static class Ask {
		/** The market id. */
		public String targetId;
		/** A commodity id, or {@link ThreatNeeds#FLEETS}. */
		public String good;
		/** The purpose (ThreatNeeds.HOLD and the rest). */
		public String purpose;
		/** The need as the asker counts it, in the good's units (a fleets ask: fleet points); what the sector answers. */
		public float amount;
		public long since;
		public long seenAt;

		public String key() { return targetId + ":" + good; }
	}

	/** A faction's answer to an ask: what sailed from where, when it is due, and when it landed (0 at sea). */
	public static class Answer {
		/** Unique; the convoy or order carries it (requestId) so its landing is known. */
		public String id;
		public String targetId;
		public String good;
		public String purpose;
		public String factionId;
		public String fromMarketId;
		public String fromName;
		public float amount;
		public float etaDays;
		public long sentAt;
		public long deliveredAt;
		/** A relief with no fleet handle to follow: counted landed when its ETA passes. */
		public boolean untracked;

		public boolean atSea() { return deliveredAt == 0L; }
		public boolean isFleets() { return ThreatNeeds.FLEETS.equals(good); }
	}

	@SuppressWarnings("unchecked")
	public static List<Ask> asks() {
		Object val = Global.getSector().getPersistentData().get(KEY_ASKS);
		if (!(val instanceof List)) {
			val = new ArrayList<Ask>();
			Global.getSector().getPersistentData().put(KEY_ASKS, val);
		}
		return (List<Ask>) val;
	}

	@SuppressWarnings("unchecked")
	public static List<Answer> answers() {
		Object val = Global.getSector().getPersistentData().get(KEY_ANSWERS);
		if (!(val instanceof List)) {
			val = new ArrayList<Answer>();
			Global.getSector().getPersistentData().put(KEY_ANSWERS, val);
		}
		return (List<Answer>) val;
	}

	/** The world's standing ask of the good; null with none. */
	public static Ask askOf(String marketId, String good) {
		if (marketId == null || good == null) return null;
		for (Ask a : asks()) {
			if (marketId.equals(a.targetId) && good.equals(a.good)) return a;
		}
		return null;
	}

	/**
	 * The coalition raises an NPC world's need here before any faction answers it: the ask is dated on
	 * its first raise and kept while it is raised (seenAt), its amount refreshed. True once the player's
	 * grace has passed (aidAskGraceDays from the first raise), or at once while the player is not
	 * mobilised - nobody is being asked then.
	 */
	public static boolean sectorMayAnswer(MarketAPI target, String good, String purpose, float amount) {
		if (target == null || good == null) return true;
		long now = Global.getSector().getClock().getTimestamp();
		Ask a = askOf(target.getId(), good);
		if (a == null) {
			a = new Ask();
			a.targetId = target.getId();
			a.good = good;
			a.since = now;
			asks().add(a);
			ThreatIncConfig.log("Aid ask: " + target.getName() + " (" + target.getFactionId() + ") asks for " + (int) amount
					+ " " + good + " (" + purpose + "); the player's for " + (int) ThreatIncConfig.aidAskGraceDays() + " d");
		}
		a.purpose = purpose;
		a.amount = amount;
		a.seenAt = now;
		return graceLeft(a) <= 0f;
	}

	/** Days of the player's grace left on an ask; 0 once the sector may answer, and always while the player is not mobilised. */
	public static float graceLeft(Ask a) {
		if (a == null || !ThreatWarState.isAtWar(Factions.PLAYER)) return 0f;
		return Math.max(0f, ThreatIncConfig.aidAskGraceDays() - Global.getSector().getClock().getElapsedDaysSince(a.since));
	}

	/**
	 * A faction answered an ask: logged, and listed on the tab until aidAnswerShownDays after it lands.
	 * The caller puts the id on the convoy or order (requestId); {@code untracked} for a relief with no
	 * handle, counted landed when its ETA passes.
	 */
	public static Answer answered(MarketAPI target, String good, String purpose, FactionAPI helper, MarketAPI donor,
			float amount, boolean untracked) {
		Answer a = new Answer();
		long now = Global.getSector().getClock().getTimestamp();
		a.id = target.getId() + ":" + good + ":" + helper.getId() + ":" + now + ":" + answers().size();
		a.targetId = target.getId();
		a.good = good;
		a.purpose = purpose;
		a.factionId = helper.getId();
		a.fromMarketId = donor != null ? donor.getId() : null;
		a.fromName = donor != null ? donor.getName() : helper.getDisplayName();
		a.amount = amount;
		a.etaDays = donor != null ? eta(donor, target) : 1f;
		a.sentAt = now;
		a.untracked = untracked;
		answers().add(a);
		ThreatIncConfig.log("Aid answer: " + helper.getId() + " sends " + (int) amount + " " + good + " from " + a.fromName
				+ " to " + target.getName() + " (" + target.getFactionId() + ", " + purpose + "), ETA "
				+ (int) Math.ceil(a.etaDays) + " d");
		return a;
	}

	/** The answers to a world's ask of a good: at sea first, then the landed, newest first. */
	public static List<Answer> answersFor(String marketId, String good) {
		List<Answer> out = new ArrayList<Answer>();
		if (marketId == null || good == null) return out;
		for (Answer a : answers()) {
			if (marketId.equals(a.targetId) && good.equals(a.good) && a.atSea()) out.add(a);
		}
		List<Answer> landed = new ArrayList<Answer>();
		for (Answer a : answers()) {
			if (marketId.equals(a.targetId) && good.equals(a.good) && !a.atSea()) landed.add(a);
		}
		Collections.sort(landed, new Comparator<Answer>() {
			public int compare(Answer x, Answer y) {
				return y.deliveredAt < x.deliveredAt ? -1 : y.deliveredAt > x.deliveredAt ? 1 : 0;
			}
		});
		out.addAll(landed);
		return out;
	}

	/** Days until an answer is due, 0 once overdue or landed. */
	public static float etaLeft(Answer a) {
		if (a == null || !a.atSea()) return 0f;
		return Math.max(0f, a.etaDays - Global.getSector().getClock().getElapsedDaysSince(a.sentAt));
	}

	/** "1,200 supplies" / "a task force of 900 FP" - an answer's amount. */
	public static String answerText(Answer a) {
		if (a == null) return "";
		if (a.isFleets()) return "a task force of " + Misc.getWithDGS(Math.round(a.amount)) + " FP";
		return Misc.getWithDGS(ThreatHulls.shown(a.good, Math.round(a.amount))) + " " + ThreatReserves.label(a.good);
	}

	/** Each tick: an ask not raised for three days is gone; an answer lost at sea goes; a landed one leaves aidAnswerShownDays after landing. */
	protected static void tickAsks() {
		long now = Global.getSector().getClock().getTimestamp();
		for (Ask a : new ArrayList<Ask>(asks())) {
			if (Global.getSector().getClock().getElapsedDaysSince(a.seenAt) > 3f) asks().remove(a);
		}
		for (Answer a : new ArrayList<Answer>(answers())) {
			if (a.atSea()) {
				if (a.untracked) {
					if (Global.getSector().getClock().getElapsedDaysSince(a.sentAt) >= a.etaDays) a.deliveredAt = now;
					continue;
				}
				boolean found = false;
				if (a.isFleets()) {
					for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
						if (a.id.equals(o.requestId)) { found = true; break; }
					}
				} else {
					for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
						if (a.id.equals(c.requestId)) { found = true; break; }
					}
				}
				if (!found) {
					answers().remove(a);
					ThreatIncConfig.log("Aid answer lost: " + a.factionId + "'s " + (int) a.amount + " " + a.good + " for " + a.targetId);
				}
			} else if (Global.getSector().getClock().getElapsedDaysSince(a.deliveredAt) > ThreatIncConfig.aidAnswerShownDays()) {
				answers().remove(a);
			}
		}
	}

	/** The answer a convoy or order carries, still at sea; null with none. */
	protected static Answer answerAtSea(String requestId) {
		if (requestId == null) return null;
		for (Answer a : answers()) {
			if (a.atSea() && requestId.equals(a.id)) return a;
		}
		return null;
	}

	/** An answer landed (its convoy at the world, its guard on station): dated and logged. True when the id was an answer's. */
	public static boolean onAnswerLanded(String requestId) {
		Answer a = answerAtSea(requestId);
		if (a == null) return false;
		a.deliveredAt = Global.getSector().getClock().getTimestamp();
		ThreatIncConfig.log("Aid answer landed: " + a.factionId + "'s " + (int) a.amount + " " + a.good + " at " + a.targetId
				+ " after " + (int) Math.ceil(Global.getSector().getClock().getElapsedDaysSince(a.sentAt)) + " d");
		return true;
	}

	/** Slow tick (IncursionManager.tick): remember when shortages began. */
	public static void tickShortages() {
		if (!ThreatWarState.enabled()) return;
		updateShortages();
		Global.getSector().getPersistentData().remove(KEY_ASKED);
	}

	/**
	 * Every half day (IncursionManager.advance's interval): each faction's look at the standing calls
	 * once aidRequestCheckDays have passed since its last ({@link #check}), and what was lost at sea.
	 */
	public static void tick(Random random) {
		if (!ThreatWarState.enabled()) return;
		tickAsks();
		if (!ThreatIncConfig.aidRequestsEnabled()) return;
		migrateOld();
		for (Offer o : new ArrayList<Offer>(offers())) {
			if (o.atSea()) settleLost(o);
		}
		for (Call c : new ArrayList<Call>(calls())) {
			ThreatBases.Base target = resolveTarget(c.targetId);
			if (target == null || requestOffReason(target) != null) {
				// the world changed hands, the outpost fell, the front is gone: the call ends with it
				ThreatIncConfig.log("Aid call dropped for " + c.targetId + " (" + c.good + "): the target is gone");
				dropCall(c);
				continue;
			}
			check(c, target, random);
		}
	}

	/** Every mobilised colony (the player's included - allies watch those too): note deficits as they start and clear. */
	protected static void updateShortages() {
		Map<String, Long> since = shortageSince();
		long now = Global.getSector().getClock().getTimestamp();
		List<String> live = new ArrayList<String>();
		for (String factionId : ThreatWarState.warFactionIds()) {
			for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
				for (String c : ThreatReserves.AID_COMMODITIES) {
					ThreatReserves.CommodityStatus s = ThreatReserves.status(market, c);
					String k = key(market, c);
					if (s != null && s.localDeficit > 0) {
						live.add(k);
						if (!since.containsKey(k)) since.put(k, now);
					}
				}
			}
		}
		for (String k : new ArrayList<String>(since.keySet())) {
			if (!live.contains(k)) since.remove(k);
		}
	}

	// ------------------------------------------------------------------
	// the targets
	// ------------------------------------------------------------------

	/** A call's target by its reserve key: a colony or outpost (ThreatBases.of), else the hive world of a front. */
	public static ThreatBases.Base resolveTarget(String id) {
		if (id == null) return null;
		ThreatBases.Base b = ThreatBases.of(id);
		if (b != null) return b;
		return ThreatBases.of(ThreatIncData.resolveColonyMarket(id));
	}

	/** The kind of target: an outpost, the hive world of the player's landing, else a colony. */
	public static String kindOf(ThreatBases.Base target) {
		if (target == null) return KIND_COLONY;
		if (target.isOutpost()) return KIND_OUTPOST;
		if (target.market != null && ThreatGroundFronts.isHiveTarget(target.market)) return KIND_FRONT;
		return KIND_COLONY;
	}

	/** The player's front on the target, for a front call; null otherwise. */
	public static ThreatGroundFronts.GroundFront frontAt(ThreatBases.Base target) {
		if (target == null || target.market == null) return null;
		ThreatGroundFronts.GroundFront f = ThreatGroundFronts.getFront(target.market.getId());
		return f != null && f.isPlayerOwned() ? f : null;
	}

	/** Why the player cannot call for aid for this target now, or null when they can. */
	public static String requestOffReason(ThreatBases.Base target) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return "Requests for aid are disabled in the mod settings.";
		if (target == null) return "No target.";
		if (!ThreatWarState.isAtWar(Factions.PLAYER)) return "Your faction is not mobilised.";
		String kind = kindOf(target);
		if (KIND_FRONT.equals(kind)) {
			if (frontAt(target) == null) return "No landing of yours on " + target.name() + ".";
			return null;
		}
		if (!Factions.PLAYER.equals(target.factionId())) return "Not yours.";
		return null;
	}

	/** Why the good cannot be called for at this target (a front takes marines and arms; an outpost has no hull pool), or null. */
	public static String goodOffReason(ThreatBases.Base target, String good) {
		String kind = kindOf(target);
		if (ThreatNeeds.FLEETS.equals(good)) return null; // a defence fleet can be called over any of them (2026-10-31)
		if (KIND_FRONT.equals(kind) && !Commodities.MARINES.equals(good) && !Commodities.HAND_WEAPONS.equals(good)) {
			return "A front takes marines, heavy armaments and a defence fleet.";
		}
		if (KIND_OUTPOST.equals(kind) && Commodities.SHIPS.equals(good)) return "An outpost has no hull pool.";
		if (ThreatAid.index(good) < 0) return "Not a good a convoy carries.";
		return null;
	}

	// ------------------------------------------------------------------
	// the calls
	// ------------------------------------------------------------------

	/** The standing call for the target and good, or null. */
	public static Call callAt(String targetId, String good) {
		if (targetId == null || good == null) return null;
		for (Call c : calls()) if (targetId.equals(c.targetId) && good.equals(c.good)) return c;
		return null;
	}

	public static boolean isCalling(String targetId, String good) {
		return callAt(targetId, good) != null;
	}

	/** The standing calls, oldest first. */
	public static List<Call> open() {
		List<Call> out = new ArrayList<Call>(calls());
		Collections.sort(out, new Comparator<Call>() {
			public int compare(Call a, Call b) { return Long.compare(a.since, b.since); }
		});
		return out;
	}

	/**
	 * The player flips the call for the good at the target: on, the sector is asked (every candidate
	 * faction looks on its next day); off, the open offers under it are dropped - an accepted one
	 * still sails, with its Cancel. True when the call now stands.
	 */
	public static boolean toggle(ThreatBases.Base target, String good) {
		if (target == null || good == null) return false;
		Call c = callAt(target.id(), good);
		if (c != null) {
			dropCall(c);
			ThreatIncConfig.log("Aid call off for " + target.name() + ": " + good);
			ThreatNotice.titled("Call Withdrawn").icon(target.market != null ? target.market.getFaction()
					: Global.getSector().getPlayerFaction())
					.line("%s withdraws its call for %s", targetNotice(target), ThreatReserves.label(good)).send();
			return false;
		}
		if (requestOffReason(target) != null || goodOffReason(target, good) != null) return false;
		c = new Call();
		c.targetId = target.id();
		c.kind = kindOf(target);
		c.good = good;
		c.since = Global.getSector().getClock().getTimestamp();
		calls().add(c);
		ThreatIncConfig.log("Aid call on for " + target.name() + ": " + good);
		// an SOS (the user, 2026-10-29 night: "you dont know if anyone is coming"): the call, nothing of who answers
		ThreatNotice.titled("Distress Call").icon(target.market != null ? target.market.getFaction()
				: Global.getSector().getPlayerFaction())
				.line("%s calls for %s", targetNotice(target), ThreatReserves.label(good)).send();
		return true;
	}

	/** Every call goes (the Colony button moved on, 2026-10-31: "changing colony toggle resets all to off"); accepted offers sail on. */
	public static void clearCalls() {
		for (Call c : new ArrayList<Call>(calls())) dropCall(c);
	}

	/** The call and every open offer under it go; accepted offers sail on. */
	protected static void dropCall(Call c) {
		calls().remove(c);
		for (Offer o : new ArrayList<Offer>(offers())) {
			if (!o.accepted() && c.targetId.equals(o.targetId) && c.good.equals(o.good)) offers().remove(o);
		}
	}

	/** The target's name for a notice: its market in its owner's colour, or the outpost's name highlighted. */
	protected static Object targetNotice(ThreatBases.Base target) {
		if (target.market != null) return ThreatNotice.market(target.market);
		return ThreatNotice.hl(target.name());
	}

	// ------------------------------------------------------------------
	// the offers
	// ------------------------------------------------------------------

	/** The offers under a call, open and at sea, oldest first. */
	public static List<Offer> offersFor(Call c) {
		List<Offer> out = new ArrayList<Offer>();
		if (c == null) return out;
		for (Offer o : offers()) if (c.targetId.equals(o.targetId) && c.good.equals(o.good)) out.add(o);
		Collections.sort(out, new Comparator<Offer>() {
			public int compare(Offer a, Offer b) { return Long.compare(a.offeredAt, b.offeredAt); }
		});
		return out;
	}

	/** The offers at sea whose call was toggled off (still on the table, with Cancel), oldest first. */
	public static List<Offer> strays() {
		List<Offer> out = new ArrayList<Offer>();
		for (Offer o : offers()) {
			if (o.atSea() && callAt(o.targetId, o.good) == null) out.add(o);
		}
		Collections.sort(out, new Comparator<Offer>() {
			public int compare(Offer a, Offer b) { return Long.compare(a.offeredAt, b.offeredAt); }
		});
		return out;
	}

	public static Offer offerById(String id) {
		if (id == null) return null;
		for (Offer o : offers()) if (id.equals(o.id)) return o;
		return null;
	}

	/** The faction's offer under the call: the open one, or the one at sea (one at a time a faction). */
	protected static Offer offerBy(Call c, String factionId) {
		for (Offer o : offers()) {
			if (o.delivered) continue;
			if (c.targetId.equals(o.targetId) && c.good.equals(o.good) && factionId.equals(o.factionId)) return o;
		}
		return null;
	}

	/** Days between one faction's looks at a call (aidRequestCheckDays, at least half a day). */
	public static float checkDays() {
		return Math.max(0.5f, ThreatIncConfig.aidRequestCheckDays());
	}

	/**
	 * Days between THIS faction's looks: the check days, stretched by how beset it is (the user,
	 * 2026-10-31: "the speed in which a faction responds to offer should be determined by how preoccupied
	 * they are with the war") - x (1 + besetness x aidRequestBesetDelay): at 3, a faction with half its
	 * worlds under the Threat looks every 2.5 days instead of 1.
	 */
	public static float checkDays(FactionAPI helper) {
		return checkDays() * (1f + besetness(helper) * Math.max(0f, ThreatIncConfig.aidRequestBesetDelay()));
	}

	/** Days until the faction looks at the call again; 0 when it has never looked. */
	public static float nextCheckIn(Call c, FactionAPI helper) {
		if (c == null || helper == null) return 0f;
		Long last = c.checkedAt().get(helper.getId());
		if (last == null) return 0f;
		return Math.max(0f, checkDays(helper) - Global.getSector().getClock().getElapsedDaysSince(last));
	}

	/**
	 * Each candidate faction whose day has come looks at the call: one that declined it, or has an
	 * offer at sea under it, looks no further; else it quotes ({@link #quote}) - a new offer goes on
	 * the table with a notice, a standing one takes the fresh figures, and one it can no longer make
	 * is withdrawn.
	 */
	protected static void check(Call c, ThreatBases.Base target, Random random) {
		long now = Global.getSector().getClock().getTimestamp();
		for (FactionAPI helper : candidates()) {
			Long last = c.checkedAt().get(helper.getId());
			if (last != null && Global.getSector().getClock().getElapsedDaysSince(last) < checkDays(helper)) continue;
			c.checkedAt().put(helper.getId(), now);
			if (c.declined().contains(helper.getId())) continue;
			Offer have = offerBy(c, helper.getId());
			if (have != null && have.accepted()) continue;
			Quote q = quote(helper, c, target);
			if (q == null || !q.ok()) {
				if (have != null) {
					// the faction needs it itself now (the user, 2026-10-31: "if they suddenly need those marines they should recall the offer")
					offers().remove(have);
					ThreatIncConfig.log("Aid offer withdrawn: " + helper.getId() + " " + c.good + " for " + target.name()
							+ " - " + (q != null ? q.why : "no quote"));
					ThreatNotice.titled("Offer Withdrawn").icon(helper)
							.line("%s withdraws its offer of %s to %s", ThreatNotice.faction(helper), ThreatNotice.hl(amountText(have)),
									targetNotice(target))
							.send();
				} else if (q != null) {
					// the reason is the log's and debug mode's, never the player's (an SOS, 2026-10-29 night)
					ThreatIncConfig.log("Aid call: " + helper.getId() + " cannot offer " + c.good + " for "
							+ target.name() + " - " + q.why);
				}
				continue;
			}
			if (have == null) {
				have = new Offer();
				have.id = c.targetId + ":" + c.good + ":" + helper.getId() + ":" + now;
				have.targetId = c.targetId;
				have.good = c.good;
				have.factionId = helper.getId();
				have.offeredAt = now;
				offers().add(have);
				fill(have, q);
				ThreatIncConfig.log("Aid offered: " + helper.getId() + " " + (int) have.amount + " " + c.good + " from "
						+ have.fromName + " to " + target.name() + ", ETA " + (int) Math.ceil(have.etaDays)
						+ " d, standing -" + have.price);
				ThreatNotice.titled("Aid Offered").icon(helper)
						.line("%s offers %s from %s to %s", ThreatNotice.faction(helper), ThreatNotice.hl(amountText(have)),
								ThreatNotice.market(q.donor), targetNotice(target))
						.line("ETA %s days", ThreatNotice.hl("" + (int) Math.ceil(have.etaDays)))
						.line("Standing with %s %s on receipt", ThreatNotice.faction(helper), ThreatNotice.hl("-" + have.price))
						.send();
			} else {
				fill(have, q);
			}
		}
	}

	/** What a faction would send: the donor, the amount, the price and the passage. */
	protected static class Quote {
		MarketAPI donor;
		float amount;
		int price;
		float etaDays;
		String why;

		boolean ok() { return donor != null && amount > 0f; }
	}

	/**
	 * One faction's quote on a call: all that its colony sparing the most of the good in reach
	 * spares ({@link #donors}), at least {@link #minSpare}, cut to the standing the player can spend
	 * with it without dropping below Favourable; nothing while its besetness outweighs the player's
	 * standing ({@link #willingness}). {@code why} says why not.
	 */
	protected static Quote quote(FactionAPI helper, Call c, ThreatBases.Base target) {
		Quote q = new Quote();
		if (helper == null || c == null || target == null) {
			q.why = "no target";
			return q;
		}
		if (willingness(helper) <= 0f) {
			int[] bc = besetCount(helper);
			q.why = bc[0] + " of " + bc[1] + " worlds beset by the Threat, past your standing";
			return q;
		}
		if (goodOffReason(target, c.good) != null) {
			q.why = goodOffReason(target, c.good);
			return q;
		}
		if (ThreatNeeds.FLEETS.equals(c.good)) return quoteFleet(q, helper, target);
		List<MarketAPI> donors = donors(helper, target.hyperLoc(), c.good);
		if (donors.isEmpty()) {
			q.why = "no colony in reach holds a surplus of " + Misc.getWithDGS((int) minSpare(c.good)) + " or more";
			return q;
		}
		MarketAPI donor = donors.get(0);
		float amount = (float) Math.floor(offerSpare(donor, c.good));
		if (ThreatIncConfig.debugMode()) {
			StringBuilder sb = new StringBuilder();
			for (MarketAPI d : donors) sb.append(sb.length() > 0 ? ", " : "").append(d.getName()).append(" ").append((int) offerSpare(d, c.good));
			ThreatIncConfig.log("Aid quote donors for " + c.good + " (" + helper.getId() + "): " + sb);
		}
		if (amount < minSpare(c.good)) {
			q.why = "the " + Misc.getWithDGS(ThreatHulls.shown(c.good, amount)) + " " + donor.getName()
					+ " holds above its keep is under " + Misc.getWithDGS((int) minSpare(c.good));
			return q;
		}
		float budget = (float) Math.floor(budget(helper));
		int price = priceFor(target, c.good, amount);
		if (price > budget) {
			// the amount the standing buys: the price is linear in the amount (a cap only lowers it)
			amount = (float) Math.floor(amount * budget / price);
			price = priceFor(target, c.good, amount);
			while (amount >= minSpare(c.good) && price > budget) {
				amount = (float) Math.floor(amount * 0.95f);
				price = priceFor(target, c.good, amount);
			}
			if (amount < minSpare(c.good)) {
				q.why = "would take you below Favourable";
				return q;
			}
		}
		q.donor = donor;
		q.amount = amount;
		q.price = price;
		q.etaDays = eta(ThreatBases.of(donor), target);
		return q;
	}

	/**
	 * A faction's quote on a call for a DEFENCE FLEET (the user, 2026-10-31: "extra request toggle for
	 * fleet, which is a request for defence fleet"): a guard from its base in reach of the target
	 * (ThreatFleetOrders.pickBase) of the fleet points its navy has free (ThreatHulls.freeFP; with
	 * hulls off, what threatens the target, ThreatFleetOrders.guardNeed), no more than the base's depot
	 * provisions for the passage (sortiePayableFP), at least a fleet (ThreatDeploy.minPoints), cut to
	 * the standing budget by its price a fleet point (ThreatAid.standingFor: its FP as ship hulls at the
	 * target's price market, aidRepGuardArrived at least - flat whatever the size until 2026-10-31).
	 */
	protected static Quote quoteFleet(Quote q, FactionAPI helper, ThreatBases.Base target) {
		MarketAPI base = ThreatFleetOrders.pickBase(helper, target.hyperLoc());
		if (base == null) {
			q.why = "no base in reach";
			return q;
		}
		float spare = ThreatHulls.enabled() ? ThreatHulls.freeFP(helper.getId()) : ThreatFleetOrders.guardNeed(target.market);
		float payable = ThreatFleetOrders.sortiePayableFP(base, target.hyperLoc());
		float fp = (float) Math.floor(Math.min(spare, payable));
		float min = (float) Math.ceil(ThreatDeploy.minPoints());
		if (fp < min) {
			q.why = "the " + Misc.getWithDGS((int) spare) + " fleet points its navy has free (" + base.getName() + " provisions "
					+ Misc.getWithDGS((int) payable) + ") is under a fleet's " + Misc.getWithDGS((int) min);
			return q;
		}
		float budget = (float) Math.floor(budget(helper));
		// priced as the player's own guard earns; over the budget, the fleet shrinks to what the budget pays for
		int price = priceFor(target, ThreatNeeds.FLEETS, fp);
		if (price > budget) {
			fp = (float) Math.floor(fp * budget / price);
			if (fp < min) {
				q.why = "would take you below Favourable";
				return q;
			}
			price = priceFor(target, ThreatNeeds.FLEETS, fp);
		}
		q.donor = base;
		q.amount = fp;
		q.price = price;
		q.etaDays = eta(ThreatBases.of(base), target);
		return q;
	}

	protected static void fill(Offer o, Quote q) {
		o.fromMarketId = q.donor.getId();
		o.fromName = q.donor.getName();
		o.amount = q.amount;
		o.price = q.price;
		o.etaDays = q.etaDays;
	}

	/** The market whose prices value the goods: the target's, or the player's colony nearest an outpost or front. */
	protected static MarketAPI priceMarket(ThreatBases.Base target) {
		if (target == null) return null;
		if (target.market != null && target.market.isPlayerOwned()) return target.market;
		MarketAPI best = null;
		float bestLy = Float.MAX_VALUE;
		Vector2f at = target.hyperLoc();
		for (MarketAPI m : Misc.getPlayerMarkets(false)) {
			if (m.getStarSystem() == null) continue;
			float ly = at != null ? Misc.getDistanceLY(m.getLocationInHyperspace(), at) : 0f;
			if (ly < bestLy) {
				bestLy = ly;
				best = m;
			}
		}
		return best != null ? best : target.market;
	}

	/**
	 * The player accepts the offer: it is quoted afresh (the faction's spare and the player's
	 * standing move) and sails now - a convoy to a colony or outpost (ThreatConvoys.dispatch), a
	 * supply run to a front (ThreatConvoys.aidFrontRun) - carrying the offer's id; what was loaded is
	 * the amount, its price charged on receipt. Null on success, else why it could not (the offer is
	 * then withdrawn, with a notice).
	 */
	public static String accept(Offer o, Random random) {
		if (o == null || o.accepted()) return "Not an open offer.";
		ThreatBases.Base target = resolveTarget(o.targetId);
		FactionAPI helper = Global.getSector().getFaction(o.factionId);
		Call c = callAt(o.targetId, o.good);
		String why = null;
		if (target == null || helper == null) why = "the target or the sender is gone";
		else if (c == null) why = "the call was withdrawn";
		else {
			Quote q = quote(helper, c, target);
			if (!q.ok()) why = q.why;
			else {
				fill(o, q);
				int idx = ThreatAid.index(o.good);
				float carried = 0f;
				MarketAPI donor = q.donor;
				if (o.isFleets()) {
					// a defence fleet: the helper's guard of the quoted points, carrying the offer's id for
					// onGuardArrived (ThreatFleetOrders.poll); what sailed is the amount
					float before = ThreatFleetOrders.guardPointsFor(target.id());
					ThreatFleetOrders.Order ord = ThreatFleetOrders.dispatchGuardPoints(helper, target, donor, o.amount);
					if (ord == null) why = "no fleet could be raised at " + donor.getName();
					else {
						ord.requestId = o.id;
						carried = Math.max(0f, ThreatFleetOrders.guardPointsFor(target.id()) - before);
						if (carried <= 0f && ord.fleet != null) carried = ord.fleet.getFleetPoints();
						if (carried <= 0f) carried = o.amount;
					}
				} else if (KIND_FRONT.equals(kindOf(target))) {
					float[] load = { Commodities.MARINES.equals(o.good) ? o.amount : 0f,
							Commodities.HAND_WEAPONS.equals(o.good) ? o.amount : 0f };
					ThreatConvoys.Convoy run = ThreatConvoys.aidFrontRun(ThreatBases.of(donor), target.market, helper,
							load, random);
					if (run == null) why = "no supply run could be raised at " + donor.getName();
					else {
						run.requestId = o.id;
						float[] sent = ThreatConvoys.carried(run);
						carried = Commodities.MARINES.equals(o.good) ? sent[0] : sent[1];
					}
				} else {
					float[] load = new float[ThreatReserves.AID_COMMODITIES.length];
					load[idx] = o.amount;
					ThreatConvoys.Convoy conv = ThreatConvoys.dispatch(ThreatBases.of(donor), target, helper, load, random,
							Factions.PLAYER, false);
					if (conv == null) why = "no convoy could be raised at " + donor.getName();
					else {
						conv.requestId = o.id;
						carried = Commodities.SHIPS.equals(o.good) ? conv.ships : ThreatConvoys.carried(conv)[idx];
					}
				}
				if (why == null && carried < 1f) why = "nothing could be loaded at " + donor.getName();
				if (why == null) {
					o.amount = carried;
					o.price = priceFor(target, o.good, carried);
					o.acceptedAt = Global.getSector().getClock().getTimestamp();
					ThreatIncConfig.log("Aid accepted: " + helper.getId() + " sends " + (int) carried + " " + o.good + " from "
							+ donor.getName() + " to " + target.name() + ", ETA " + (int) Math.ceil(o.etaDays)
							+ " d, standing -" + o.price + " on receipt");
					ThreatNotice.titled("Aid Sails").icon(helper)
							.line("%s sends %s from %s to %s", ThreatNotice.faction(helper), ThreatNotice.hl(amountText(o)),
									ThreatNotice.market(donor), targetNotice(target))
							.line("ETA %s days", ThreatNotice.hl("" + (int) Math.ceil(o.etaDays)))
							.line("Standing with %s %s on receipt", ThreatNotice.faction(helper), ThreatNotice.hl("-" + o.price))
							.send();
					return null;
				}
			}
		}
		offers().remove(o);
		ThreatIncConfig.log("Aid offer lapsed: " + o.factionId + " " + o.good + " for " + o.targetId + " - " + why);
		ThreatNotice n = ThreatNotice.titled("Offer Lapsed").icon(helper != null ? helper : Global.getSector().getPlayerFaction());
		if (helper != null && target != null) {
			n.line("%s can no longer send %s to %s", ThreatNotice.faction(helper), ThreatReserves.label(o.good),
					targetNotice(target));
		} else {
			n.line("The offer of %s is gone", ThreatReserves.label(o.good));
		}
		n.send();
		return why;
	}

	/** The player rejects the offer: it goes, and the faction offers nothing more on the call until it is toggled again. */
	public static boolean reject(Offer o) {
		if (o == null || o.accepted()) return false;
		offers().remove(o);
		Call c = callAt(o.targetId, o.good);
		if (c != null && !c.declined().contains(o.factionId)) c.declined().add(o.factionId);
		ThreatIncConfig.log("Aid offer rejected: " + o.factionId + " " + (int) o.amount + " " + o.good + " for " + o.targetId);
		return true;
	}

	/** The player cancels an accepted offer at sea: the convoy turns home, nothing is charged, the faction offers nothing more on the call until it is toggled again. */
	public static boolean cancel(Offer o) {
		if (o == null || !o.atSea()) return false;
		FactionAPI helper = Global.getSector().getFaction(o.factionId);
		ThreatConvoys.Convoy conv = convoyFor(o);
		ThreatFleetOrders.Order ord = conv == null ? orderFor(o) : null;
		if (conv != null) ThreatConvoys.returnHome(conv);
		if (ord != null) ThreatFleetOrders.recall(ord);
		offers().remove(o);
		Call c = callAt(o.targetId, o.good);
		if (c != null && !c.declined().contains(o.factionId)) c.declined().add(o.factionId);
		ThreatIncConfig.log("Aid cancelled: " + o.factionId + " " + (int) o.amount + " " + o.good + " for " + o.targetId
				+ (conv == null && ord == null ? " (nothing at sea)" : ""));
		ThreatBases.Base target = resolveTarget(o.targetId);
		ThreatNotice n = ThreatNotice.titled("Aid Cancelled").icon(helper != null ? helper : Global.getSector().getPlayerFaction());
		if (helper != null) {
			n.line("%s's %s for %s turns home", ThreatNotice.faction(helper), ThreatNotice.hl(amountText(o)),
					target != null ? targetNotice(target) : ThreatNotice.hl(o.targetId));
		}
		n.send();
		return true;
	}

	/** As below, between two colonies. */
	public static float eta(MarketAPI from, MarketAPI to) {
		return eta(ThreatBases.of(from), ThreatBases.of(to));
	}

	/** Days a convoy from one base to another is estimated at: the passage at the board's pace (ThreatReach.days) and a day in-system. */
	public static float eta(ThreatBases.Base from, ThreatBases.Base to) {
		if (from == null || to == null || from.hyperLoc() == null || to.hyperLoc() == null) return 1f;
		float ly = Misc.getDistanceLY(from.hyperLoc(), to.hyperLoc());
		return ThreatReach.days(ly) + 1f;
	}

	/** Days until an accepted offer is due, 0 once overdue. */
	public static float etaLeft(Offer o) {
		if (o == null || !o.accepted()) return 0f;
		return Math.max(0f, o.etaDays - Global.getSector().getClock().getElapsedDaysSince(o.acceptedAt));
	}

	/** "defence fleet" for the fleets call, else the good's label (never "fleet points" - the user, 2026-10-31). */
	public static String goodLabel(String good) {
		return ThreatNeeds.FLEETS.equals(good) ? "defence fleet" : ThreatReserves.label(good);
	}

	/** "320 marines" / "a defence fleet of 900 FP" - the offer's amount. */
	public static String amountText(Offer o) {
		if (o == null) return "";
		if (o.isFleets()) return "a defence fleet of " + Misc.getWithDGS(Math.round(o.amount)) + " FP";
		return Misc.getWithDGS(ThreatHulls.shown(o.good, o.amount)) + " " + ThreatReserves.label(o.good);
	}

	/** "Hegemony: 1,568 marines from Ilm" - an offer on the table. */
	public static String offerText(Offer o) {
		if (o == null) return "";
		return ThreatWarState.displayName(o.factionId) + ": " + amountText(o) + " from " + o.fromName;
	}

	// ------------------------------------------------------------------
	// receipt, loss, the price
	// ------------------------------------------------------------------

	/** An answering faction's convoy landed at the player's base (a colony, an outpost, or a front's hive world): the offer is delivered and its price charged. True when the convoy was one. */
	public static boolean onConvoyLanded(ThreatConvoys.Convoy c, ThreatBases.Base base) {
		if (c == null || c.requestId == null) return false;
		// a faction's answer to an NPC world's ask (2026-10-31 evening): dated, the ally's word follows
		if (onAnswerLanded(c.requestId)) return false;
		Offer o = offerAtSea(c.requestId, c.factionId, goodOf(c));
		if (o == null) return false;
		deliver(o, base);
		return true;
	}

	/** An answering faction's guard reached the player's colony (an older save's task force): the offer is delivered and its price charged. */
	public static void onGuardArrived(ThreatFleetOrders.Order o) {
		if (o == null || o.requestId == null) return;
		if (onAnswerLanded(o.requestId)) return;
		Offer offer = offerAtSea(o.requestId, o.factionId, ThreatNeeds.FLEETS);
		if (offer == null) return;
		deliver(offer, resolveTarget(offer.targetId));
	}

	/** The good a convoy carries: the one aboard. */
	protected static String goodOf(ThreatConvoys.Convoy c) {
		if (c.ships > 0f) return Commodities.SHIPS;
		if (c.marines > 0f) return Commodities.MARINES;
		if (c.armaments > 0f) return Commodities.HAND_WEAPONS;
		if (c.fuel > 0f) return Commodities.FUEL;
		if (c.supplies > 0f) return Commodities.SUPPLIES;
		return null;
	}

	/** The offer at sea a convoy or order answers: by its id, or (an older save's request id) by the sender and the good. */
	protected static Offer offerAtSea(String requestId, String factionId, String good) {
		for (Offer o : offers()) {
			if (!o.atSea() || !requestId.equals(o.id)) continue;
			if (o.factionId.equals(factionId) && (good == null || good.equals(o.good))) return o;
		}
		for (Offer o : offers()) {
			if (o.atSea() && requestId.equals(o.id) && o.factionId.equals(factionId)) return o;
		}
		return null;
	}

	/** The offer landed: its price is charged, the notice sent, and it leaves the table. */
	protected static void deliver(Offer o, ThreatBases.Base base) {
		o.delivered = true;
		offers().remove(o);
		FactionAPI helper = Global.getSector().getFaction(o.factionId);
		charge(helper, o.price);
		ThreatIncConfig.log("Aid delivered: " + o.factionId + " " + (int) o.amount + " " + o.good + " at "
				+ (base != null ? base.name() : o.targetId) + ", standing -" + o.price);
		ThreatNotice n = ThreatNotice.titled("Aid Landed").icon(helper);
		if (base != null) {
			n.line("%s from %s at %s", ThreatNotice.hl(amountText(o)), ThreatNotice.faction(helper), targetNotice(base));
		} else {
			n.line("%s from %s", ThreatNotice.hl(amountText(o)), ThreatNotice.faction(helper));
		}
		if (o.price > 0) n.line("Standing with %s %s", ThreatNotice.faction(helper), ThreatNotice.hl("-" + o.price));
		n.send();
	}

	/**
	 * Standing charged for an offer received: a whole number of points off the relationship, never
	 * below Favourable (a player already under it loses nothing more), set straight on the faction
	 * so vanilla shows no message and plays no sound (the user, 2026-10-29: "whole number amount
	 * only! and remove the decrement faction rep sound").
	 */
	public static void charge(FactionAPI helper, int price) {
		if (helper == null || price <= 0 || helper.isPlayerFaction()) return;
		float floor = RepLevel.FAVORABLE.getMin();
		float before = helper.getRelToPlayer().getRel();
		if (before < floor) return;
		float after = Math.max(floor, before - price * 0.01f);
		// on the hundredth the relationship screen rounds to, so the drop reads as the whole number
		after = Math.round(after * 100f) / 100f;
		helper.setRelationship(Factions.PLAYER, after);
	}

	/** An accepted offer whose convoy or fleet is no longer at sea and never landed: nothing is charged, the offer goes; the faction may offer again. */
	protected static void settleLost(Offer o) {
		boolean found = o.isFleets() ? orderFor(o) != null : convoyFor(o) != null;
		if (found) return;
		offers().remove(o);
		FactionAPI helper = Global.getSector().getFaction(o.factionId);
		ThreatBases.Base target = resolveTarget(o.targetId);
		ThreatIncConfig.log("Aid lost: " + o.factionId + " " + (int) o.amount + " " + o.good + " for " + o.targetId);
		ThreatNotice n = ThreatNotice.titled("Aid Lost").bad()
				.icon(target != null && target.market != null ? target.market.getFaction() : Global.getSector().getPlayerFaction());
		n.line("%s's %s for %s was lost on the way", ThreatNotice.faction(helper), ThreatNotice.hl(amountText(o)),
				target != null ? targetNotice(target) : ThreatNotice.hl(o.targetId));
		n.send();
	}

	protected static ThreatConvoys.Convoy convoyFor(Offer o) {
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (o.id.equals(c.requestId) && o.factionId.equals(c.factionId)) return c;
		}
		return null;
	}

	protected static ThreatFleetOrders.Order orderFor(Offer o) {
		for (ThreatFleetOrders.Order ord : ThreatFleetOrders.all()) {
			if (o.id.equals(ord.requestId) && o.factionId.equals(ord.factionId)) return ord;
		}
		return null;
	}

	/**
	 * The whole-number standing an amount of a good costs the player when a faction sends it to the
	 * target: ThreatAid.standingFor at the target's price market - the figure the player would EARN
	 * sending the same (the user, 2026-10-31: "parity on rep for sending and requesting") - at
	 * least 1 for anything sent.
	 */
	protected static int priceFor(ThreatBases.Base target, String good, float amount) {
		if (amount <= 0f) return 0;
		return Math.max(1, Math.round(ThreatAid.standingFor(priceMarket(target), good, amount)));
	}

	/**
	 * An older save's requests (2026-10-23 to 2026-10-30) become calls and offers: an open part a
	 * standing call on its world, a part at sea an accepted offer carrying the request's id (its
	 * convoy or fleet carries the same), a delivered one nothing. The old list is emptied.
	 */
	protected static void migrateOld() {
		List<Request> old = requests();
		if (old.isEmpty()) return;
		long now = Global.getSector().getClock().getTimestamp();
		for (Request r : new ArrayList<Request>(old)) {
			migrate(r);
			for (Part p : r.parts()) {
				if (p.delivered || p.isFleets() && p.open()) continue;
				if (p.open()) {
					if (!r.withdrawn && callAt(r.marketId, p.good) == null) {
						Call c = new Call();
						c.targetId = r.marketId;
						c.kind = KIND_COLONY;
						c.good = p.good;
						c.since = r.since;
						calls().add(c);
					}
					continue;
				}
				Offer o = new Offer();
				o.id = r.id;
				o.targetId = r.marketId;
				o.good = p.good;
				o.factionId = p.factionId;
				o.fromMarketId = p.fromMarketId;
				o.fromName = p.fromName;
				o.amount = p.amount;
				o.etaDays = p.etaDays;
				o.price = p.price;
				o.offeredAt = p.acceptedAt != 0L ? p.acceptedAt : now;
				o.acceptedAt = p.acceptedAt != 0L ? p.acceptedAt : now;
				offers().add(o);
			}
		}
		ThreatIncConfig.log("Aid requests migrated: " + old.size() + " request(s) -> " + calls().size() + " call(s), "
				+ offers().size() + " offer(s)");
		old.clear();
	}

	// ------------------------------------------------------------------
	// candidates, prices, standing
	// ------------------------------------------------------------------

	/** Standing points the player can spend with the faction before dropping below Favourable. */
	public static float budget(FactionAPI helper) {
		if (helper == null) return 0f;
		return Math.max(0f, (helper.getRelToPlayer().getRel() - RepLevel.FAVORABLE.getMin()) / 0.01f);
	}

	/** War factions at Favourable or better with the player, not hostile, best standing first. */
	public static List<FactionAPI> candidates() {
		List<FactionAPI> out = new ArrayList<FactionAPI>();
		FactionAPI player = Global.getSector().getPlayerFaction();
		for (String id : ThreatWarState.warFactionIds()) {
			if (Factions.PLAYER.equals(id) || ThreatWarState.excluded(id)) continue;
			FactionAPI f = Global.getSector().getFaction(id);
			if (f == null || f.isPlayerFaction()) continue;
			if (f.isHostileTo(player) || player.isHostileTo(f)) continue;
			if (!f.getRelToPlayer().isAtWorst(RepLevel.FAVORABLE)) continue;
			out.add(f);
		}
		Collections.sort(out, new Comparator<FactionAPI>() {
			public int compare(FactionAPI a, FactionAPI b) {
				return Float.compare(b.getRelToPlayer().getRel(), a.getRelToPlayer().getRel());
			}
		});
		return out;
	}

	/** {beset, all}: the faction's worlds under a Threat army, Threat warships over the orbit (ThreatBlockade.penalty) or strikes bound for them (ThreatFrontlines.strikesWeight), over all its worlds. */
	public static int[] besetCount(FactionAPI helper) {
		int all = 0, beset = 0;
		if (helper == null) return new int[] { 0, 0 };
		for (MarketAPI m : ThreatReserves.marketsOf(helper.getId())) {
			all++;
			if (underArmy(m) || ThreatBlockade.penalty(m) > 0f || ThreatFrontlines.strikesWeight(m) > 0f) beset++;
		}
		return new int[] { beset, all };
	}

	/** The share of the faction's worlds the Threat besets, 0..1 ({@link #besetCount}). */
	public static float besetness(FactionAPI helper) {
		int[] c = besetCount(helper);
		return c[1] > 0 ? c[0] / (float) c[1] : 0f;
	}

	/** The player's standing with the faction from Favourable's floor (0) to Cooperative's (1). */
	public static float standing(FactionAPI helper) {
		if (helper == null) return 0f;
		float lo = RepLevel.FAVORABLE.getMin(), hi = RepLevel.COOPERATIVE.getMin();
		return Math.max(0f, Math.min(1f, (helper.getRelToPlayer().getRel() - lo) / Math.max(0.01f, hi - lo)));
	}

	/**
	 * Whether a faction answers at all (the user, 2026-10-29: "really needs to depend on factions
	 * current level of besetness by the threat cross referenced with your overall standing"): 1
	 * while its besetness x aidRequestBesetWeight is under the player's standing with it, else 0 -
	 * a faction with half its worlds under the Threat answers only a player half way from
	 * Favourable to Cooperative. What it sends is its capacity ({@link #tryAccept}), not a share.
	 */
	public static float willingness(FactionAPI helper) {
		float beset = Math.max(0f, Math.min(1f, besetness(helper) * ThreatIncConfig.aidRequestBesetWeight()));
		if (beset <= 0f) return 1f;
		return standing(helper) < beset ? 0f : 1f;
	}

	/** "Hegemony: 2 of 7 worlds beset, standing 48 of 100 - answers, 12 standing to spend" - a faction's line on the board. */
	public static String standLine(FactionAPI helper) {
		int[] c = besetCount(helper);
		return ThreatWarState.displayName(helper.getId()) + ": " + c[0] + " of " + c[1] + " worlds beset, standing "
				+ Math.round(standing(helper) * 100f) + " of 100 - "
				+ (willingness(helper) <= 0f ? "answers nothing"
						: "answers, " + (int) Math.floor(budget(helper)) + " standing to spend");
	}

	/** The least a colony must spare of a good to answer a request with it: aidRequestMinSpare, one unit for ship hulls. */
	public static float minSpare(String good) {
		if (Commodities.SHIPS.equals(good)) return 1f;
		return Math.max(0f, ThreatIncConfig.aidRequestMinSpare());
	}

	/**
	 * The helper's colonies in reach of the target holding a SURPLUS of the good (the user, 2026-10-31:
	 * "factions should only make offers from surplus colonies ... if they are short on the requested
	 * commodity they dont respond at all"): its reserve of the good in the white state - no deficit, no
	 * shortage cover, not exhausted - and offerSpare of minSpare or more; the most
	 * spare first. An NPC helper reaches as far as its colony's fuel pays (ThreatConvoys.stockReachLY).
	 */
	protected static List<MarketAPI> donors(FactionAPI helper, Vector2f at, String good) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (helper == null || at == null) return out;
		final String g = good;
		for (MarketAPI donor : ThreatReserves.marketsOf(helper.getId())) {
			if (donor.getStarSystem() == null || donor.getPrimaryEntity() == null) continue;
			if (ThreatConvoys.sealed(donor)) continue; // the door shuts both ways (2026-10-31)
			if (Misc.getDistanceLY(donor.getLocationInHyperspace(), at) > ThreatConvoys.stockReachLY(donor)) continue;
			if (!holdsSurplus(donor, good)) continue;
			if (offerSpare(donor, good) >= minSpare(good)) out.add(donor);
		}
		Collections.sort(out, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(offerSpare(b, g), offerSpare(a, g));
			}
		});
		return out;
	}

	/** Whether the colony's reserve of the good reads as a surplus - the white state of the reserve key (no deficit, no cover, not exhausted); hulls always. */
	public static boolean holdsSurplus(MarketAPI donor, String good) {
		if (Commodities.SHIPS.equals(good)) return true;
		ThreatReserves.CommodityStatus st = ThreatReserves.status(donor, good);
		if (st == null) return ThreatReserves.stock(donor.getId(), good) > 0f;
		return !st.exhausted && !st.stockpilesOff && !st.covering && st.deficit <= 0;
	}

	/**
	 * What a colony can OFFER of a good: its stock above its own keep (ThreatReserves.monthsBasis x
	 * donorKeepFraction, never under the garrison's floor; a forward base's feeding credit for
	 * supplies; a world under a front what ThreatConvoys.frontWorldSpare allows; hulls the port's
	 * spare units). The siege it may be staging for is NOT held back (ThreatConvoys.spare holds the
	 * staging bank and target, which left Ilm with 4,721 marines offering 216 from Ailmar - the user,
	 * 2026-10-31: "why on earth would they send from a colony with no marines when they have one with
	 * 5k"): an offer the player accepts is the faction's choice to spend it.
	 */
	public static float offerSpare(MarketAPI donor, String good) {
		if (donor == null || good == null) return 0f;
		if (Commodities.SHIPS.equals(good)) return ThreatHulls.spareUnits(donor);
		if (ThreatGroundFronts.hasFront(donor)) return ThreatConvoys.frontWorldSpare(donor, good);
		float keep = ThreatReserves.monthsBasis(donor, good) * ThreatIncConfig.donorKeepFraction();
		ThreatReserves.CommodityStatus st = ThreatReserves.status(donor, good);
		if (st != null) keep = Math.max(keep, st.floor);
		if (Commodities.SUPPLIES.equals(good)) keep += ThreatFeeding.credit(donor);
		return Math.max(0f, ThreatReserves.stock(donor.getId(), good) - keep);
	}

	/** An older save's request read as parts: one good at a tier (2026-10-23), or levers and a Fleet lever (2026-10-28 / 29). */
	protected static void migrate(Request r) {
		if (r == null) return;
		if (r.id == null) r.id = r.marketId + ":" + r.since;
		if (r.parts != null) return;
		r.parts = new ArrayList<Part>();
		int[] tiers = r.tiers;
		boolean fleets = r.fleets;
		if (tiers == null) {
			tiers = new int[ThreatReserves.AID_COMMODITIES.length];
			if (ThreatNeeds.FLEETS.equals(r.good)) {
				fleets = true;
			} else {
				int idx = ThreatAid.index(r.good);
				if (idx >= 0) tiers[idx] = Math.max(1, Math.min(2, r.tier));
			}
		}
		for (int i = 0; i < tiers.length && i < ThreatReserves.AID_COMMODITIES.length; i++) {
			if (tiers[i] <= 0) continue;
			Part p = new Part();
			p.good = ThreatReserves.AID_COMMODITIES[i];
			p.tier = Math.max(1, Math.min(2, tiers[i]));
			r.parts.add(p);
		}
		if (fleets) {
			Part p = new Part();
			p.good = ThreatNeeds.FLEETS;
			p.tier = Math.max(1, Math.min(2, r.fleetTier));
			r.parts.add(p);
		}
	}

	protected static ThreatNotice.Reason reason(ThreatNotice.Reason why, FactionAPI helper, String what) {
		if (why == null) return ThreatNotice.Reason.of("%s: " + what, ThreatNotice.faction(helper));
		return why.line("%s: " + what, ThreatNotice.faction(helper));
	}

	// supply needs
	// ------------------------------------------------------------------

	/** Short of peacetime demand with the depot unable to cover it: exhausted now, or uncovered for missionAidShortageDays. */
	public static boolean shortageStanding(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null) return false;
		if (s.exhausted) return true;
		if (s.localDeficit <= 0 || s.covering) return false;
		Long since = shortageSince().get(key(market, commodityId));
		if (since == null) return false;
		return Global.getSector().getClock().getElapsedDaysSince(since)
				>= ThreatIncConfig.missionAidShortageDays();
	}

	/** What a depot need asks for: the peacetime gap in items. The War footing's share is not asked for. */
	public static int requestItems(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null || s.localDeficit <= 0 || s.econUnit <= 0f) return 0;
		return Math.max((int) s.econUnit, Math.round(s.localDeficit * s.econUnit));
	}

	/** Whether a Threat army stands on the colony: its needs are then the battle's (marines, armaments) before the depot's. */
	public static boolean underArmy(MarketAPI market) {
		return market != null && ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()));
	}

	/** The deficit in items: vanilla's units short times the commodity's econ unit. */
	public static int needItems(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null || s.deficit <= 0 || s.econUnit <= 0f) return 0;
		return Math.max((int) s.econUnit, Math.round(s.deficit * s.econUnit));
	}

	/** The commodity the colony is shortest of, in items - the largest deficit. Null when nothing is short. */
	public static String worstShortage(MarketAPI market) {
		if (market == null) return null;
		String best = null;
		int bestNeed = 0;
		for (String c : ThreatReserves.AID_COMMODITIES) {
			int need = needItems(market, c);
			if (need > bestNeed) {
				bestNeed = need;
				best = c;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// defence needs
	// ------------------------------------------------------------------

	/** Live Threat strikes with this colony among their targets. */
	public static List<GenericRaidFGI> strikesAgainst(MarketAPI market) {
		List<GenericRaidFGI> result = new ArrayList<GenericRaidFGI>();
		if (market == null) return result;
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI fgi = (GenericRaidFGI) curr;
			if (fgi.isEnded() || fgi.isEnding()) continue;
			// nobody asks for help against a strike nobody has seen (docs/frontlines.md)
			if (fgi.isHidden()) continue;
			if (ThreatFrontlines.boundFor(fgi, market)) result.add(fgi);
		}
		return result;
	}

	/** Whether the strike is in the colony's system: real fleets there, or its payload action running in route mode. */
	public static boolean strikeAtTarget(GenericRaidFGI fgi, MarketAPI market) {
		if (fgi == null || market == null || market.getContainingLocation() == null) return false;
		if (fgi.isSpawnedFleets()) {
			for (CampaignFleetAPI fleet : fgi.getFleets()) {
				if (fleet == null || !fleet.isAlive()) continue;
				if (fleet.getContainingLocation() == market.getContainingLocation()) return true;
			}
			return false;
		}
		FGAction action = fgi.getCurrentAction();
		return action != null && GenericRaidFGI.PAYLOAD_ACTION.equals(action.getId());
	}

	/** Fleet points of the strike: the real fleets when spawned, else its planned sizes. */
	public static float strikeStrength(GenericRaidFGI fgi) {
		float sum = 0f;
		if (fgi.isSpawnedFleets()) {
			for (CampaignFleetAPI fleet : fgi.getFleets()) {
				if (fleet != null && fleet.isAlive()) sum += fleet.getFleetPoints();
			}
		}
		if (sum <= 0f && fgi.getParams() != null) {
			for (Integer size : fgi.getParams().fleetSizes) {
				sum += size * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
			}
		}
		return sum;
	}

	/** The faction's strength in the colony's system plus its station. */
	public static float defenderStrength(MarketAPI market) {
		StarSystemAPI system = market != null ? market.getStarSystem() : null;
		FactionAPI faction = market != null ? market.getFaction() : null;
		if (system == null || faction == null) return 0f;
		float strength = WarSimScript.getFactionStrength(faction, system);
		if (market.getPrimaryEntity() != null) {
			strength += WarSimScript.getStationStrength(faction, system, market.getPrimaryEntity());
		}
		return strength;
	}

	/** A strike is coming and the defenders are outmatched (defendRequestRatio). */
	public static boolean needsDefence(MarketAPI market) {
		List<GenericRaidFGI> strikes = strikesAgainst(market);
		if (strikes.isEmpty()) return false;
		float defence = defenderStrength(market) * ThreatIncConfig.defendRequestRatio();
		for (GenericRaidFGI s : strikes) {
			if (strikeStrength(s) > defence) return true;
		}
		return false;
	}
}
