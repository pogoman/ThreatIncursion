# Design theory review - what the literature says about the game we are building

Written 2026-09-04 at the user's request, before building outposts. The mod has drifted
from "a new enemy faction" into an asymmetric single-player 4X layered on Starsector's
sandbox: a machine hive with its own economy (docs/hive-economy.md), a ground war
(docs/ground-war.md) and a faction-side strategy layer of reserves, convoys and remote
fleet orders (docs/strategy-layer.md). This note checks that direction against
established design writing and military/operational theory, and lists what is worth
verifying before more is built. Sources at the end.

## 1. What kind of game this is, and the precedents

**A single-player 4X orbits the human.** Soren Johnson's Old World notes make the point
directly: pretending an AI-driven 4X is symmetrical like a board game is a mistake; design
for pacing and the player's experience, acknowledge the asymmetry, and above all avoid a
surprise ending to a twenty-hour game [OW-END]. Our direction is already asymmetric by
construction; the open question it raises is the ENDING (section 6).

**The closest precedent is AI War: Fleet Command.** Chris Park built an RTS where the AI
does not play by the player's rules at all: it has its own economy of reinforcement
points and wave timers, and a single visible "AI Progress" number that rises as the player
takes worlds, pulling more of the AI's strength into play. The player sets the pace of the
war by choosing what to attack [AIWAR-INT][AIWAR-3MA]. Our hive has the first half of this
(its own fabrication economy, capability-based phases) but NOT the second: the swarm does
not currently escalate in response to the player's success. Whether it should is the
single biggest unanswered design question (section 5).

**The anti-precedent is the Stellaris endgame crisis.** The standard criticisms - crisis
fleets simply spawn with no economy or logistics behind them, the fight is a single
frontal fleet-power check, each crisis has a rote counter, and there is no mechanism for
factions to rally together [STELL-1][STELL-2] - are precisely the traps the hive economy,
the ground war and the faction war-mode layer are designed to avoid. Worth keeping as a
checklist: does every hive fleet come from a colony? (yes) Can supply lines be cut? (yes)
Is there more than one way to win a system? (yes: starve, siege, land) Do factions
cooperate? (partly - war mode is per faction; nothing coordinates them yet).

**Asymmetry first, balance late.** Both StarCraft II (Browder: make the races as
different as possible first, balance afterwards, then change conservatively) [SC2] and
Root (Wehrle: don't balance so early that you remove what made the asymmetry interesting;
balance through the interactions between factions) [ROOT] argue for the order we are
following: mechanics as knobs now, tuning after the in-game check.

## 2. Principles we already follow, and their grounding

- **Transparency.** Johnson: "transparency is the antidote to suspicion and distrust";
  the player must be able to predict the AI and be given enough cues to beat it [SJ-AI].
  The user's "full transparency on NPC faction reserves" and the fixed, plannable rates
  ([[fixed-decline-rate]] in memory) are this principle. Keep applying it to the hive side:
  counter-attack cadence, reinforcement rules and strike targeting should all be readable
  in tooltips, not inferred.
- **Everything comes from somewhere.** Physical supply is the design core of the serious
  wargames: Unity of Command's supply-line system, where movement and effectiveness depend
  on staying connected [UOC]; Hearts of Iron IV's hub-and-rail logistics, where the AI
  plays by the same supply rules as the player [HOI4]. Our per-colony reserves, convoys and
  cargo-carried troops are the same idea. The HOI4 lesson to remember: the supply AI was
  the weak point (routes that do not re-plan when the front moves) [HOI4-CRIT] - our convoy
  planner is deliberately simple and must be watched for the same failure (a base that
  keeps calling convoys through a system the swarm now holds).
- **Stock fed by flow.** Supreme Commander's economy is income against expenditure, not
  a scarce stockpile, and the game's depth comes from tipping that balance [SUPCOM].
  Distant Worlds separates a private economy the player only steers indirectly from a
  state economy the player commands, and makes every system automatable with a veto
  [DW]. Our reserves are stocks fed by vanilla production flows, the convoy planner runs
  itself, and the Stage button is the override. That is the Distant Worlds model and it is
  the right one for a game the player is also flying a ship in: **no chores**.

