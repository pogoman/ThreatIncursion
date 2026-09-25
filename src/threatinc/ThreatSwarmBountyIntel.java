package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.ReputationActionResponsePlugin.ReputationAdjustmentResult;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * A SWARM BOUNTY (2026-09-24, docs/player-aid.md section 4): a base whose
 * siege the Defense Swarms over a hive system outweigh
 * (IncursionManager.siegeOrbitNeeded) pays for Threat ships destroyed in that
 * system - vanilla's system bounty, pointed at the hive. No accepting and no
 * failing: it runs swarmBountyDays, paying swarmBountyPerFrigate per ship by
 * hull size (frigate 1 to capital 5, vanilla's Misc.getSizeNum) times the player's share of the
 * fighting, with vanilla's system-bounty standing per battle - and the
 * player's hunting fleets earn it too, for their share of their side
 * ({@link HunterPay}). Defense Swarms
 * of that system count wherever they are caught - and each ship lost pays ONE
 * bounty: its garrison's home system's, else the system it was caught in's
 * ({@link #paidBy}). It ends early only if the
 * hive system falls, or the base or its faction's war goes. The siege gate
 * reads the live garrison, so every swarm destroyed opens the siege sooner.
 */
public class ThreatSwarmBountyIntel extends BaseIntelPlugin {

	protected String systemId;
	protected String factionId;
	protected String baseMarketId;
	/** The Defense Swarm FP the base's siege can take, when posted. */
	protected int siegeFP;
	protected float baseBounty;
	protected float duration;
	protected float elapsedDays;
	protected int totalPaid;
	protected int latestPayment;
	protected float latestFraction = 1f;
	protected ReputationAdjustmentResult latestRep;
	/** Why it ended early: "cleared", "neutralized"; null when it ran its term. */
	protected String endReason;

	protected ThreatSwarmBountyIntel(MarketAPI base, StarSystemAPI system, int siegeFP) {
		this.systemId = system.getId();
		this.factionId = base.getFactionId();
		this.baseMarketId = base.getId();
		this.siegeFP = siegeFP;
		this.baseBounty = Math.max(0f, ThreatIncConfig.swarmBountyPerFrigate());
		this.duration = Math.max(1f, ThreatIncConfig.swarmBountyDays());
	}

	// ------------------------------------------------------------------
	// posting and lookup
	// ------------------------------------------------------------------

	/**
	 * A base's siege is outweighed in orbit: post a bounty on the hive
	 * system's swarms, unless one is already running there.
	 */
	public static ThreatSwarmBountyIntel post(MarketAPI base, StarSystemAPI system, int siegeFP) {
		if (base == null || system == null || !ThreatIncConfig.swarmBountiesEnabled()) return null;
		if (find(system.getId()) != null) return null;
		ThreatSwarmBountyIntel b = new ThreatSwarmBountyIntel(base, system, siegeFP);
		Global.getSector().getIntelManager().addIntel(b);
		ThreatIncConfig.log("Swarm bounty posted by " + b.factionId + " on " + system.getName()
				+ " (siege takes " + siegeFP + " FP)");
		return b;
	}

	/** Every bounty still running. */
	public static List<ThreatSwarmBountyIntel> running() {
		List<ThreatSwarmBountyIntel> result = new ArrayList<ThreatSwarmBountyIntel>();
		for (IntelInfoPlugin curr : Global.getSector().getIntelManager().getIntel(ThreatSwarmBountyIntel.class)) {
			ThreatSwarmBountyIntel b = (ThreatSwarmBountyIntel) curr;
			if (!b.isEnded() && !b.isEnding()) result.add(b);
		}
		return result;
	}

	public String getSystemId() {
		return systemId;
	}

	/** The running bounty on this hive system, or null. */
	public static ThreatSwarmBountyIntel find(String systemId) {
		for (IntelInfoPlugin curr : Global.getSector().getIntelManager().getIntel(ThreatSwarmBountyIntel.class)) {
			ThreatSwarmBountyIntel b = (ThreatSwarmBountyIntel) curr;
			if (b.isEnded() || b.isEnding()) continue;
			if (systemId.equals(b.systemId)) return b;
		}
		return null;
	}

	/**
	 * Hears the player's battles and pays the running bounties for the
	 * Threat ships lost on the other side. Transient, added on every load.
	 */
	public static class Kills extends BaseCampaignEventListener {
		public Kills() {
			super(false);
		}

		@Override
		public void reportBattleOccurred(CampaignFleetAPI primaryWinner, BattleAPI battle) {
			if (battle == null) return;
			thinned(battle);
			if (!battle.isPlayerInvolved()) return;
			payAll(battle.getNonPlayerSideSnapshot(), battle.getPlayerInvolvementFraction(),
					Global.getSector().getPlayerFleet(), "your fleet", battle);
		}

		@Override
		public void reportBattleFinished(CampaignFleetAPI primaryWinner, BattleAPI battle) {
			settle(battle);
		}
	}

	/**
	 * Any battle - the player's, a task force's, a sortie's - that sank Threat
	 * ships of a bountied hive system weighs its sieges on the next poll
	 * (IncursionManager.huntThinned), as a hunting force's does: the swarms
	 * regrow before the monthly tick, and the bounty promises that every swarm
	 * destroyed opens the siege sooner.
	 */
	protected static void thinned(BattleAPI battle) {
		if (running().isEmpty()) return;
		java.util.Set<String> systems = new java.util.HashSet<String>();
		List<CampaignFleetAPI> all = battle.getSnapshotBothSides();
		if (all == null) return;
		for (CampaignFleetAPI fleet : all) {
			if (fleet == null || Misc.getSnapshotMembersLost(fleet).isEmpty()) continue;
			String battleSystemId = fleet.getContainingLocation() instanceof StarSystemAPI
					? ((StarSystemAPI) fleet.getContainingLocation()).getId() : null;
			String id = paidBy(fleet, battleSystemId);
			if (id != null && find(id) != null) systems.add(id);
		}
		for (String id : systems) IncursionManager.huntThinned(id);
	}

	/**
	 * Rides each of the player's hunting fleets (ThreatFleetOrders.dispatchHunt
	 * and adoptHunt, 2026-09-24): a battle it fights pays the running
	 * bounties for the Threat ships lost on the other side, for that fleet's
	 * share of its side's strength - so a Hunt order earns the bounty with
	 * the player elsewhere. Saved with the fleet; no fields.
	 */
	public static class HunterPay implements FleetEventListener {
		/** Puts one on the fleet unless it carries one already: a fleet hunted again would otherwise pay each bounty twice. */
		public static void attach(CampaignFleetAPI fleet) {
			if (fleet == null) return;
			List<FleetEventListener> listeners = fleet.getEventListeners();
			if (listeners != null) {
				for (FleetEventListener l : listeners) {
					if (l instanceof HunterPay) return;
				}
			}
			fleet.addEventListener(new HunterPay());
		}

		@Override
		public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
		}

		@Override
		public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
			if (fleet == null || battle == null) return;
			// a battle the player is in is paid by Kills, for the player's share -
			// which already counts the player's own fleets. Asked of the live
			// player side instead, a hunter wiped out in the fight had been pruned
			// from it and was paid on top (rc1 review)
			if (battle.isPlayerInvolved()) return;
			// a former hunter moved to Support or Defend earns no bounty
			if (!ThreatFleetOrders.onHunt(fleet)) return;
			float ours = snapshotFP(fleet);
			float side = 0f;
			for (CampaignFleetAPI f : battle.getSnapshotSideFor(fleet)) side += snapshotFP(f);
			if (ours <= 0f || side <= 0f) return;
			payAll(battle.getOtherSideSnapshotFor(fleet), Math.min(1f, ours / side), fleet, fleet.getName(), battle);
		}
	}

	/** Battle -> bounty -> {credits paid, FP destroyed} not yet announced; settled once per battle. Not saved. */
	protected static final java.util.Map<BattleAPI, java.util.Map<ThreatSwarmBountyIntel, float[]>> PENDING =
			new java.util.WeakHashMap<BattleAPI, java.util.Map<ThreatSwarmBountyIntel, float[]>>();

	/** Called on load: standing owed for a battle of the game this session left is not paid into the loaded one. */
	public static void forgetPending() {
		PENDING.clear();
	}

	/**
	 * Standing and the message for a battle, once it is over: an autoresolved
	 * fight reports every round, and each round had applied its own reputation
	 * step and sent its own message (4 of each for a 4-round fight).
	 */
	protected static void settle(BattleAPI battle) {
		java.util.Map<ThreatSwarmBountyIntel, float[]> owed = battle != null ? PENDING.remove(battle) : null;
		if (owed == null) return;
		for (java.util.Map.Entry<ThreatSwarmBountyIntel, float[]> e : owed.entrySet()) {
			e.getKey().announce((int) e.getValue()[0], e.getValue()[1]);
		}
	}

	/** Fleet points a fleet went into the battle with. */
	protected static float snapshotFP(CampaignFleetAPI fleet) {
		float fp = 0f;
		if (fleet == null || fleet.getFleetData() == null) return fp;
		for (FleetMemberAPI m : fleet.getFleetData().getSnapshot()) fp += m.getFleetPointCost();
		return fp;
	}

	/**
	 * Pays the running bounties for the Threat ships lost among
	 * {@code enemies} in a battle {@code ours} fought, times {@code share}.
	 * Every enemy fleet is handed to exactly one bounty ({@link #paidBy}), so
	 * a Defense Swarm of one bountied system caught in another is not paid
	 * for twice. Both the player's own battles (Kills) and the hunting fleets'
	 * (HunterPay) come through here.
	 */
	protected static void payAll(List<CampaignFleetAPI> enemies, float share, CampaignFleetAPI ours, String who,
			BattleAPI battle) {
		// a battle whose finish was never heard settles now
		for (BattleAPI b : new ArrayList<BattleAPI>(PENDING.keySet())) {
			if (b != battle && b.isDone()) settle(b);
		}
		if (enemies == null || enemies.isEmpty() || share <= 0f) return;
		List<ThreatSwarmBountyIntel> bounties = running();
		if (bounties.isEmpty()) return;
		String battleSystemId = ours != null && ours.getContainingLocation() instanceof StarSystemAPI
				? ((StarSystemAPI) ours.getContainingLocation()).getId() : null;
		List<String> payer = new ArrayList<String>();
		for (CampaignFleetAPI fleet : enemies) payer.add(paidBy(fleet, battleSystemId));
		for (ThreatSwarmBountyIntel b : bounties) {
			List<CampaignFleetAPI> covered = new ArrayList<CampaignFleetAPI>();
			for (int i = 0; i < enemies.size(); i++) {
				if (b.systemId.equals(payer.get(i))) covered.add(enemies.get(i));
			}
			if (!covered.isEmpty()) b.payFor(covered, share, who, battle);
		}
	}

	/**
	 * The hive system whose bounty pays for this Threat fleet: its garrison's
	 * home system when a bounty runs there, else the system it was caught in
	 * (which pays only if a bounty runs there). Null for a fleet that is not
	 * Threat's.
	 */
	protected static String paidBy(CampaignFleetAPI fleet, String battleSystemId) {
		if (fleet == null || fleet.getFaction() == null) return null;
		if (!Factions.THREAT.equals(fleet.getFaction().getId())) return null;
		String home = fleet.getMemoryWithoutUpdate().getString(ThreatColonyManager.GARRISON_FLAG);
		MarketAPI colony = home != null ? Global.getSector().getEconomy().getMarket(home) : null;
		if (colony != null && colony.getStarSystem() != null && find(colony.getStarSystem().getId()) != null) {
			return colony.getStarSystem().getId();
		}
		return battleSystemId;
	}

	/** Pays for the Threat ships lost among {@code enemies} - the fleets {@link #payAll} handed this bounty - times {@code share}. */
	protected void payFor(List<CampaignFleetAPI> enemies, float share, String who, BattleAPI battle) {
		if (enemies == null || share <= 0f) return;
		float bounty = 0f;
		float fpDestroyed = 0f;
		for (CampaignFleetAPI fleet : enemies) {
			for (FleetMemberAPI loss : Misc.getSnapshotMembersLost(fleet)) {
				bounty += Misc.getSizeNum(loss.getHullSpec().getHullSize()) * baseBounty;
				fpDestroyed += loss.getFleetPointCost();
			}
		}
		int payment = (int) (bounty * share);
		if (payment <= 0) return;
		Global.getSector().getPlayerFleet().getCargo().getCredits().add(payment);
		latestFraction = share;
		totalPaid += payment;
		ThreatIncConfig.log("Swarm bounty paid on " + systemName() + " (" + who + "): " + payment + " for "
				+ (int) fpDestroyed + " FP destroyed (share " + (int) (share * 100f) + "%)");
		float repFP = (int) (fpDestroyed * share);
		if (battle == null) {
			announce(payment, repFP);
			return;
		}
		// standing and the message wait for the battle's end: once per battle
		java.util.Map<ThreatSwarmBountyIntel, float[]> owed = PENDING.get(battle);
		if (owed == null) {
			owed = new java.util.LinkedHashMap<ThreatSwarmBountyIntel, float[]>();
			PENDING.put(battle, owed);
		}
		float[] sum = owed.get(this);
		if (sum == null) {
			sum = new float[2];
			owed.put(this, sum);
		}
		sum[0] += payment;
		sum[1] += repFP;
	}

	/** One battle's pay on this bounty: vanilla's system-bounty standing and the message. */
	protected void announce(int payment, float repFP) {
		if (payment <= 0) return;
		latestRep = Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.SYSTEM_BOUNTY_REWARD, Float.valueOf(repFP), null, null, true, false),
				factionId);
		latestPayment = payment;
		sendUpdateIfPlayerHasIntel(Integer.valueOf(payment), false);
	}

	// ------------------------------------------------------------------
	// lifecycle (driven by IncursionManager.advanceModIntel)
	// ------------------------------------------------------------------

	@Override
	protected void advanceImpl(float amount) {
		elapsedDays += Global.getSector().getClock().convertToDays(amount);
		String gone = needGone();
		if (gone != null) {
			finish(gone);
		} else if (elapsedDays >= duration) {
			finish(null);
		}
	}

	protected String needGone() {
		if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) return "cleared";
		MarketAPI base = Global.getSector().getEconomy().getMarket(baseMarketId);
		if (base == null || !factionId.equals(base.getFactionId())) return "neutralized";
		if (!ThreatWarState.isAtWar(factionId)) return "neutralized";
		return null;
	}

	protected void finish(String reason) {
		endReason = reason;
		ThreatIncConfig.log("Swarm bounty on " + systemName() + " over"
				+ (reason != null ? " (" + reason + ")" : "") + ", paid " + totalPaid);
		// ending first, so the update renders as the ended bounty ("- Over", paid in all)
		endAfterDelay();
		sendUpdateIfPlayerHasIntel(new Object(), false);
	}

	@Override
	public boolean runWhilePaused() {
		return false;
	}

	// ------------------------------------------------------------------
	// presentation
	// ------------------------------------------------------------------

	public FactionAPI getFaction() {
		FactionAPI faction = Global.getSector().getFaction(factionId);
		if (faction == null) faction = Global.getSector().getFaction(Factions.INDEPENDENT);
		return faction;
	}

	/** The hive system, or null when it is gone. */
	protected StarSystemAPI system() {
		return systemId != null ? Global.getSector().getStarSystem(systemId) : null;
	}

	protected String systemName() {
		StarSystemAPI system = system();
		return system != null ? system.getNameWithLowercaseTypeShort() : "hive system";
	}

	protected String baseName() {
		MarketAPI base = Global.getSector().getEconomy().getMarket(baseMarketId);
		return base != null ? base.getName() : baseMarketId;
	}

	/** Defense Swarm points a siege faces now: the strongest world's (IncursionManager.siegeOrbitFaced). */
	protected float orbitFP() {
		StarSystemAPI system = system();
		if (system == null) return 0f;
		return IncursionManager.siegeOrbitFaced(IncursionManager.collectSiegeTargets(null, system));
	}

	@Override
	public String getName() {
		String name = "Swarm Bounty - " + systemName();
		if (isEnding() || isEnded()) name += " - Over";
		return name;
	}

	@Override
	public FactionAPI getFactionForUIColors() {
		return getFaction();
	}

	@Override
	public String getIcon() {
		return Global.getSettings().getSpriteName("intel", "system_bounty");
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(Tags.INTEL_BOUNTY);
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		tags.add(factionId);
		return tags;
	}

	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		List<MarketAPI> hives = new ArrayList<MarketAPI>(ThreatIncData.getLiveColonyMarkets(systemId));
		for (MarketAPI hive : hives) {
			if (hive.getPrimaryEntity() != null) return hive.getPrimaryEntity();
		}
		StarSystemAPI system = system();
		return system != null ? system.getCenter() : null;
	}

	@Override
	public String getSmallDescriptionTitle() {
		return getName();
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		info.addPara(getName(), getTitleColor(mode), 0f);
		addBulletPoints(info, mode);
	}

	@Override
	protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode) {
		Color h = Misc.getHighlightColor();
		Color tc = getBulletColorForMode(mode);
		float initPad = mode == ListInfoMode.IN_DESC ? 10f : 3f;
		FactionAPI faction = getFaction();
		bullet(info);
		boolean isUpdate = getListInfoParam() != null;
		if (isUpdate && getListInfoParam() instanceof Integer) {
			info.addPara("%s received", initPad, tc, h, Misc.getDGSCredits(latestPayment));
			if (latestRep != null) {
				CoreReputationPlugin.addAdjustmentMessage(latestRep.delta, faction, null, null, null,
						info, tc, isUpdate, 0f);
			}
		} else if (isEnding() || isEnded()) {
			info.addPara("%s paid in all", initPad, tc, h, Misc.getDGSCredits(totalPaid));
		} else {
			if (mode != ListInfoMode.IN_DESC) {
				String name = ThreatWarState.displayName(factionId);
				info.addPara("Faction: " + name, initPad, tc, faction.getBaseUIColor(), name);
				initPad = 0f;
			}
			info.addPara("%s base reward per frigate", initPad, tc, h, Misc.getDGSCredits(baseBounty));
			info.addPara("Strongest swarms %s FP, siege takes %s", 0f, tc, h, Misc.getWithDGS((int) orbitFP()),
					Misc.getWithDGS(siegeFP));
			addDays(info, "remaining", Math.max(0f, duration - elapsedDays), tc);
		}
		unindent(info);
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		FactionAPI faction = getFaction();
		if (faction.getLogo() != null) info.addImage(faction.getLogo(), width, 128, opad);
		info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle()) + " " + faction.getDisplayNameIsOrAre()
				+ " paying for Threat ships destroyed in the " + systemName() + ", for its siege from "
				+ baseName() + ".", opad);
		if (isEnding() || isEnded()) {
			info.addPara("cleared".equals(endReason) ? "The hive system has fallen; the bounty is over."
					: "neutralized".equals(endReason) ? "The bounty has been withdrawn."
					: "The bounty has run its term.", opad);
		}
		addBulletPoints(info, ListInfoMode.IN_DESC);
		if (totalPaid > 0 && !isEnding() && !isEnded()) {
			info.addPara("You have been paid %s so far.", opad, h, Misc.getDGSCredits(totalPaid));
		}
	}
}
