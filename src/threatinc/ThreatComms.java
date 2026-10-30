package threatinc;

import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

/**
 * WAR COMMS (docs/war-effort-rework.md section 6, the user 2026-10-23: "the notifications are war
 * comms chatter - 'sindria needs marines, we cannot hold!' - rather than clinical 'sindria needs
 * exactly x marines'"). Every notice here is a title, one VOICED line in quotes in the faction's
 * colour, then one fact line naming the purpose and the good WITHOUT the figure (decision 4: the
 * figures live on the board and at the dock, read a hundred times; the notice is read once).
 * Three variants a scenario, picked by a seeded roll on the world and the day so a reload reads
 * the same. Titles light blue under the faction's crest; red only for a loss.
 */
public class ThreatComms {

	// ---- 6.1 needs appearing: {title, v1, v2, v3, fact} ----

	static final String[] HOLD = { "{World} Calls For Marines",
			"{World} to all stations: the line is breaking. Marines on the ground or the city falls.",
			"We are losing districts by the day. Send marines - anything that can hold a rifle.",
			"The garrison is bleeding out on {World}. Marines, now, or write us off.",
			"Needs: marines, hold the line" };
	static final String[] OVERRUN = { "{World} Is Falling",
			"This is {World}. We cannot hold. If anyone is coming, come now.",
			"Last district. {Faction} command, we are being overrun.",
			"{World} is going dark. Whoever hears this: we have {days} days.",
			"Needs: marines, hold the line - {days} days at this pace" };
	static final String[] PUSH_BACK = { "{World} Holds",
			"{World} holds, barely. Give us marines and we take the districts back.",
			"The line is steady. More marines and we go over the top.",
			"We have them stopped. We need the numbers to finish it.",
			"Needs: marines, push back" };
	static final String[] ARM = { "{World} Short Of Armaments",
			"Our marines are fighting with hunting rifles. Heavy armaments to {World}, anyone who can.",
			"{World} quartermaster: we are down to sidearms. Armaments, by any hull.",
			"The depot is bare. Heavy armaments or the line goes quiet.",
			"Needs: heavy armaments, arm the defenders" };
	static final String[] ORBIT = { "{World} Blockaded",
			"The swarm sits on {World}'s orbit. Nothing lands until it is driven off - warships, not freighters.",
			"{Faction} command: {World} is cut off from above. We need hulls in that sky.",
			"Every convoy we send waits at the jump point. Clear the orbit and the marines are an hour away.",
			"Needs: fleets, lift the orbit" };
	static final String[] INBOUND = { "Threat Inbound On {World}",
			"Threat fleets inbound on {World}. Our patrols will not hold them. Any fleet in reach.",
			"{World} tower to all captains: they are coming, and we are outweighed.",
			"{Faction} command: {World} will be struck within days. We ask for ships.",
			"Needs: fleets, meet the strike" };
	static final String[] DEPOT_FUEL = { "{World} Depot Dry",
			"{World}'s tanks are dry: our convoys are grounded.",
			"No fuel at {World}. The patrols fly on fumes.",
			"{World} asks for fuel. The war does not wait for the tankers.",
			"Needs: fuel, keep the depot" };
	static final String[] DEPOT_SUPPLIES = { "{World} Short Of Supplies",
			"{World} cannot keep its ships flying. Supplies, if you have them.",
			"The yards at {World} are idle for want of supplies.",
			"{World} to the sector: our stores are out. Supplies, any quantity.",
			"Needs: supplies, keep the depot" };
	static final String[] DEPOT_ARMS = { "{World} Short Of Armaments",
			"{World}'s garrison drills with empty racks. Heavy armaments wanted.",
			"The militia at {World} has nothing to shoot with.",
			"{Faction} quartermaster: {World} needs armaments before the swarm finds it.",
			"Needs: heavy armaments, keep the depot" };
	static final String[] DEPOT_MARINES = { "{World} Short Of Marines",
			"{World}'s barracks stand half empty. Marines wanted before the swarm finds it.",
			"The garrison at {World} is under strength. Marines, by any hull.",
			"{Faction} command: {World}'s garrison is a skeleton crew. We ask for marines.",
			"Needs: marines, keep the depot" };
	static final String[] NAVY = { "{Faction} Short Of Hulls",
			"The yards at {World} cannot replace the losses at the front. The navy sails thin.",
			"{Faction} admiralty: we have crews and no hulls. Ships, by convoy, to {World}.",
			"Every slip at {World} is full and it is not enough. We ask for hulls.",
			"Needs: ship hulls, rebuild the navy" };
	static final String[] EXPEDITION_FUEL = { "{Faction} Expedition Waits",
			"The expedition{against} is fuelled for half the voyage. Fuel to {World} and it sails.",
			"{Faction} command: the fleet is ready and the tanks are not. Fuel to {World}.",
			"We have the ships and the marines. We lack the fuel to get them there.",
			"Needs: fuel, provision the expedition" };
	static final String[] EXPEDITION_SUPPLIES = { "{Faction} Expedition Waits",
			"The expedition{against} is stored for half the voyage. Supplies to {World} and it sails.",
			"{Faction} command: the fleet is ready and the stores are not. Supplies to {World}.",
			"We have the ships and the marines. We lack the supplies to keep them there.",
			"Needs: supplies, provision the expedition" };