## 3. Battle doctrine: how we resolve fights on the ground

- **Lanchester.** Aimed-fire combat follows the square law: a force twice as large is
  four times as strong, so concentration wins and doubling production is worth more than
  doubling quality [LANCH-ADAMS][LANCH-WIKI]. Our ground resolution is LINEAR (strength
  ratio, clamped 0.5x-3x for push pace; a straight comparison for counter-attacks). That
  is a deliberate legibility choice ([[fixed-decline-rate]]) and it is defensible - many
  wargames flatten Lanchester for readability - but be aware of the consequence: linear
  resolution under-rewards concentration, so the player gains little by massing two
  fronts' worth of marines on one world, and the hive gains little by concentrating a
  counter-attack. If fights ever feel "mushy", a mild super-linear term is the lever, not
  more hit points.
- **The 3:1 rule is folklore.** Dupuy could find no historical substantiation for the
  "attacker needs 3:1" rule; attackers win at lower ratios and lose at higher ones
  depending on posture, surprise and terrain [DUPUY-31]. Our hold threshold (25 percent of
  the defense figure, mirroring vanilla's raid-disruption threshold) is a game number, not a
  doctrine number - fine, but do not defend it as realism.
- **Defense is the stronger form; the attack culminates.** Clausewitz: every offensive
  weakens as it advances (casualties, supply, friction) until a culminating point where
  the defender's counterstroke is strongest [CLAUSE][CULM]. This is already the shape of
  the stratum campaign: pushing bleeds (0.30 per 30 days), a pushing front is exposed, dry
  fronts fight at half strength, and the hive counter-attacks. What is MISSING is the
  supply-line half of culmination: an NPC front cannot be resupplied at all (it withers
  when its landing stock runs dry), and a player front is resupplied only by the player
  docking in person. The backlog item "logistics runs to fronts" is the fix and the
  literature says it is the important one - the front should culminate because its convoys
  stopped arriving, not because a timer said so.
- **Defense in depth.** Strata are an echeloned defense: each layer strips the attacker
  and buys time for the counterstroke. That is orthodox, and it is why the hive's
  counter-attack cadence scaled by health is the right coupling (a starved hive cannot
  mount the counterstroke that culmination promises the defender).
- **Saturation bombardment is off by default (2026-09-05), and the strike doctrine is now
  symmetric.** The old Threat strike was a bombardment: one pass wounded a world, or
  erased one already ground to the destroy threshold. That broke every principle above at
  once. It was *decision-free* - nothing the defender did between the strike launching and
  the pass landing changed the outcome, so the sector's biggest event had no counterplay
  and no story, only a die roll on whether the swarm reached you. It was *asymmetric* in
  the worst direction: the player had spent the whole ground-war rework learning that
  colonies die on the ground and never from orbit, and then watched the swarm do the one
  thing the rules said was impossible. And it made the strike *terminal rather than
  positional* - there was no siege to relieve, no front to counter-attack, nothing to
  culminate, so none of Clausewitz, Corbett or Blackett had any purchase on the sector's
  central threat. Replacing the pass with soften-land-reinforce buys all of it back:
  orbit superiority over your own world is now a live Corbettian objective (contest it and
  nothing lands), the swarm's own offensive culminates when its 90 days of armaments run
  out with no convoy behind it, and defense in depth cuts both ways - your strata strip the
  swarm's ground exactly as its strata strip yours. The knob
  (`strikeSaturationEnabled`, default false) restores the old behaviour for anyone who
  wants the sector to feel arbitrary and doomed rather than contested; it is not the
  design. See docs/ground-war.md, "Threat ground assaults".

## 4. Naval doctrine: orbit, convoys, interception

- **Corbett, not Mahan.** Mahan wants the decisive battle that sweeps the enemy from the
  sea; Corbett says command of the sea is nothing but control of communications, usually
  local and temporary, and that commerce raiding (guerre de course) forces the enemy into
  disproportionate expenditure [CORB][CORB-USNI]. Our layer is Corbettian: orbit
  superiority over a world, a jump-point intercept, convoys as the thing being fought over.
  The hive has no commerce-raiding behaviour yet - its fleets attack convoys only if they
  happen to meet. If convoys are to matter, the swarm needs a deliberate raider role
  (section 5).
- **Blackett's convoy mathematics.** WWII operational research found that ships sunk per
  attack were roughly constant regardless of convoy size, that a large convoy was barely
  easier to find than a small one, and that losses fell with the number of escorts;
  therefore a few large, well-escorted convoys beat many small ones, and adopting that
  cut losses sharply [BLACK-NG][BLACK-OR]. Design implications for ThreatConvoys: (a) the
  default should be fewer, larger convoys - the current one-convoy-per-base-per-tick with
  600-marine / 1,500-unit capacity is on the small side; (b) escort strength should scale
  with cargo value, not be a flat 30 FP; (c) interception should resolve as "constant
  damage per attack" rather than all-or-nothing, so a big convoy loses a fraction and
  arrives - which the "deposit what is still aboard" rule already supports.

## 5. Feedback loops and the gotchas that apply to us

- **The player-side positive loop is intentional.** Starving a hive makes it weaker,
  which makes it cheaper to take: a positive feedback loop for the attacker, with no
  negative loop on the hive side except reinforcement (Mutual Defense) and spread. That
  is the classic snowball [FB-1][FB-2]. It is acceptable in a single-player game where the
  player is meant to eventually win - but it interacts badly with the next point.
- **No escalation means no tension after the first victory.** AI War's AI Progress is
  the canonical negative loop against the player: winning raises the stakes [AIWAR-INT].
  Options for us, in order of how much they respect transparency: (1) the swarm answers a
  lost colony by redirecting strikes at the faction that took it - visible, causal, no
  rubber band; (2) eradications raise the hive's seeding tempo or garrison scale on a
  displayed "alarm" meter; (3) hidden difficulty scaling - which Johnson's transparency
  rule says not to do [SJ-AI]. Recommend (1) plus a displayed (2) as a knob, and decide
  this before outposts, because outposts are exactly what an escalating swarm would
  attack.
- **Death spirals.** Adams's downward spiral is a positive loop that diminishes the
  loser; his remedies are repair mechanisms, victory defined outside the loop, and not
  transferring losses straight to the opponent [ADAMS-DS]. The hive has repair (organs
  recover, garrisons rebuild) and its losses are not handed to the player, so it is not a
  pure spiral. Factions in war mode DO spiral: a struck faction's reserves fund sorties
  that draw down reserves that a lost convoy never replaces. That is by design, but watch
  for the degenerate case where a small faction mobilises, empties its depots in one
  expedition and then does nothing for the rest of the game - a minimum peacetime accrual
  or a stand-down that refills would be the repair mechanism.
- **Chores.** The moment the player has to click Stage every month, the layer has failed
  (Distant Worlds' whole premise [DW]). Keep the planner autonomous and make the Stage
  button an exception, not a routine.
- **Legibility of asymmetric rules.** Root works because every faction's different rules
  are still expressed through one shared language of pieces and clearings [ROOT]. Our
  shared language is the war board: hive and faction views should use the same columns
  where the concept is the same (defense figure, strength, ETA, supply) so the asymmetry
  reads as different goals, not different UIs.

## 6. The ending

Johnson: the failure mode of every 4X is the late game - either a surprise ending or
"clicking through to victory"; Old World's answer is a double condition (ambitions AND
points) that makes the end legible and earned [OW-END]. Eradicating hives one by one is
the clicking-through case. Before the strategy layer grows further it is worth deciding
what the war's END is: a home hive whose destruction ends the incursion, a seeding source
that can be cut, or a phase-3 armada that must be met. Whatever it is, it should be
visible on the board from early on (the phase bar is the natural place) so the player
knows what "winning" means and can see the swarm's progress toward its own end state.

## 7. What to verify or decide before continuing

1. **In-game check of everything built since 0.5.2** - nothing from the ground war or the
   strategy layer has rendered yet. Nothing below matters until that is done.
2. **Escalation** (section 5): does the hive answer success? Decide the mechanism and
   make it visible.
3. **Convoy shape** (section 4): fewer/larger/escorted by cargo value; a hive raider role
   so interception is a real contest, not a coincidence.
4. **Logistics to fronts** (section 3): convoys that resupply a front, for NPC and player
   alike, so culmination is a supply story.
5. **Combat non-linearity** (section 3): keep linear for now; revisit only if fights feel
   flat, and then as a single super-linear knob.
6. **The ending** (section 6): define it, show it.
7. **Faction coordination** (section 1, Stellaris lesson): nothing yet makes two mobilised
   factions act together; a shared "coalition" target or the player's board as the
   coordinator is the obvious fit for "coordinate from one screen".
8. **Outposts** (deferred request) read well against all of this: a forward station on a
   purged world is Corbett's local control and a shorter supply line (a convoy depot that
   moves the culminating point forward), and it is the natural thing an escalating swarm
   attacks. Build it after 2 and 3 are decided, with the station type by faction as the
   user described.

