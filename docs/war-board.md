# The Abyssal War board (`ThreatWarBoard.java`)

The sector-wide status screen for the incursion. It is the large description of
`ThreatIncursionIntel`, a permanent Major Events entry named "The Abyssal War" that
exists from the incursion's start until `ThreatIncursionIntel.isEradicated()` (no infested
system, no seeding swarm in transit, no expedition in flight). The per-system
`InfestedSystemIntel` entries are hidden (`isHidden()` true) but kept for the commission
machinery and as map anchors.

The board **reads only**. Every figure comes from state the simulation already keeps; the only
new arithmetic is the priority score, "systems within reach" (billed reach off), distances, a
system's share of its upkeep paid, and the hive supply model. `design/war-effort/Round2.dc.html`
is the layout it implements.

## Layout, top to bottom

1. **Header** (custom panel): crest, title in Orbitron, cycle/day line, and the phase bar with
   its three stage labels (Awakened / Strike-capable / Core worlds in reach) driven by
   `IncursionManager.getPhase()`, which is capability-based and can regress. Phase 3 also
   requires a strikeable size-6+ world within the armada-capable hive's fuel range
   (`coreWorldInReach`, the same gate `pickStrikeTarget` applies) - so "Core worlds in reach"
   is literally true, and cutting a hive's fuel can push the phase back.
2. **Totals strip** (custom panel, two rows of four label/value pairs): known systems, hive
   worlds, mass, swarms sighted, expeditions out, sieges in, missions, hives burned.
3. **Ledger**: stock table, one row per known system in priority order. Columns (wide / narrow
   below `NARROW_WIDTH` = 1050): `#`, System, Worlds (sizes largest first, "+n" overflow), Mass,
   Fed (Vitality with size upkeep off; bar + trend glyph drawn by the overlay - see "Fed and
   vitality" below), Swarms ("live/desired +mustered", plus a
   glyph: green up = a nexus is growing replacements, red down = a short garrison whose nexus is
   silenced, dash = all garrisons full), Reach (see "Reach" below),
   Strikes (count), Activity (crests, drawn: every siege expedition, task force, intercept,
   Support or Defend sortie, front run and ground front against the system - as many as fit the column, the rest
   in the row tooltip), Supply (commodity icons, drawn), Core (ly, wide only). The
   Threat/reason column was removed for space; the reason opens the row tooltip. The Missions
   (View button) and Actions (Purge button) columns were removed 2026-09-05: missions stay in the
   totals strip and the row tooltip, and the purge commission is gone altogether (below).
   Clicking a row selects the system (`tableRowClicked` with a String id).
