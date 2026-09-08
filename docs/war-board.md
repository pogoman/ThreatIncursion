# The Abyssal War board (`ThreatWarBoard.java`)

The sector-wide status screen for the incursion. It is the large description of
`ThreatIncursionIntel`, a permanent Major Events entry named "The Abyssal War" that
exists from the incursion's start until `ThreatIncursionIntel.isEradicated()` (no infested
system, no seeding swarm in transit, no expedition in flight). The per-system
`InfestedSystemIntel` entries are hidden (`isHidden()` true) but kept for the commission
machinery and as map anchors.

The board **reads only**. Every figure comes from state the simulation already keeps; the only
new arithmetic is the priority score, "systems within reach", distances, and the hive supply
model. `design/war-effort/Round2.dc.html` is the layout it implements.

## Layout, top to bottom

1. **Header** (custom panel): crest, title in Orbitron, cycle/day line, and the phase bar with
   its three stage labels (Awakened / Strike-capable / Core worlds in reach) driven by
   `IncursionManager.getPhase()`, which is capability-based and can regress.
2. **Totals strip** (custom panel, two rows of four label/value pairs): known systems, hive
   worlds, mass, swarms sighted, expeditions out, sieges in, missions, hives burned.
3. **Ledger**: stock table, one row per known system in priority order. Columns (wide / narrow
   below `NARROW_WIDTH` = 1050): `#`, System, Worlds (sizes largest first, "+n" overflow), Mass,
   Vitality (bar + trend glyph drawn by the overlay), Swarms ("live/desired +mustered", plus a
   glyph: green up = a nexus is growing replacements, red down = a short garrison whose nexus is
   silenced, dash = all garrisons full), Reach,
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
   line A "Vitality n%  Swarms a/b  Reach n ly" (vitality in its health colour; "Swarms a/b of n"
   in red when the hull shortage caps the garrison below the size table's n; "Decline n%"
   replaces reach while declining) at full width - it wrapped when it shared the line; line B
   "Def x  sat y fuel  tac z" with the size forecast right-aligned on the same line ("s5 -> s6
   ~270 d", "s5 -> s4 ~22 d" red while declining, "s8 max", "s5 stalled"); then the organ icons
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

Built by `buildEntries()` for every system in `ThreatIncData.stages()`. `Entry.known` is whether
the player has found it (`ThreatIncData.discoveredSystems()`, or debug mode); an unfound system is
listed with its figures but named "Unknown" in gray, its row tooltip has no sector map, it cannot
be selected for the detail block (its cards would name its planets) and gets no Purge or View
button. Requested Sept 2026 after the debug listing turned out to be the better read. Per entry:
stage, live markets, mass, size-weighted health and trend, swarms live/desired/mustered
(`countLiveGarrison`, `desiredGarrisonCount`, `preparingStrikeFleetCount`), staging colony and
`fuelRangeLY` reach, inhabited systems inside reach, outbound ops (strikes from
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
(+1000 +50 each), living systems inside reach (+200 +40 each), a colony declining (+60), network
impact (`ThreatMissionIntel.networkImpact`, x100; chip HOME HIVE / NETWORK LINK), mass (x8),
proximity to the core (30 - ly). Non-colony stages score low (SEEDING / MARKED).

## Vitality and needs - why 100% vitality with fuel short is correct

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

Siege expeditions, NPC and commissioned alike, are **sized to the target** (Sept 2026,
`IncursionManager.siegeFleetSizes`). Vanilla's raid effectiveness is `raidStr / (raidStr +
defenderStr)` and an industry raid needs `MarketCMD.DISRUPTION_THRESHOLD` = 0.25 of it, so the
landing force must be at least a third of the strongest target's `getDefenderStr` as it will
stand after the tactical pass (`SIEGE_SUPPRESSED_DEFENSE_FRACTION` 0.6 of an intact world's
figure: Nexus bonus halved, batteries at half effect), times `SIEGE_RAID_HEADROOM` (1.25) for
the preparedness bump each raid adds. NPC raid strength is a
quarter of crew capacity (`MarketCMD.getRaidStr`), which tracks fleet size, so fleets are added
to the old baseline shape until difficulty points x `threatinc_siegeRaidStrPerPoint` (43,
measured: 22 points landed 947) clears the need, up to `threatinc_siegeMaxFleets`. The
commission quote shows the estimate against the need and warns when the cap leaves it short.
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
the same per-world figure.

Disrupted defenses **wear** (Sept 2026, `ThreatColonyManager.disruptedDefenseResilience`, used
by `ThreatGroundDefenses` and `SwarmNexus`): a structure's bonus falls linearly with the
disruption days on its clock to nothing at `threatinc_defenseWearDays` (300), and from orbit
alone never below `threatinc_fortificationOrbitFloor` (0.5) - the hive's half of the
fortification rule (2026-09-06; the old half-effect step is gone).
Disruption stacks - tactical passes keep the longer duration, successful raids add theirs - so
repeated raids now lower the defense figure (and with it the raid odds and the bombardment
bill) where before every pass met the same half-effect batteries. The size-anchored base never
wears. Vanilla's own "defender preparedness" bump after each raid still applies on top; the
siege sizing headroom covers it. Vanilla only reapplies a disrupted industry when the
disruption ends or on the monthly economy step, so `refreshWornDefenses` (fast poll) reapplies
any hive world with a disrupted organ - the worn figure, and everything read off
`getDefenderStr` (siege sizing at launch, raid odds, bombardment cost, the card's Def line),
follows the clock daily. Sieges are sized once at launch against the figure then current;
they do not resize en route. The same wear applies to the fabrication half of vitality
(`ThreatColonyManager.wornDownFactor`): a disrupted Core runs at `coreDownFactor` when fresh
and at zero once it carries `defenseWearDays`, so a Core hit again and again drags vitality
below the flat 25 percent it used to floor at.

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