## 8. Filling the gaps - concrete plans

Each plan: what the code does today, the mechanic, where it hooks in, the knobs, and
what the player sees. All of it is knob-gated and defaults to on unless noted. Sizes:
S = a day, M = a few days, L = a week-plus of building.

### 8.1 Escalation - the swarm answers who hurts it (M)

**Today.** `IncursionManager.tryStrikes` picks targets by fuel reach and size
(`pickStrikeTarget`); phases are capability-based (`getPhase`). Nothing remembers who
took a stratum or eradicated a hive.

**Mechanic: grudge and alarm, both visible.**
- Per-faction GRUDGE (persistent map factionId -> points): +`alarmPerStratum` when a
  front of that faction takes a stratum, +`alarmPerEradication` on a ground victory,
  +`alarmPerRaid` for a successful commando raid or tactical pass; decays
  `alarmDecayPer30` per 30 days. Player and NPC alike.
- Hive ALARM = the sum of grudges. Two levers only:
  1. **Targeting**: in `pickStrikeTarget`, a candidate world's weight is multiplied by
     `1 + grudge(faction) x alarmTargetMult`. The swarm turns on whoever is hurting it.
  2. **Tempo**: strike cooldown and seeding cadence divided by `1 + alarm x alarmTempoMult`.
- **Retaliation** (`retaliationEnabled`): on a ground victory, the nearest surviving hive
  within reach of the winner's nearest colony launches a strike at it immediately if it
  has the garrison to spare (`launchStrike` with an explicit target). Causal, instant,
  legible - the AI War lesson without a hidden number.

