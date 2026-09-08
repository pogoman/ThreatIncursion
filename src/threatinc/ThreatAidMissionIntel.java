package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.ReputationActionResponsePlugin.ReputationAdjustmentResult;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.MissionCompletionRep;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepRewards;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.BaseMissionIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * A faction's REQUEST FOR HELP at one of its colonies (docs/player-aid.md
 * section 4), posted in its name and answered by the player either in person
 * or with a fleet sent from a player colony via the war board. An ordinary
 * vanilla mission: accepted from the intel screen, withdrawn unaccepted after
 * missionPostingDays, and once accepted FAILED (standing lost, as an abandoned
 * contract) if not completed within its term or if the colony is lost.
 *
 * <ul>
 * <li><b>Defend</b> - a window of missionDefendDays. Completes when the term
 * ends with no Threat ground force landed on the colony and a player asset
 * (the player's fleet, or a guard sent from a player colony) in the system
 * when each strike arrived. Fails the moment the swarm puts troops on the
 * ground, or a saturation pass hits ({@link #strikeLanded}, from
 * ThreatGroundFronts.landOrReinforce and the saturation raid action). A pass
 * that only bombards tactically, or is turned back from a held orbit, does not
 * fail it - holding the orbit is the contract working. If no strike came at all, or the faction
 * beat one with no player asset present, the contract ends unpaid and
 * without penalty - nothing was owed.</li>
 * <li><b>Aid</b> - deliver N of a commodity within missionDurationDays, by any
 * mix of convoy landings and hand-overs at the colony's station commander;
 * the running total completes it.</li>
 * </ul>
 *
 * Sending board aid to a colony with an open request accepts it.
 */
public class ThreatAidMissionIntel extends BaseMissionIntel {

	public static final int KIND_DEFEND = 0;
	public static final int KIND_AID = 1;

	/** Market memory, refreshed while a request is open: the station commander offers the hand-over. */
	public static final String MEM_REQUEST = "$threatinc_aidRequest";

	protected int kind;
	protected String marketId;
	protected String factionId;
	protected String commodityId;
	protected int needed;
	protected int delivered;
	protected int reward;
	protected long postedTimestamp;
	/** Why it ended without pay: "expired", "neutralized", "quiet", "alone". */
	protected String cancelReason;
	/** Strikes seen in the colony's system during the term. */
	protected List<Object> engaged = new ArrayList<Object>();
	/** Of those, the ones a player asset was present for. */
	protected List<Object> covered = new ArrayList<Object>();
	protected int stopped;
	protected IntervalUtil watch = new IntervalUtil(0.2f, 0.3f);

	protected ThreatAidMissionIntel(int kind, MarketAPI market, String commodityId, int needed) {
		this.kind = kind;
		this.marketId = market.getId();
		this.factionId = market.getFactionId();
		this.commodityId = commodityId;
		this.needed = needed;
		this.postedTimestamp = Global.getSector().getClock().getTimestamp();
		if (kind == KIND_DEFEND) {
			reward = Math.round(ThreatIncConfig.missionDefendCredits() * market.getSize() / 1000f) * 1000;
			setDuration(ThreatIncConfig.missionDefendDays());
		} else {
			float value = ThreatAid.valueAt(market, commodityId, needed);
			reward = Math.max(1000, Math.round(value * ThreatIncConfig.missionAidPayMult() / 1000f) * 1000);
			setDuration(ThreatIncConfig.missionDurationDays());
		}
	}

	// ------------------------------------------------------------------
	// posting and lookup
	// ------------------------------------------------------------------

	public static ThreatAidMissionIntel postDefend(MarketAPI market) {
		if (market == null) return null;
		ThreatAidMissionIntel m = new ThreatAidMissionIntel(KIND_DEFEND, market, null, 0);
		Global.getSector().getIntelManager().queueIntel(m);
		ThreatIncConfig.log("Help request posted: " + m.getName());
		return m;
	}

	public static ThreatAidMissionIntel postAid(MarketAPI market, String commodityId, int need) {
		if (market == null || commodityId == null || need <= 0) return null;
		ThreatAidMissionIntel m = new ThreatAidMissionIntel(KIND_AID, market, commodityId, need);
		Global.getSector().getIntelManager().queueIntel(m);
		ThreatIncConfig.log("Help request posted: " + m.getName());
		return m;
	}

	/** Posted or accepted, in the intel list or still in the comm queue. */
	public static List<ThreatAidMissionIntel> getOpen() {
		List<ThreatAidMissionIntel> result = new ArrayList<ThreatAidMissionIntel>();
		List<IntelInfoPlugin> all = new ArrayList<IntelInfoPlugin>();
		all.addAll(Global.getSector().getIntelManager().getIntel(ThreatAidMissionIntel.class));
		all.addAll(Global.getSector().getIntelManager().getCommQueue(ThreatAidMissionIntel.class));
		for (IntelInfoPlugin curr : all) {
			ThreatAidMissionIntel m = (ThreatAidMissionIntel) curr;
			if (m.isEnded() || m.isEnding()) continue;
			if (!m.isPosted() && !m.isAccepted()) continue;
			if (result.contains(m)) continue;
			result.add(m);
		}
		return result;
	}

	/** Posted or accepted and already in the player's intel (the comm queue has not reached them yet). */
	public static List<ThreatAidMissionIntel> inIntel() {
		List<ThreatAidMissionIntel> result = new ArrayList<ThreatAidMissionIntel>();
		for (IntelInfoPlugin curr : Global.getSector().getIntelManager().getIntel(ThreatAidMissionIntel.class)) {
			ThreatAidMissionIntel m = (ThreatAidMissionIntel) curr;
			if (m.isEnded() || m.isEnding()) continue;
			if (!m.isPosted() && !m.isAccepted()) continue;
			result.add(m);
		}
		return result;
	}

	public static int countPosted() {
		int n = 0;
		for (ThreatAidMissionIntel m : getOpen()) {
			if (m.isPosted()) n++;
		}
		return n;
	}

	/** An open request of this kind at the market (commodity ignored for defence), or null. */
	public static ThreatAidMissionIntel find(String marketId, int kind, String commodityId) {
		for (ThreatAidMissionIntel m : getOpen()) {
			if (m.kind != kind || !m.marketId.equals(marketId)) continue;
			if (kind == KIND_AID && (commodityId == null || !commodityId.equals(m.commodityId))) continue;
			return m;
		}
		return null;
	}

	/** Open requests the player can see at this colony, for the station commander's menu. */
	public static List<ThreatAidMissionIntel> openAt(MarketAPI market) {
		List<ThreatAidMissionIntel> result = new ArrayList<ThreatAidMissionIntel>();
		if (market == null) return result;
		for (ThreatAidMissionIntel m : inIntel()) {
			if (m.marketId.equals(market.getId())) result.add(m);
		}
		return result;
	}

	/**
	 * A delivery landed at the colony: the matching request takes it (a
	 * posted one is accepted by the act). Returns true if a request took
	 * any of it.
	 */
	public static boolean creditDelivery(MarketAPI market, String commodityId, int quantity) {
		if (market == null || commodityId == null || quantity <= 0) return false;
		for (ThreatAidMissionIntel m : inIntel()) {
			if (m.kind != KIND_AID || !m.marketId.equals(market.getId())) continue;
			if (!commodityId.equals(m.commodityId) || m.remaining() <= 0) continue;
			if (m.isPosted()) m.accept();
			m.delivered += quantity;
			ThreatIncConfig.log("Help request credited: " + quantity + " " + commodityId + " -> "
					+ m.getName() + " (" + m.delivered + "/" + m.needed + ")");
			if (m.delivered >= m.needed) m.complete("delivered");
			return true;
		}
		return false;
	}

	/** A Threat action landed on the colony: every defence contract for it fails. */
	public static void strikeLanded(MarketAPI market) {
		if (market == null) return;
		for (ThreatAidMissionIntel m : inIntel()) {
			if (m.kind != KIND_DEFEND || !m.marketId.equals(market.getId())) continue;
			if (!m.isAccepted()) continue;
			m.fail("landed");
		}
	}

	// ------------------------------------------------------------------
	// accessors
	// ------------------------------------------------------------------

	public int getKind() {
		return kind;
	}

	public String getMarketId() {
		return marketId;
	}

	public String getCommodityId() {
		return commodityId;
	}

	public int getNeeded() {
		return needed;
	}

	public int getDelivered() {
		return delivered;
	}

	public int remaining() {
		return Math.max(0, needed - delivered);
	}

	public int getReward() {
		return reward;
	}

	public String getFactionId() {
		return factionId;
	}

	public FactionAPI getFaction() {
		FactionAPI faction = Global.getSector().getFaction(factionId);
		if (faction == null) faction = Global.getSector().getFaction(Factions.INDEPENDENT);
		return faction;
	}

	protected MarketAPI getMarket() {
		return Global.getSector().getEconomy().getMarket(marketId);
	}

	protected String marketName() {
		MarketAPI market = getMarket();
		return market != null ? market.getName() : marketId;
	}

	public float postingDaysRemaining() {
		return Math.max(0f, ThreatIncConfig.missionPostingDays()
				- Global.getSector().getClock().getElapsedDaysSince(postedTimestamp));
	}

	public float completionDaysRemaining() {
		if (duration == null) return 0f;
		return Math.max(0f, duration - elapsedDays);
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	@Override
	public void advanceImpl(float amount) {
		MarketAPI market = getMarket();
		if (isPosted()) {
			if (market == null || !factionId.equals(market.getFactionId())) {
				cancelWithReason("neutralized");
				return;
			}
			if (kind == KIND_DEFEND && ThreatAidRequests.strikesAgainst(market).isEmpty()) {
				cancelWithReason("neutralized");
				return;
			}
			if (postingDaysRemaining() <= 0f) {
				cancelWithReason("expired");
				return;
			}
			flag(market);
			return;
		}
		if (!isAccepted()) return;
		setElapsedDays(getElapsedDays() + amount);
		if (market == null || !factionId.equals(market.getFactionId())) {
			fail("lost");
			return;
		}
		flag(market);
		boolean termOver = getDuration() != null && getElapsedDays() >= getDuration();
		if (kind == KIND_AID) {
			if (delivered >= needed) {
				complete("delivered");
			} else if (termOver) {
				fail("expired");
			}
			return;
		}
		watch.advance(amount);
		if (watch.intervalElapsed()) watchStrikes(market);
		if (!isAccepted()) return;
		// a strike still in the system decides the contract; the term waits for it
		if (termOver && engaged.isEmpty()) {
			if (stopped > 0) complete("held");
			else endUnpaid("quiet");
		}
	}

	@Override
	public void advanceMission(float amount) {
		// handled in advanceImpl: the term is ours to run
	}

	protected void flag(MarketAPI market) {
		if (market != null) market.getMemoryWithoutUpdate().set(MEM_REQUEST, true, 2f);
	}

	/**
	 * Strikes in the colony's system are noted, and whether a player asset
	 * was there with them. One that leaves or dies without landing an action
	 * was stopped - by the player if an asset was present, else by the
	 * faction alone, which ends the contract unpaid.
	 */
	protected void watchStrikes(MarketAPI market) {
		List<GenericRaidFGI> strikes = ThreatAidRequests.strikesAgainst(market);
		boolean asset = ThreatAid.playerAssetIn(market);
		for (GenericRaidFGI s : strikes) {
			if (!ThreatAidRequests.strikeAtTarget(s, market)) continue;
			if (!engaged.contains(s)) engaged.add(s);
			if (asset && !covered.contains(s)) covered.add(s);
		}
		for (Object o : new ArrayList<Object>(engaged)) {
			GenericRaidFGI s = o instanceof GenericRaidFGI ? (GenericRaidFGI) o : null;
			boolean over = s == null || s.isEnded() || s.isEnding() || s.isAborted()
					|| s.isFailed() || s.isSucceeded() || !strikes.contains(s);
			if (!over) continue;
			engaged.remove(o);
			if (covered.contains(o)) {
				covered.remove(o);
				stopped++;
				ThreatIncConfig.log("Help request: strike on " + marketName()
						+ " stopped with a player asset present (" + stopped + ")");
			} else {
				endUnpaid("alone");
				return;
			}
		}
	}

	protected void accept() {
		setImportant(true);
		setMissionState(MissionState.ACCEPTED);
		missionAccepted();
	}

	protected void complete(String how) {
		Global.getSector().getPlayerFleet().getCargo().getCredits().add(reward);
		MissionCompletionRep rep = new MissionCompletionRep(RepRewards.HIGH, RepLevel.WELCOMING,
				-RepRewards.TINY, RepLevel.INHOSPITABLE);
		ReputationAdjustmentResult result = Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.MISSION_SUCCESS, rep, null, null, true, false),
				factionId);
		MissionResult mr = new MissionResult(reward, result);
		mr.custom = how;
		setMissionResult(mr);
		setMissionState(MissionState.COMPLETED);
		endMission();
		sendUpdateIfPlayerHasIntel(mr, false);
		ThreatIncConfig.log("Help request completed (" + how + "): " + getName() + ", paid " + reward);
	}

	protected void fail(String how) {
		MissionResult mr = createAbandonedResult(true);
		mr.custom = how;
		setMissionResult(mr);
		setMissionState(MissionState.FAILED);
		endMission();
		sendUpdateIfPlayerHasIntel(mr, false);
		ThreatIncConfig.log("Help request failed (" + how + "): " + getName());
	}

	/** Nothing was owed: over without pay or penalty. */
	protected void endUnpaid(String reason) {
		cancelReason = reason;
		MissionResult mr = new MissionResult();
		mr.custom = reason;
		setMissionResult(mr);
		setMissionState(MissionState.CANCELLED);
		endMission();
		sendUpdateIfPlayerHasIntel(mr, false);
		ThreatIncConfig.log("Help request ended unpaid (" + reason + "): " + getName());
	}

	protected void cancelWithReason(String reason) {
		if (!isPosted()) return;
		cancelReason = reason;
		cancel();
		ThreatIncConfig.log("Help request withdrawn (" + reason + "): " + getName());
	}

	@Override
	public void missionAccepted() {
		MarketAPI market = getMarket();
		if (market != null && market.getPrimaryEntity() != null) {
			Misc.setFlagWithReason(market.getPrimaryEntity().getMemoryWithoutUpdate(),
					MemFlags.ENTITY_MISSION_IMPORTANT, "threatinc_aid", true, getDuration());
		}
		ThreatIncConfig.log("Help request accepted: " + getName());
	}

	@Override
	public void endMission() {
		MarketAPI market = getMarket();
		if (market != null && market.getPrimaryEntity() != null) {
			Misc.setFlagWithReason(market.getPrimaryEntity().getMemoryWithoutUpdate(),
					MemFlags.ENTITY_MISSION_IMPORTANT, "threatinc_aid", false, 0f);
		}
		endAfterDelay();
	}

	@Override
	protected MissionResult createAbandonedResult(boolean withPenalty) {
		if (withPenalty) {
			MissionCompletionRep rep = new MissionCompletionRep(RepRewards.HIGH, RepLevel.WELCOMING,
					-RepRewards.TINY, RepLevel.INHOSPITABLE);
			ReputationAdjustmentResult result = Global.getSector().adjustPlayerReputation(
					new RepActionEnvelope(RepActions.MISSION_FAILURE, rep, null, null, true, false),
					factionId);
			return new MissionResult(0, result);
		}
		return new MissionResult();
	}

	@Override
	protected MissionResult createTimeRanOutFailedResult() {
		return createAbandonedResult(true);
	}

	@Override
	public boolean shouldRemoveIntel() {
		return isEnded() && super.shouldRemoveIntel();
	}

	// ------------------------------------------------------------------
	// presentation
	// ------------------------------------------------------------------

	protected String commodityLabel() {
		return ThreatReserves.label(commodityId);
	}

	@Override
	public String getName() {
		if (kind == KIND_DEFEND) {
			return "Defend " + marketName() + " for the " + getFaction().getDisplayName()
					+ getPostfixForState();
		}
		return "Deliver " + Misc.getWithDGS(needed) + " " + commodityLabel() + " to " + marketName()
				+ getPostfixForState();
	}

	@Override
	protected String getMissionTypeNoun() {
		return kind == KIND_DEFEND ? "defence contract" : "aid contract";
	}

	@Override
	public FactionAPI getFactionForUIColors() {
		return getFaction();
	}

	@Override
	public String getIcon() {
		String crest = getFaction().getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		tags.add(factionId);
		return tags;
	}

	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		MarketAPI market = getMarket();
		return market != null ? market.getPrimaryEntity() : null;
	}

	@Override
	public String getSmallDescriptionTitle() {
		return getName();
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);
		addBulletPoints(info, mode);
	}

	@Override
	protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode) {
		Color h = Misc.getHighlightColor();
		float pad = 3f;
		float opad = 10f;
		float initPad = mode == ListInfoMode.IN_DESC ? opad : pad;
		Color tc = getBulletColorForMode(mode);
		FactionAPI faction = getFaction();

		bullet(info);
		boolean isUpdate = getListInfoParam() != null;
		if (missionResult != null && (isUpdate || !isPosted() && !isAccepted())) {
			if (missionResult.payment > 0) {
				info.addPara("%s received", initPad, tc, h, Misc.getDGSCredits(missionResult.payment));
				initPad = 0f;
			}
			if (missionResult.rep1 != null) {
				CoreReputationPlugin.addAdjustmentMessage(missionResult.rep1.delta, faction,
						null, null, null, info, tc, isUpdate, initPad);
			}
		} else {
			if (mode != ListInfoMode.IN_DESC) {
				info.addPara("Faction: " + faction.getDisplayName(), initPad, tc,
						faction.getBaseUIColor(), faction.getDisplayName());
				initPad = 0f;
			}
			info.addPara("%s reward", initPad, tc, h, Misc.getDGSCredits(reward));
			if (kind == KIND_AID) {
				info.addPara("%s of %s delivered", 0f, tc, h, Misc.getWithDGS(delivered),
						Misc.getWithDGS(needed));
			} else if (isAccepted()) {
				info.addPara("%s strikes stopped", 0f, tc, h, "" + stopped);
			}
			if (isAccepted()) {
				addDays(info, "remaining", completionDaysRemaining(), tc, 0f);
			} else {
				addDays(info, "remaining to accept", postingDaysRemaining(), tc, 0f);
				addDays(info, "to complete once accepted", getDuration() != null ? getDuration() : 0f,
						tc, 0f);
			}
		}
		unindent(info);
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		FactionAPI faction = getFaction();
		if (faction.getLogo() != null) info.addImage(faction.getLogo(), width, 128, opad);
		MarketAPI market = getMarket();
		String sysName = market != null && market.getStarSystem() != null
				? market.getStarSystem().getNameWithLowercaseType() : "its system";
		Color fc = faction.getBaseUIColor();
		Color h = Misc.getHighlightColor();

		if (kind == KIND_DEFEND) {
			info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle()) + " asks for the defence "
					+ "of " + marketName() + ", in the " + sysName + ": a Threat strike is coming and "
					+ "its defenders are outmatched.", opad, fc,
					faction.getDisplayNameWithArticleWithoutArticle());
			info.addPara("The contract runs for %s days. It pays if no Threat action lands on the "
					+ "colony in that time and you were there for it - your own fleet in the "
					+ "system when a strike arrives, or a guard sent from one of your colonies "
					+ "through the war board. A strike that raids or bombards the colony fails "
					+ "it on the spot; a strike the colony beats without you ends it unpaid.",
					opad, h, "" + (int) ThreatIncConfig.missionDefendDays());
		} else {
			info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle()) + " asks for %s "
					+ commodityLabel() + " at " + marketName() + ", in the " + sysName + ": the "
					+ "colony is short and its war depot cannot cover it.", opad, fc,
					Misc.getWithDGS(needed));
			info.addPara("Deliver it any way you like: hand it over to the station commander "
					+ "when you dock, or send a convoy from one of your colonies through the war "
					+ "board. Partial deliveries count; the contract completes when the total is "
					+ "reached.", opad);
		}

		if (!isPosted() && !isAccepted()) {
			String how = missionResult != null && missionResult.custom instanceof String
					? (String) missionResult.custom : null;
			if (isCompleted()) {
				info.addPara("held".equals(how) ? "The colony came through its window untouched and "
						+ "the contract was paid in full." : "The delivery was completed and the "
						+ "contract paid in full.", opad);
			} else if (isFailed()) {
				info.addPara("landed".equals(how) ? "A Threat strike landed on the colony; the "
						+ "contract failed." : "lost".equals(how) ? "The colony was lost; the "
						+ "contract failed." : "The deadline passed; the contract failed.", opad);
			} else if (isCancelled()) {
				if ("alone".equals(cancelReason)) {
					info.addPara("The colony beat off the strike without you. Nothing was owed.", opad);
				} else if ("quiet".equals(cancelReason)) {
					info.addPara("No strike came in the window. Nothing was owed.", opad);
				} else if ("neutralized".equals(cancelReason)) {
					info.addPara("The need passed before the request was accepted.", opad);
				} else {
					info.addPara("The request expired unaccepted.", opad);
				}
			} else if (isAbandoned()) {
				info.addPara("You abandoned this contract.", opad);
			}
			addGenericMissionState(info);
			addBulletPoints(info, ListInfoMode.IN_DESC);
			return;
		}

		addBulletPoints(info, ListInfoMode.IN_DESC);
		addGenericMissionState(info);
		addAcceptOrAbandonButton(info, width, "Accept", "Abandon");
	}
}