	// ---- 6.2 needs met ----

	static final String[] HOLDS = { "{World} Holds The Line",
			"{World} to all stations: the marines landed. The line holds.",
			"They are in the trenches. We are not going anywhere.",
			"Reinforcements on the ground. {World} fights on.",
			"Marines landed; the line holds" };
	static final String[] PUSHES = { "{World} Pushes Back",
			"{World}: we are taking the districts back.",
			"The swarm is giving ground on {World}.",
			"{Faction} command: {World} is on the offensive.",
			"The defenders gain ground" };
	static final String[] CLEAR = { "{World} Is Clear",
			"{World} is clear. The swarm is dead on the ground.",
			"No Threat left on {World}. Thank you - all of you.",
			"{World} to the sector: it is over here.",
			"The Threat army is gone" };
	static final String[] SKY = { "{World}'s Sky Is Clear",
			"The orbit is ours. Convoys, {World} is open.",
			"{World} tower: the swarm is gone from above. Land everything.",
			"Sky clear over {World}. Now the ground.",
			"The swarm no longer holds the orbit" };
	static final String[] ARMED = { "{World} Rearmed",
			"The armaments are down on {World}. The line has teeth again.",
			"{World} quartermaster: racks full. Our marines shoot back now.",
			"Heavy armaments on the ground at {World}. Our thanks.",
			"The defenders are armed" };
	static final String[] FUELLED = { "{World} Refuelled",
			"{World} depot: tanks full. Convoys roll.",
			"Fuel landed at {World}. The patrols fly again.",
			"{World} is fuelled. Our thanks.",
			"The depot's fuel is restored" };
	static final String[] STOCKED = { "{World} Resupplied",
			"Supplies landed at {World}. The yards are working.",
			"{World} depot: stores full. The ships fly.",
			"{World} is stocked. Our thanks.",
			"The depot's supplies are restored" };
	static final String[] DEPOT_ARMED = { "{World} Rearmed",
			"{World}'s racks are full. Let the swarm come.",
			"The militia at {World} has its armaments.",
			"{Faction} quartermaster: {World} is armed. Our thanks.",
			"The depot's armaments are restored" };
	static final String[] MANNED = { "{World} Garrisoned",
			"{World}'s barracks are full. The garrison stands ready.",
			"Marines landed at {World}. We are at strength.",
			"{Faction} command: {World} is manned. Our thanks.",
			"The garrison is at strength" };
	static final String[] REBUILT = { "{Faction} Navy Rebuilt",
			"The hulls are in at {World}. The navy sails full.",
			"{Faction} admiralty: the fleet is whole again.",
			"Every slip at {World} has delivered. Our thanks.",
			"The navy's losses are replaced" };
	static final String[] SAILS = { "{Faction} Expedition Sails",
			"Fuelled and stored. The expedition sails from {World}.",
			"{Faction} command: the fleet is under way.",
			"The expedition is provisioned. The swarm will hear from us.",
			"The expedition sails" };