**Hooks.** `ThreatGroundFronts.takeStratum` / `groundVictory` (front.factionId),
`ThreatPurgeFGI.doCustomRaidAction` (faction), `IncursionManager.tryStrikes`,
`pickStrikeTarget`, `trySpread`; a `ThreatIncData` grudge map with `clearSystem`
untouched (grudges outlive systems).

**Player sees.** An "Alarm" figure in the board header beside the phase bar, with the
formula in its tooltip; a "Swarm grudge" line in the faction view heading and in the
hive ledger's reason chip ("RETALIATION" when a strike is a grudge strike). The vitality
condition tooltip on a hive world does NOT show grudge (fog of war rule).

### 8.2 Convoys - fewer, larger, escorted, and hunted (M)

**Today.** `ThreatConvoys.planLogistics`: one convoy per base per slow tick, capacity
600 marines / 1,500 units, escort 30 FP flat; a convoy is attacked only if a hostile
fleet happens to meet it; ship losses in an off-screen battle do not reduce the cargo.

**Mechanic, following Blackett.**
- **Size to the shortfall**: load = min(shortfall, `convoyMaxMarines` 2,000 /
  `convoyMaxCargo` 6,000); `convoyMinLoadFraction` up to 0.5 so nothing small sails.
  Transports and freighters are already sized to the load.