4. **Selected system heading**, then that system's **Ground fronts table** (2026-09-05, shared
   with the faction view - `ThreatWarBoard.addFronts(rows, tableFaction, showSystem)`; the
   hive view passes `frontRowsIn(selected)` and no System column, a faction view passes
   `frontRowsFor(factionId)` - its own landings plus the swarm's landings on its worlds - with
   one). Shown only while the selection has a front; other systems' fronts show as crests in
   Activity. Columns, every figure from `ThreatGroundFronts`' readout helpers so the table
   quotes what the tick does: Force (owner in its colour, "You"), World, [System],
   Attackers (effective strength), Defenders (the world's figure), Defenses (worn green / held yellow /
   recovering red / intact - which way the defenders are heading, `defensesTrend`), State
   (holding / grinding / foothold), Held `held/size` (strata on a hive, districts on a colony), Stance (push / regrouping / dug in),
   Attrition ("N / day" - troops a day at the current stance and supply, `attritionPer30Days` over
   `perDay`; every player-facing loss figure in the mod is a rate a day), Counter
   (days to the next counter-attack, green when the front would repel it now,
   `counterAttackRepelled`), Arms (days of armaments, "dry" red), Falls ("~N d" until the last layer falls while
   it can push), Space (who holds the space over the world in their colour, grey "Empty" when
   nothing armed is there - `ThreatGroundFronts.spaceHolder`, the faction with the most armed
   points within `ORBIT_HOLD_RANGE`). The row tooltip is the arithmetic, one line per fact (`addFrontTooltip`):
   strength = troops x entrenchment x supply; defenders and the hold / grind requirements;
   which structures are disrupted and for how long (the defenders' whole "reinforcement");
   armaments, burn and days; losses and why; the next counter-attack's figures and outcome
   (repelled / costs N marines and a stratum / overrun); the push in progress or what one
   would cost; what the front wants brought. Click = the colony screen (`ROW_MARKET`).
   **A front the player can order takes two rows** (user's layout, 2026-09-06): the entry,
   then an empty row - same tooltip, same click - that its buttons float over, left to right:
   **Push** / **Dig in** (whichever the front is not doing - a front that lands able to hold
   is already pushing, `ThreatGroundFronts.autoPush`; `BUTTON_PUSH` / `BUTTON_ENTRENCH`
   -> `ThreatGroundFronts.orderPush` / `orderEntrench`, gated by `pushBlockReason` - holding
   strength - and `entrenchBlockReason`; the confirm quotes days, casualties, losses and burn),
   **Supply** (gated by `ThreatConvoys.supplyBlockReason`, which since 2026-09-05 also refuses
   when the front wants nothing or no base has anything to send - the "wants nothing" gate
   is skipped at the Max load tier, below), **Support** (Escort until
   2026-09-06), **Defend** (2026-09-07: the same station, bombarding only while the front
   cannot hold; `BUTTON_DEFEND` -> `ThreatFleetOrders.dispatchDefend`), **Pull out**. No
   Actions column: the columns share the whole width. `Fronts.buttonRows` holds each front's
   button-row index and `rowCount` the table's total, so `addFrontButtons` anchors a row's
   buttons `belowLeft` of the table panel by `(rowCount - index) * ROW_H`. All route through
   `ThreatFactionView.addOrderPrompt` / `executeOrder`, one code path, for both views.
   The table sits **above** the cards: the last colony card is a repositioned component and
   anything added after one lands beside it (platform trap 2).
5. **Colony cards**, three across wide / two narrow, `CARD_H`
   = 124 px, deliberately thin (Sept 2026): name, with the **Map** button (jumps the map to the
   planet) and **Colony** button flush right (`aboveRight(card, -24f)` with negative x offsets);
   line A "Fed n%  Swarms a/b  Reach n ly" (b is `garrisonTargetCount`, 2026-09-29: the
   posture base count, not the size table's n; Fed is the world's share of its supplies upkeep
   paid in the Fed colours below, "-" below size 3 - "Vitality n%" in its health colour with size
   upkeep off; "Swarms a/b of n"
   in red when the hull shortage caps the garrison below the size table's n; a ground front's
   "Front" figure replaces reach while one is on the ground; Reach only from strike size, see
   "Reach" below) at full width - it wrapped when it shared the line; line B
   "Def x  sat y a day  tac z a day" (2026-10-01: a day of saturation, `satFuelPerFPDay` x your fleet's FP as it stands, and a day of tactical bombardment by it; a hive has no bar to quote - a colony card would read "raze y fuel", the fuel through its shield) with the size forecast right-aligned on the same line ("s5 -> s6
   ~270 d", "s5 -> s4 ~22 d" while starving, "s8 max", "s5 holding" - "s5 stalled" with size
   upkeep off; in the Fed figure's colour, red while a front or saturation takes it); then the organ icons
   along the bottom (industry sprites, red-tinted while disrupted, day
   count beneath), each with a hover tooltip naming the industry, its state, its output and its
   inputs (`industryTooltip`, an invisible `createUIElement` hover target inside the card with
   `addTooltipTo` from the main maker).
   Everything else about a world - commodities with vanilla's full breakdown, accessibility,
   growth, hazard - is one click away on the **Colony** button, which opens vanilla's own colony
   screen: `ui.showDialog(planet, new ThreatColonyScreenDialog(planet))`, whose `init` calls
   `showCore(CoreUITabId.CARGO, planet, CoreUITradeMode.NONE, listener)` - exactly what
   vanilla's "View colony info" option does (`MakeOptionOpenCore ... CARGO` in rules.csv) - and
   dismisses itself when the screen closes. The card's own commodity list, vitality bar, Info
   button and long colony tooltip were removed the same day for that reason: vanilla's UI to
   maintain, not ours.

All floating buttons (Map, Colony, Push / Dig in, Support, Defend, Pull out, Supply) are created after
everything else and anchored to siblings (the table panel, the cards); see platform trap 1 and 2.

**Load ladders** (2026-09-07, `ThreatFactionView.addTierSelector`, docs/player-aid.md): a table
whose buttons move a quantity carries a **Min / Med / Max** selector in a row reserved under its
section heading (`addSpacer(SELECTOR_ROW_H)` before `beginTable2`; the buttons float in last,
`aboveLeft` the table). Three: **Siege force** over the hives table, **Supply run** over the
ground fronts, **Convoy load** over the colonies. The tier is a view toggle like the faction
selector - no Confirm, the current tier's button drawn disabled - stored on the intel and read
by every order button in the table below, so a row's tooltip and its Confirm quote the tier's
own numbers. The fronts ladder only appears where a front of the player's carries orders
(`Fronts.tierSelector`). No ladder on the fleets or purged-worlds tables: nothing there moves
a quantity the player chooses.

**Faction selector** (Sept 2026, `ThreatFactionView`, docs/strategy-layer.md): once any faction
has mobilised, a row of buttons sits between the strip and the ledger - "The Threat" and one per
mobilised faction. Choosing a faction replaces the ledger and cards with that faction's colonies,
reserves, fleets and orders; the hive view above is untouched. Built without an in-game check.

## Data model (`Entry`, one per hive system)

Built by `buildEntries()` for every system in `ThreatIncData.stages()` the player has found
(`ThreatIncData.discoveredSystems()`, or debug mode). An unfound system is not listed at all and
counts toward none of the strip's totals, and while nothing is found the board itself is off the
intel list (`ThreatIncursionIntel.isHidden`). User's rule 2026-09-25, replacing the earlier gray
"Unknown" rows. Per entry:
stage, live markets, mass, the share of its supplies upkeep paid (`fed`; size-weighted health
with size upkeep off), whether a world starves and the days to the first size lost, the trend,
swarms live/desired/mustered
(`countLiveGarrison`, `garrisonTargetCount`, `preparingStrikeFleetCount`; 2026-09-29: "desired" is
`garrisonTargetCount` - the size table's `desiredGarrisonCount` with posture off, posture's base count
(reserve plus a forge's launch stock, docs/hive-garrison-and-upkeep.md "Posture") with it on, so a quiet colony
shows full at its lean target and the fabrication trend arrow reads the same count) - all counted in
fleets since 2026-09-29: a garrison fleet grown past one swarm (docs/hive-economy.md "Grown garrison
fleets") counts once, and `preparingStrikeFleetCount` is the strike's packed fleets, not its swarms -
staging colony and - billed reach - the faction and light-years of the world the system would
strike first (`ThreatReach.facedFaction` / `facedLY`) and whether it is grounded, or - billed reach
off - `fuelRangeLY` reach and the inhabited systems inside it, outbound ops (strikes from
`IncursionManager.getStrikeList()` where `params.source` is here; seeding swarms from
`SeedingSwarmIntel`), inbound ops (`getPurgeList()` sieges targeting the system,
`getResponseList()` task forces targeting a market here), open missions (`ThreatMissionIntel`
accepted then posted), distances to the nearest size-6 non-Threat non-player world and to the
nearest player colony, the commodities produced, and the priority score.

`Op.status` uses a fixed vocabulary from the fleet group's current action:
"mustering - recall" (strikes still at the staging colony; `getETAUntil(TRAVEL_ACTION)`),
"en route" (`getETAUntil(PAYLOAD_ACTION)`), "bombarding"/"engaging", "withdrawing".

## Priority score (`score(Entry)`)

Most urgent first, each contributor also names the reason chip: strike(s) in flight from here
(+1000 +50 each), living systems inside reach (+200 +40 each; billed reach: CAN STRIKE +200 flat, below), a
colony declining or starving (+60; chip DECLINING, or STARVING with the days to the first size
lost), network impact (`ThreatMissionIntel.networkImpact`, x100; chip HOME HIVE / NETWORK LINK),
mass (x8), proximity to the core (30 - ly). The fallback chips are FOOTHOLD, STALLED (HOLDING
under size upkeep) and ENTRENCHED. Non-colony stages score low (SEEDING / MARKED).

Under billed reach every world is in reach of a trip the hive pays for, so a count of systems
"in reach" means nothing and IN REACH is gone. Its tier goes to **CAN STRIKE** (+200, no
per-system term): a staging colony, not grounded, and a known world to strike. Without it a big
grounded system would rank level with one that can launch.

## Fed and vitality

Under size upkeep (`ThreatColonyUpkeep.enabled()`, 2026-09-30) the Vitality column is **Fed**:
the system's share of its supplies upkeep paid at the last feed, weighted by each world's bill
(`perMonth(size)` x `fedShare`), one tick at the break-even share (`upkeepBreakEven`, 0.5). It
reuses the reserve key: white paid in full, yellow short of it, red below break-even
(starving), grey "-" with nothing billed (every world below size 3). The glyph points down while
a world starves or a front or saturation takes one, up while one grows, a dash otherwise. A
world's state (`ThreatWarBoard.fedState`) is read off the engine's clocks: starving while
`daysToLoseSize` runs, growing while `daysToNextSize` runs, else holding (at break-even, at its
cap, or held by a front or saturation). The row tooltip adds "fed n%" to its title, a line of
how many worlds grow, hold and starve, and one line per starving world (the size it falls to,
the days). `InfestedSystemIntel`'s Hive Status and
`ThreatSiegeReportIntel`'s current state grade the same way ("starving, fed 30%"), and
`HiveVitalityCondition` drops its vitality line and the Core's worn figure. A hive's
counter-attacks pace on min(1, fed / break-even) x (size - strata held) / size
(`ThreatGroundFronts.hiveCounterAttackPace`, floored at 0.25 as before; no Core factor).

With size upkeep off everything reads vitality, as below.

### Vitality and needs - why 100% vitality with fuel short is correct

`computeHealth` = fabrication (organs on/off) x supply, and supply averages the growth inputs
(`ThreatColonyManager.growthInputs()`: ore, metals, heavy machinery, volatiles, plus rare ore
and rare metals whenever the incursion runs a rare economy), each weighted by the fraction of
its demand met. The same list feeds the half-fed growth gate. Rare inputs used to be excluded
from the average and bite only on total cutoff; dropped Sept 2026 at the user's call ("a
shortage is a shortage") - a hive seeded on a poor rare deposit is a little short for good and
the number says so. Fuel and hulls are outside it: fuel sets reach and launches, hulls the
garrison - which is why a card can read "Vitality 100%" beside "Swarms 3/3 of 5". The per-commodity
detail lives on vanilla's colony screen (Colony button); the only commodity icons the board
still draws are the ledger's Supply column and the organ tooltips, both filtered to `RELEVANT`
so vanilla goods the hive does not run on (drugs, supplies, organs, ...) never appear, and
the organ tooltips show units on hand then a faded red stack of units missing.

## Hive supply model (`HiveSupply`)

Computed once per render over the whole hive (known or not, like the bounty boards): per
relevant commodity, total production (`getMaxSupply`) and demand (`getMaxDemand`). Only demand
is read: a colony's output that no hive world wants is drawn dim. The Supply column and the
row tooltip show every commodity a system produces, as icons, nothing more. Shares of a hive
total, "largest known producer" green frames and the key-supplier chip were removed in Sept
2026: vanilla's economy is not a flow, one producer feeds every hive world that can reach it,
so a share of a total says nothing about who feeds whom - see [hive-economy.md](hive-economy.md).
What a player can read off the column is how many known systems make each resource; one is
the hive's weak point.

## Missions and sieges

Missions come from `ThreatMissionIntel` (the bounty-to-mission conversion, done in a separate
session); the board shows their count in the totals strip and lists them in the row tooltip.
The Missions column's View button was removed 2026-09-05.

**The purge commission is gone** (2026-09-05). The board's Purge button and the commission
section on the infested-system map icon were a second UI over `launchSiegeExpedition` with
their own base pick (`findPlayerExpeditionBase`) and quote, and the button did not consult
`siegeBlockReason`: it offered an 8-fleet expedition from a colony with 0 FP free. The one
way to raise a siege is the faction view's **Siege** button, gated and quoted by
`IncursionManager.siegeBlockReason` / `siegeWants`. NPC navies siege only while
**mobilised** (`tryPurgeBombardments` skips bases of factions not in war mode): a faction the
swarm has never struck keeps no reserve, so it launches nothing - no expedition from nowhere.
`commissionEnabled` was removed from the config.

**Bombard** (2026-09-28, built, untested; docs/suppression-balance.md v2 section 9) sits between
Siege and Hunt on each hive row: a saturating expedition, the player's twin of the NPC raze task.
It takes the system's hives with a structure for saturation to fall on and no front
(`IncursionManager.bombardTargets`), sizes its fleets to outlast their guns in turn
(`bombardFleetSizes`, `razeRun` over `ThreatGroundFronts.razePlan`), fits them to the base's free
points like a Siege, and carries the passage then the saturation's fuel - what each stay to the
commander's stop burns - from the base's own reserve, less if that is all there is. A hive has no
bar (2026-10-01, docs/ground-war-sieges.md "Saturation presses a hive"): the order presses each hive and
never ends one. The confirm: "Order a bombing expedition from B against the S?", the fleets and
their FP, "Carries X of the Y fuel the saturation takes.", the fuel drawn of the reserve, and per
hive "Name: N days in orbit, S FP bombing, about F FP lost, down D days." The launch notice is "Bombing
Expedition" ("To saturate ... from orbit"); the fleets table reads "bombarding the ...". Gated by
`bombardBlockReason`; refused with a "Bombing Refused" notice. To fit the third button the hives
table went from System .30 / Actions .21 to .26 / .25. The Siege prompt also quotes the fuel its
bombardment burns.

Siege expeditions, NPC and commissioned alike, are **sized to the target** (Sept 2026,
`IncursionManager.siegeFleetSizes`). Vanilla's raid effectiveness is `raidStr / (raidStr +
defenderStr)` and an industry raid needs `MarketCMD.DISRUPTION_THRESHOLD` = 0.25 of it, so the
landing force must be at least a third of the strongest target's `getDefenderStr` as it will
stand after the tactical pass (`ThreatGroundFronts.bombardPlan`: the defence left when the
flotilla's commander stops bombarding, since 2026-09-28), times `SIEGE_RAID_HEADROOM` (1.25) for
the preparedness bump each raid adds. NPC raid strength is a
quarter of crew capacity (`MarketCMD.getRaidStr`), which tracks fleet size, so fleets are added
to the old baseline shape until difficulty points x `threatinc_siegeRaidStrPerPoint` (43,
measured: 22 points landed 947) clears the need. The flotilla grows until it clears it
(2026-09-29: it stopped at `threatinc_siegeMaxFleets`, and that cap alone blocked 814 of 1,284
siege attempts in a test - the knob is gone). What decides whether it sails is the depots' pooled
provisions (an NPC) or the base's free fleet points (the player's, which fits the flotilla to
them); the commission quote shows the estimate against the need.
Before this, a difficulty-sized flotilla against Gamma Gibidigi I (defenses 3,000) landed 947
against the 1,000 needed and had both raids repulsed - the fee bought one tactical pass.

Expedition **reach is fuel and fleets** for every side (Sept 2026,
`ThreatColonyManager.fuelRangeLY`, which `IncursionManager.expeditionRangeLY` delegates to):
`strikeLYPerFuel x min(fuel available, expeditionFuelCapacity)`, where the capacity is
`threatinc_reachFuelCarry` (4 units at a fleet-size figure of 100 percent) times the colony's
fleet-size figure: vanilla's own `Stats.COMBAT_FLEET_SIZE_MULT` untouched for faction and player
worlds (the "Fleets" percentage on the colony screen), and hive vitality x size / 4 for hive
worlds. All the fuel in the sector is no use to a colony that only fields small fleets, so a
healthy size-4 hive world seeds 20 ly, a size-2 foothold 10, a size-8 world the fuel-bound 25,
while a size-8 Hegemony High Command world at 250 percent strikes as far as its fuel allows. The
flat `threatinc_responseRangeLY` (20) is gone. The mission board's faction-reach weighting uses
the same per-world figure. For hive worlds this radius holds only with billed reach off.

**Reach** on the board under billed reach (`ThreatReach.enabled()`, 2026-09-30; the hive has no
radius, docs/hive-reach-and-stock.md "Reach is the bill"): the light-years to the world the system would
strike first (`ThreatReach.facedLY`, rounded up) in that world's owner's colour (`facedFaction`).
"grounded", in the good colour, when the hive cannot keep one swarm away
(`ThreatReach.canSustain(ThreatPosture.oneSwarmFP(...))` false) or its fuel stock is empty
(`ThreatFuel.stock() <= 0`) - judged on the staging colony or, with none (an empty stock leaves
`pickStrikeStaging` nothing), the biggest world of strike size (`ThreatWarBoard.swarmSource`).
"-" when no colony here can stage, or it knows no world to strike. The card shows the same for
each world of strike size, grounded on that world's own swarm; the row tooltip names the
faction ("First target: Hegemony, 12 ly") or why it is grounded; `InfestedSystemIntel` says it
in words. The column took a point of width from Activity to fit "grounded" (the crests Activity
fits did not change). With billed reach off the old radius shows, red while a living system lies
inside it.

Disrupted defenses **wear** (Sept 2026, `ThreatColonyManager.disruptedDefenseResilience`, used
by `ThreatGroundDefenses` and `SwarmNexus`): a structure's bonus falls linearly with the
disruption days on its clock to nothing at `threatinc_defenseWearDays` (300), orbit included -
the orbital floor that used to stop bombardment at half effect is gone (2026-09-28).
Disruption stacks - tactical passes keep the longer duration, successful raids add theirs - so
repeated raids now lower the defense figure (and with it the raid odds and the bombardment
bill) where before every pass met the same half-effect batteries. The size-anchored base never
wears. Vanilla's own "defender preparedness" bump after each raid still applies on top; the
siege sizing headroom covers it. Vanilla only reapplies a disrupted industry when the
disruption ends or on the monthly economy step, so `refreshWornDefenses` (fast poll) reapplies
any hive world with a disrupted organ - the worn figure, and everything read off
`getDefenderStr` (siege sizing at launch, raid odds, bombardment cost, the card's Def line),
follows the clock daily. Sieges are sized once at launch against the figure then current;
they do not resize en route. With size upkeep off the same wear applies to the fabrication half
of vitality (`ThreatColonyManager.wornDownFactor`): a disrupted Core runs at `coreDownFactor`
when fresh and at zero once it carries `defenseWearDays`, so a Core hit again and again drags
vitality below the flat 25 percent it used to floor at.

**Raid doctrine** (Sept 2026, `ThreatPurgeFGI.pickRaidTarget` / `raidValue`): each commando
raid lands on the industry worth most on that world right now. Fabrication Core 100 (the kill),
Swarm Nexus 60, port 12 per growth input the world ships in (the trickle rule starves it), and
economy industries by what the hive loses: availability is best-single-source, so a mine,
refinery, fuel plant or forge scores only when this world is the hive's largest producer of
something another hive world wants - weight (fuel and hulls 6, metals 4, ore/rare ore/volatiles
3, machinery 2) x the gap to the second-best producer, doubled when there is no second. One of
ten equal mines scores zero and is left alone; the only fuel plant is hunted. Anything already
carrying a day or more of disruption is skipped so damage spreads across a world's organs
rather than stacking on one; when everything is down the organ closest to recovering is hit
again. Replaced the fixed Nexus-Core-port-forge order, under which every raid after the
tactical pass went to the Core and the economy was never touched.

The sitrep (`ThreatSiegeReportIntel`) posts from `notifyActionFinished` when the payload action
completes, i.e. as the fleets turn for home, and each action carries "N days ago". Posted on
return, it arrived after every clock it described had run out - a Core raid of ~15 days beside a
colony already nominal again read as "the Core was never disrupted" (Gamma Gibidigi I, Sept
2026). Aborted or destroyed expeditions still report from `notifyEnding`. Raid disruption
durations are vanilla's (`doIndustryRaid`, scaled by `punitiveExpeditionDisruptDurationMult`) and
were deliberately left alone: a 90-day floor was added and reverted the same day, as a balance
change nobody asked for.

While a siege is running, the expedition's own intel carries a **Status** readout
(`ThreatPurgeFGI.addStatusSection`): one line per surviving target - what is being done to it
("softening defenses" / "landed N troops" / "raiding", from `needsSoftening` and the world's
front) and the passes spent of `raidParams.raidsPerColony` (`FGRaidAction.getRaidCount`) - and,
for a cargo expedition, the marines and heavy armaments still aboard. The per-target lines show
only while the raid action itself is current; the cargo line shows from launch.

## Things not done / open

- The narrow (1080p, 997 px) fold has not been re-checked since the cards grew.
- No sort toggles; one fixed priority order with the reason in the row tooltip.
- Operations no longer have a table of their own; they live in the row tooltip.
- An NPC faction view shows its war council: a strategy line under the heading
  (`ThreatWarCouncil.strategyLine`), and one row per running play at the top of the fleets table
  (`ThreatFactionView.fleetRows`; kind, target, phase, planned FP, days to the next check).
- A faction view's fronts table names worlds in systems the player has not discovered (any
  landing of that faction shows), the one place the board breaks the fog-of-war rule in
  [intel-ui-platform.md](intel-ui-platform.md) trap 8. Asked for that way; revisit if it reads
  as a leak. (The hive view's copy follows the selected system, which is always found.)
- The reworked fronts table (2026-09-05: per-system, new columns, faction view copy; 2026-09-06:
  the button row under each of the player's fronts) has not been seen in-game - javac only.
  Check first: the four buttons sit centred on the blank row under the right entry (with and
  without the System column, with several fronts, with a Threat front above a player one);
  the header tooltip indices; the table's place between the heading and the cards. A row-menu
  dialog (`ThreatOrdersDialog`) replaced every button for one evening and was reverted the
  next morning: buttons are the user's choice.
- Round-one text mockups in `design/war-effort/` page 1 are superseded; page 2 is current.