	// ---- 6.3 the player's requests answered ----

	static final String[] ANSWERS = { "{Faction} Answers {World}",
			"{Faction} command: {n} {good} sail from {Colony} for {World}. We will not forget the asking.",
			"{Colony} to {World}: help is coming.",
			"{Faction} to the {PlayerFaction}: our convoy sails. Consider the favour noted." };
	static final String[] SAILS_FOR = { "{Faction} Sails For {World}",
			"{Faction} admiralty: {n} fleet points under way to {World}.",
			"Ships are coming, {World}. Hold the sky until they arrive.",
			"{Faction} to {World}: we are {days} days out." };
	static final String[] CALL_ANSWERED = { "{World}'s Call Answered",
			"{World}: the last convoy is in. Our thanks to every flag that answered.",
			"{World}: every convoy is down. We owe the flags that came.",
			"{World} has what it asked for. Tell the captains who answered: we will not forget." };
	static final String[] DOCK_THANKS = {
			"{World} takes the {good} with thanks. The {Faction} will remember this.",
			"The quartermaster signs for {n} {good}. 'You may have just saved this city.'",
			"'That is more than we asked. It will all be used.'" };

	// ------------------------------------------------------------------

	/** The variant for this world today: the same on a reload. */
	protected static int variant(MarketAPI m) {
		int day = Global.getSector().getClock().getDay() + 31 * Global.getSector().getClock().getMonth()
				+ 372 * Global.getSector().getClock().getCycle();
		return new Random((m != null ? m.getId().hashCode() : 0) ^ (day * 7919)).nextInt(3);
	}

	/** {World}, {Faction}, {against} (" against {target}" or nothing), {days} and the rest substituted. */
	protected static String fill(String s, FactionAPI faction, MarketAPI m, ThreatNeeds.Need n) {
		if (s == null) return "";
		String world = m != null ? m.getName() : "the colony";
		String f = faction != null ? ThreatWarState.displayName(faction.getId()) : "command";
		s = s.replace("{World}", world).replace("{Faction}", f);
		s = s.replace("{against}", n != null && n.target != null ? " against " + n.target : "");
		int days = n != null && n.days >= 0 ? Math.max(1, n.days) : -1;
		s = s.replace("{days} days", days == 1 ? "1 day" : "{days} days");
		s = s.replace("{days}", days >= 0 ? "" + days : "a few");
		return s;
	}

	/** Title, the voiced line in the faction's colour, the fact line; the faction's crest. */
	protected static ThreatNotice notice(String[] table, FactionAPI faction, MarketAPI m, ThreatNeeds.Need n) {
		int v = variant(m);
		ThreatNotice.Hl voice = new ThreatNotice.Hl("\"" + fill(table[1 + v], faction, m, n) + "\"",
				faction != null ? faction.getBaseUIColor() : Misc.getTextColor());
		if (faction != null) voice.factionId = faction.getId();
		ThreatNotice notice = ThreatNotice.titled(fill(table[0], faction, m, n)).icon(faction).line("%s", voice);
		if (table.length > 4 && table[4] != null) notice.line(fill(table[4], faction, m, n));
		return notice;
	}