- **Escort by cargo value**: escort FP = `convoyEscortBaseFP` + value x
  `convoyEscortPerThousand`, value = marines x 1 + armaments x 0.5 + fuel x 0.1 +
  supplies x 0.1 (weights as knobs). A rich convoy is a real fleet.
- **Losses are fractional**: `ThreatConvoys.poll` trims cargo to what the surviving hulls
  can carry (`getSpaceUsed` over `getMaxCapacity`, marines over free crew space) after any
  off-screen fight, so a mauled convoy arrives light rather than either intact or gone -
  Blackett's constant-loss-per-attack. On-screen fights already do this through vanilla.
- **The raider role** (`raiderEnabled`): when a convoy sails, every hive colony within
  `raiderRangeLY` of the route's midpoint with a garrison above its defensive reserve
  (`garrisonAvailableForLaunch`) rolls `raiderChance`; on success it detaches ONE swarm
  (`consumeGarrison(colony, 1)`) with `FleetAssignment.INTERCEPT` on the convoy fleet for
  `raiderDays`, then `GO_TO_LOCATION_AND_DESPAWN` home (returning it to the garrison via
  the existing reinforcement-arrival path). Corbett's guerre de course, paid for out of a
  real garrison - a hive that raids is a hive that is thinner at home.

**Hooks.** `ThreatConvoys.dispatch` / `poll`; `ThreatColonyManager.garrisonAvailableForLaunch`,
`consumeGarrison`, `checkReinforcementArrivals`; a new `raiders` persistent list.

**Player sees.** Convoy row status "hunted" with the raider's origin; an "Interdiction"
op on the hive ledger row (outbound) so Guard/Intercept have a reason to exist; the
convoy tooltip quotes escort FP and cargo value.

### 8.3 Logistics to fronts - culmination as a supply story (L)

**Today.** A front's armaments are what landed with it; NPC fronts wither when dry;
player fronts are resupplied only by docking in person; a withdrawn NPC front is simply
gone.

**Mechanic: the front is a reserve consumer.**
- **Supply runs**: the convoy planner treats a friendly front (`front.factionId`) as a
  destination. Target = `frontResupplyDays` (60) x the burn of the army the run leaves
  behind (current marines, or `frontReinforceFraction` of peak if higher) armaments on
  hand, plus marines to bring the front back to `frontReinforceFraction` (0.8) of its
  landing strength. Donor = the faction's nearest staging base (its reserve, which colony
  convoys refill) - a two-hop chain, colony -> base -> front, each hop interceptable.
- **The orbit gate**: a supply run must survive the world's Defense Swarms. The convoy
  goes to the hive system's jump-point (`ORBIT_PASSIVE`) and waits up to
  `frontRunWaitDays` (30) for `orbitContested()` to clear, then runs in; on arrival
  `ThreatGroundFronts.resupply(front, marines, armaments)`. If the wait runs out it turns
  home on the tracked return leg. A garrisoned hive therefore strangles the front - the
  culminating point arrives because the convoys stopped, not because a timer said so.
- **Withdraw to fleet** (the backlog item): a run with empty holds; on arrival with the
  orbit clear, `withdraw()` puts the survivors and materiel aboard and the convoy carries
  them home into the base reserve, where a later Siege can land them again. NPC fronts use
  the same run when their stance AI decides the campaign is lost (armaments dry AND
  strength below grind).
- **Player**: a **Supply** button on the hive card and on the faction view's hive row
  (own faction only) orders a run from the nearest base's reserve; the Ground operations
  dialog stays as the in-person route.

**Hooks.** `ThreatConvoys.Convoy` gains `toFrontMarketId` and `pickup`; `poll` gets a
front-arrival branch and the jump-point wait; `ThreatGroundFronts.resupply` / `withdraw`
exist; `tickFront` NPC stance AI gains the "call for withdrawal" branch;
`ThreatFactionView` and `ThreatWarBoard.buildCard` buttons.

**Player sees.** Front card line gains "supply run ~N d" / "run waiting - orbit
contested"; the war board forecast column already reads "core ~N d" and will now go
"contested" when supply is cut.

### 8.4 Combat non-linearity - one knob, default off (S)

**Today.** Push pace = base days x (defense / strength) clamped 0.5-3; counter-attack =
attack > defense; overrun at 2:1. Attrition is a flat fraction. All linear.

**Mechanic.** `groundStrengthExponent` (1.0): every strength RATIO the ground war
computes is raised to it before use - push pace ratio, counter-attack comparison
(attack^e vs defense^e is the same as ratio^e), overrun test, and the push casualty
fraction scaled by (defense / strength)^e so the weaker side bleeds proportionally more.
At 1.0 nothing changes; at 1.5-2.0 concentration pays the way Lanchester says it should.
The dialog's "~N days, ~M casualties" stays exact because the formula is deterministic.

**Hooks.** `ThreatGroundFronts.pushDaysEstimate`, `tickFront` (push progress),
`hiveCounterAttack`, `pushCasualtyEstimate`.

**Recommendation.** Ship at 1.0, revisit after the in-game check if fights feel flat.

### 8.5 The ending - both sides' win conditions on the phase bar (M, needs a decision)

**Today.** `ThreatIncursionIntel.isEradicated`: no infested system, no seeding swarm, no
expedition in flight. The swarm has an origin (`ThreatIncData.KEY_OG_SYSTEM`) but it is
just where seeding started. There is no defined Threat victory.

**Proposal.**
- **The Origin Hive is the objective.** While the origin colony's Fabrication Core lives,
  cleansed systems can be re-seeded and the swarm spreads. Its ground victory
  ("Origin breached") ends the incursion's ability to spread and strike; surviving hives
  become remnants - still colonies, still bounties, no strikes, no seeding, no growth -
  and the board declares "Incursion broken". Mop-up is optional, which is Johnson's
  answer to clicking through. (No timer on the remnants; the user's rule stands.)
- **The Threat's own end state, visible**: `threatVictoryCoreWorlds` (3): when that many
  size-6+ inhabited worlds have been decivilized by the swarm, the phase bar reads "Core
  worlds falling" and the sector's factions get a final mobilisation (every faction in
  reach of a hive enters war mode). Not a game over - Starsector has none - but the
  swarm's progress toward it is on the bar from day one, beside the player's.
- **Phase bar** becomes two tracks: the swarm's (Awakened / Strike-capable / Core worlds
  in reach / Core worlds falling) and the sector's (Origin unknown / Origin located /
  Origin breached / Incursion broken).

**Hooks.** `IncursionManager.trySpread`, `tryStrikes` (gate on origin alive),
`ThreatColonyManager.eradicate` (origin check), `ThreatWarBoard.addHeader`,
`ThreatIncursionIntel.isEradicated`, `ThreatIncData.decivTargets` counting.

**Decision needed.** Whether the origin should be a fixed, findable system (it is today)
or whether the hive can MOVE its origin (a new "seat" when the old one is threatened),
which is harder to win against and more like a real adversary. Recommend fixed for the
first version.

### 8.6 The faction spiral - depots that never empty to nothing (S)

**Today.** Reserves seed with 3 months, accrue only from production, and an expedition
draws up to its full want; a small faction can spend its whole depot on one sortie and
then postpone forever.