	/** The table for a need that appears or escalates. */
	protected static String[] tableFor(ThreatNeeds.Need n) {
		if (ThreatNeeds.HOLD.equals(n.purpose)) return n.overrun ? OVERRUN : HOLD;
		if (ThreatNeeds.PUSH_BACK.equals(n.purpose)) return PUSH_BACK;
		if (ThreatNeeds.ARM.equals(n.purpose)) return ARM;
		if (ThreatNeeds.ORBIT.equals(n.purpose)) return n.strike ? INBOUND : ORBIT;
		if (ThreatNeeds.NAVY.equals(n.purpose) || ThreatNeeds.FLEET.equals(n.purpose)) return NAVY;
		if (ThreatNeeds.EXPEDITION.equals(n.purpose)) {
			return Commodities.FUEL.equals(n.good) ? EXPEDITION_FUEL : EXPEDITION_SUPPLIES;
		}
		if (Commodities.FUEL.equals(n.good)) return DEPOT_FUEL;
		if (Commodities.SUPPLIES.equals(n.good)) return DEPOT_SUPPLIES;
		if (Commodities.MARINES.equals(n.good)) return DEPOT_MARINES;
		return DEPOT_ARMS;
	}

	/** 6.1: a need appears (or escalates to overrun / exhausted). */
	public static void appears(FactionAPI faction, MarketAPI m, ThreatNeeds.Need n) {
		if (unspoken(n.purpose)) {
			ThreatIncConfig.log("Comms (quiet): " + notice(tableFor(n), faction, m, n).plain());
			return;
		}
		notice(tableFor(n), faction, m, n).send();
	}

	/**
	 * A navy's hulls and an expedition's provisions are never announced, short or met (the user,
	 * 2026-10-30, of "Short Of Hulls" and "Expedition Sails": "get rid of these"); the board shows them.
	 */
	protected static boolean unspoken(String purpose) {
		return ThreatNeeds.NAVY.equals(purpose) || ThreatNeeds.FLEET.equals(purpose)
				|| ThreatNeeds.EXPEDITION.equals(purpose);
	}

	/**
	 * 6.2: a need of this purpose is gone. What is said depends on what stands now: a marines or
	 * arms need gone with the army still there - the line holds (a push back need remains) or
	 * pushes back (none); the army gone - the world is clear; the orbit gone - the sky is clear;
	 * arms gone with the army still there - rearmed; a depot need gone - that good restored; hulls -
	 * the navy rebuilt; the expedition's last need - it sails. False when nothing is said.
	 */
	public static boolean met(FactionAPI faction, MarketAPI m, String purpose, String good, List<ThreatNeeds.Need> now) {
		if (m == null || faction == null) return false;
		if (unspoken(purpose)) return false;
		boolean army = ThreatAidRequests.underArmy(m);
		if (ThreatNeeds.HOLD.equals(purpose) || ThreatNeeds.PUSH_BACK.equals(purpose) || ThreatNeeds.ARM.equals(purpose)) {
			if (!army) {
				notice(CLEAR, faction, m, null).send();
				return true;
			}
			if (ThreatNeeds.find(now, ThreatNeeds.HOLD, null) != null) return false; // escalated: HOLD speaks
			if (ThreatNeeds.ARM.equals(purpose)) {
				notice(ARMED, faction, m, null).send();
				return true;
			}
			boolean pushBack = ThreatNeeds.find(now, ThreatNeeds.PUSH_BACK, null) != null;
			if (ThreatNeeds.PUSH_BACK.equals(purpose) && pushBack) return false;
			notice(pushBack ? HOLDS : PUSHES, faction, m, null).send();
			return true;
		}
		if (ThreatNeeds.ORBIT.equals(purpose)) {
			notice(SKY, faction, m, null).send();
			return true;
		}
		if (ThreatNeeds.DEPOT.equals(purpose)) {
			String[] table = Commodities.FUEL.equals(good) ? FUELLED : Commodities.SUPPLIES.equals(good) ? STOCKED
					: Commodities.MARINES.equals(good) ? MANNED : DEPOT_ARMED;
			notice(table, faction, m, null).send();
			return true;
		}
		if (ThreatNeeds.NAVY.equals(purpose) || ThreatNeeds.FLEET.equals(purpose)) {
			notice(REBUILT, faction, m, null).send();
			return true;
		}
		if (ThreatNeeds.find(now, ThreatNeeds.EXPEDITION, null) != null) return false; // the other provision still short
		notice(SAILS, faction, m, null).send();
		return true;
	}