**Mechanic.**
- `reserveFloorFraction` (0.25): draws for expeditions and orders never take a base below
  this fraction of its cap - the home garrison's stock. Convoys respect the donor keep
  fraction already. The player's own colonies use `playerReserveFloorFraction` (0,
  2026-09-05 evening): the user's orders may commit the whole stock - "totally fine for
  them to commit their whole regiment if I want them to"; the cost is the colony's own
  ground defence, which counts its stockpiled marines. **Fixed 2026-09-05:** the floor stands on the largest cap the depot
  has banked towards (`ColonyReserve.capSeen`), not the live cap - the live cap is the
  current surplus, which is zero the moment the colony is in deficit, so the floor used
  to vanish exactly when a struck world needed it. The rule-3 shortage cover now
  respects the same floor (it used to write straight through it; Sindria at 0 fuel /
  0 supplies after repeated strikes was this). A colony that never banked a commodity
  has no floor for it.
- **Send what you can**: when a base holds at least `expeditionMinMarinesFraction` of the
  want, the expedition is sized to what it CAN draw (fleet count from
  `siegeFleetSizes` capped by the marines available), instead of always drawing the full
  want. A smaller expedition that sails beats a big one that never does.
- `reserveBaselinePerSize` (5 marines / 30 days per size): every colony trickles a militia
  reserve regardless of industry, so a farming world is not permanently zero.

**Hooks.** `ThreatReserves.draw` (floor variant `drawAbove`), `IncursionManager.launchSiegeExpedition`,
`ThreatReserves.accrualPer30`.

### 8.7 Faction coordination - a light coalition (M)

**Today.** Each mobilised faction acts alone; NPC purges pick the nearest military world
of any faction; nothing times two factions' efforts together.

**Mechanic.**
- **Coalition call**: when any mobilised faction launches a siege against a hive system, a
  60-day call is posted for that system. Every OTHER mobilised faction with a base in reach
  and spare reserves answers with probability `coalitionSupportChance` by sending an
  Intercept task force to the hive's jump-point timed to the siege's ETA (the existing
  `dispatchIntercept`), so the siege lands under cover.
- **Player as participant, not coordinator** (revised 2026-09-05, docs/player-aid.md): the
  Rally button and every order over an NPC navy were removed. The player sends their own
  colonies' fleets as aid and earns standing; allies help each other by standing.

**Hooks.** `IncursionManager.launchSiegeExpedition` (post the call), a persistent
`coalitionCalls` list on the slow tick, `ThreatFleetOrders.dispatchIntercept`,
`ThreatWarBoard.addPurgeButton` neighbour.

### Suggested order - and what happened (overnight 2026-09-04/05)

1. In-game check of everything since 0.5.2 - DONE on the laptop panel (1080p): ground
   war cards, faction selector, faction view, convoys, raiders, front runs, counter-attack
   outcomes all seen live; details in docs/strategy-layer.md.
2. 8.6 spiral floor and 8.4 exponent knob - BUILT (0.6.1).
3. 8.2 convoys and raiders - BUILT and verified (0.6.1, planner fixes in 0.6.2).
4. 8.3 logistics to fronts - BUILT (0.6.2); supply/evacuation runs dispatch and turn back
   correctly, the door-wait and landing paths still need a run to complete in-game.
5. 8.1 escalation - BUILT (0.6.3): grudge, alarm, tempo, targeting, retaliation, header.
6. 8.5 the ending - DEFERRED to a live session (user's call).
7. 8.7 coalition - BUILT (0.6.4), untested in-game.
8. Outposts - BUILT (0.6.4) as standalone faction-styled stations that block re-seeding;
   not yet depots; untested in-game (no purged world in the test save). 2026-09-05: the
   player can build one over any uncolonised world from the planet dialog.

## Sources

- [OW-END] Soren Johnson, Old World designer notes #11 (the game end): https://forums.civfanatics.com/threads/old-world-designer-notes-11-soren-johnson-on-the-game-end.673707/
- [SJ-AI] Soren Johnson on AI transparency (Designer Notes): https://www.designer-notes.com/author/soren/page/22/
- [AIWAR-INT] Co-Optimus interview with Chris Park (AI War): https://www.co-optimus.com/interview/305/page/2/interview-with-christopher-park-ai-war-lead-designer.html
- [AIWAR-3MA] Three Moves Ahead ep. 37, Chris Park and AI War: https://arcengames.com/three-moves-ahead-episode-37-chris-park-and-ai-war-fleet-command/
- [STELL-1] "The end game crisis too boring and one dimensional", Paradox forums: https://forum.paradoxplaza.com/forum/threads/the-end-game-crisis-too-boring-and-one-dimensional.1643580/
- [STELL-2] "The biggest problem with End-Game Crisis?", Steam discussions: https://steamcommunity.com/app/281990/discussions/0/1694914735998228541/
- [SC2] Game Informer interview with Dustin Browder: https://gameinformer.com/b/features/archive/2010/04/08/an-extensive-interview-with-starcraft-ii-design-director.aspx
- [ROOT] Cole Wehrle on Root, Elevation Games interview: https://www.elevation.games/blog/interview-with-cole-wehrle-designer-of-root-john-company-and-pax-pamir
- [UOC] Game Design Round Table #33, Unity of Command with Tomislav Uzelac: https://thegamedesignroundtable.com/2013/06/25/episode-33/
- [HOI4] HoI IV 1.11 logistics overhaul: https://eip.gg/hoi4/news/hearts-of-iron-iv-patch-1-11-logistics-overhaul-no-step-back-dlc/
- [HOI4-CRIT] "New supply system is bad", Paradox forums: https://forum.paradoxplaza.com/forum/threads/new-supply-system-is-bad.1505579/
- [SUPCOM] Wayward Strategy, control of economic processes: https://waywardstrategy.com/2015/11/23/rts-design-thought-control-of-economic-processes/
- [DW] PC Gamer on Distant Worlds 2 automation: https://www.pcgamer.com/distant-worlds-2-is-an-infinitely-complex-space-4x-you-can-play-in-your-sleep/
- [LANCH-ADAMS] Ernest Adams, "Kicking Butt by the Numbers: Lanchester's Laws": https://www.gamedeveloper.com/design/the-designer-s-notebook-kicking-butt-by-the-numbers-lanchester-s-laws
- [LANCH-WIKI] Lanchester's laws: https://en.wikipedia.org/wiki/Lanchester's_laws
- [DUPUY-31] The Dupuy Institute on the 3-1 rule: https://dupuyinstitute.org/2017/12/01/tdi-friday-read-the-validity-of-the-3-1-rule-of-combat/
- [CLAUSE] Clausewitz, On War, Book 7 ch. 5 (the culminating point of the attack): https://clausewitzstudies.org/readings/OnWar1873/BK7ch05.html
- [CULM] Military Strategy Magazine, defensive operations and Clausewitz: https://www.militarystrategymagazine.com/article/theory-to-reality-defensive-operations-confirm-clausewitzs-theory/
- [CORB] Corbett, Some Principles of Maritime Strategy (summary): https://saass.fandom.com/wiki/Corbett,_Some_Principles_of_Maritime_Warfare
- [CORB-USNI] "Revisiting Corbett and Mahan", Proceedings: https://www.usni.org/magazines/proceedings/2021/june/revisiting-corbett-and-mahan
- [BLACK-NG] Naval Gazing, Operations Research in the Atlantic: https://www.navalgazing.net/OR-in-the-Atlantic
- [BLACK-OR] Operations research (WWII convoy findings): https://en.wikipedia.org/wiki/Operations_Research
- [FB-1] Machinations, feedback loops in game systems: https://machinations.io/articles/game-systems-feedback-loops-and-how-they-help-craft-player-experiences
- [FB-2] "Catch Me If You Can: the runaway leader and catch-up mechanics": https://fantastic-factories.medium.com/catch-me-if-you-can-the-runaway-leader-and-catch-up-mechanics-53f0356c440d
- [ADAMS-DS] Ernest Adams, "Preventing the Downward Spiral": https://www.gamedeveloper.com/design/the-designer-s-notebook-preventing-the-downward-spiral