	// ---- 6.3 ----

	/** A faction's convoy sails for the player's colony: the voiced line, then "{n} {good} from {Colony}; standing with {Faction} -{r}". */
	public static void answers(FactionAPI helper, MarketAPI donor, MarketAPI world, int n, String good, float rep) {
		String goodLabel = ThreatReserves.label(good);
		int v = variant(world);
		String line = ANSWERS[1 + v].replace("{n}", Misc.getWithDGS(ThreatHulls.shown(good, n))).replace("{good}", goodLabel)
				.replace("{Colony}", donor != null ? donor.getName() : "our port")
				.replace("{PlayerFaction}", Global.getSector().getPlayerFaction().getDisplayName());
		line = fill(line, helper, world, null);
		ThreatNotice.Hl voice = new ThreatNotice.Hl("\"" + line + "\"", helper.getBaseUIColor());
		voice.factionId = helper.getId();
		ThreatNotice.titled(fill(ANSWERS[0], helper, world, null)).icon(helper).line("%s", voice)
				.line("%s %s from %s; standing with %s %s", Misc.getWithDGS(ThreatHulls.shown(good, n)), goodLabel,
						donor != null ? ThreatNotice.market(donor) : "its port", ThreatNotice.faction(helper),
						ThreatNotice.red(String.format("-%.1f", rep)))
				.send();
	}

	/** A faction's guard sails for the player's colony. */
	public static void sailsFor(FactionAPI helper, MarketAPI base, MarketAPI world, int fp, int days, float rep) {
		int v = variant(world);
		String line = SAILS_FOR[1 + v].replace("{n}", Misc.getWithDGS(fp));
		ThreatNeeds.Need d = new ThreatNeeds.Need();
		d.days = days;
		line = fill(line, helper, world, d);
		ThreatNotice.Hl voice = new ThreatNotice.Hl("\"" + line + "\"", helper.getBaseUIColor());
		voice.factionId = helper.getId();
		ThreatNotice.titled(fill(SAILS_FOR[0], helper, world, null)).icon(helper).line("%s", voice)
				.line("%s fleet points from %s; standing with %s %s", Misc.getWithDGS(fp),
						base != null ? ThreatNotice.market(base) : "its base", ThreatNotice.faction(helper),
						ThreatNotice.red(String.format("-%.1f", rep)))
				.send();
	}

	/** Nobody answered the player's request: one fact line per candidate's reason. */
	public static void noAnswer(MarketAPI world, ThreatNotice.Reason reasons) {
		ThreatNotice n = ThreatNotice.titled("No Answer For " + world.getName()).bad()
				.icon(Global.getSector().getPlayerFaction());
		if (reasons != null) n.lines(reasons);
		else n.line("No faction is Favourable or better");
		n.send();
	}

	/** The player's request is covered: "{total} {good} landed from {k} factions". */
	public static void callAnswered(MarketAPI world, int total, String good, int senders) {
		FactionAPI player = Global.getSector().getPlayerFaction();
		ThreatNotice.Hl voice = new ThreatNotice.Hl("\"" + fill(CALL_ANSWERED[1 + variant(world)], player, world, null) + "\"",
				player.getBaseUIColor());
		ThreatNotice.titled(fill(CALL_ANSWERED[0], player, world, null)).icon(player).line("%s", voice)
				.line("%s %s from %s %s", Misc.getWithDGS(total),
						ThreatNeeds.FLEETS.equals(good) ? "fleet points" : ThreatReserves.label(good),
						"" + senders, senders == 1 ? "faction" : "factions")
				.send();
	}

	/** The dock's thanks after a hand-over (the text panel, not a notice). */
	public static String dockThanks(FactionAPI faction, MarketAPI world, int n, String good, boolean overMax) {
		String s = overMax ? DOCK_THANKS[2] : DOCK_THANKS[variant(world) % 2];
		return fill(s.replace("{n}", Misc.getWithDGS(n)).replace("{good}", ThreatReserves.label(good)), faction, world, null);
	}
}
