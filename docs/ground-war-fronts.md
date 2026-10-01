# Ground war - the front engine and the board

Split out of `ground-war.md` on 2026-10-01. One-line answers: `facts.md`.

## The model

**The colony is the fortress; the Core is the objective.** A size-S hive is S strata
deep with the Fabrication Core at the center. There is NO decline timer any more -
the old health-below-threshold decline engine is removed (rejected again 2026-09-28).
**A hive dies one way** (2026-10-01): a ground victory - take every stratum, destroy the
Core (`ThreatGroundFronts.groundVictory` -> `ThreatColonyManager.eradicate`). Saturation
never razes a hive: it presses it, every structure worn a day at a time ("Saturation presses
a hive" below); a human colony can still be razed down to its last level (2026-09-28,
`ThreatRazing`, "Bombardment v2" below). Starvation shrinks a hive and bombardment weakens it;
neither kills. This holds for NPCs too - purge expeditions land
their own fronts (below) - and, since 2026-09-05, **for the swarm as well**: Threat
strikes land Threat-owned fronts on inhabited worlds and can only kill a colony by
taking its last stratum ("Threat ground assaults" below). The rule is symmetric.

## The front (ThreatGroundFronts)

Marines + heavy armaments landed on a hive world ("Ground operations" on the military
options menu; commits everything aboard). Ticks on the colony poll at flat rates:

- **Supply**: armaments burn per MARINE per 30 days (`frontArmamentsPerMarinePer30Days`,
  0.25 - x`frontPushUpkeepMult` while
  assaulting). The stockpile IS the countdown; a dry front fights at
  `frontDryEffectivenessMult` (0.5) effectiveness AND attrits at
  `frontUnsuppliedLossMult` (3x) - "not enough armaments" is exactly the
  reinforce-before-pushing moment.
- **Effectiveness** = troops x entrenchment x supply factor, vs the world's CURRENT worn
  defense figure (`MarketCMD.getDefenderStr`). **Entrenchment is the attacker's cover, and
  it is earned** (user, 2026-09-06): a landing fights at `frontLandingMult` (0.75) and digs
  in to `frontEntrenchMaxMult` (1.0) over `frontEntrenchDays` (30) of holding - the ramp is
  frozen while pushing - and taking a layer or being battered by a counter-attack keeps only
  `frontEntrenchKeptFraction` (0.5) of it. Pushing costs cover, holding earns it. The
  defenders have no entrenchment number: their ground-defense figure IS their entrenchment.
- **Suppression states**: HOLDING (>= `frontHoldFraction`, 0.17, of the defense figure -
  lowered from 0.25 when the 1.5x entrenchment bonus went, so the old landing sizes still
  hold) suppresses Core/Nexus/port/defense structures by feeding their disruption clocks
  (the existing wear mechanic); GRINDING (>= `frontGrindFraction`, 0.10) wears just
  the defense structures; FOOTHOLD suppresses nothing. Either wears by its advantage,
  `frontWearRate` (12) x troops / (troops + defence) days a day (2026-09-28).
- **What drives a disruption clock** (2026-09-28, "Bombardment v2" below): orbit, a day
  of bombardment at a time - a Threat strike over a colony, a faction's or the player's
  siege expedition over a hive, a Support or Defend sortie over either, and the player's
  own bombardment all fly the same day - adding the theatre's rate x F / (F + D) x what
  still stands of each fortification, so it softens with diminishing returns and never
  wears anything out. A front adds `frontWearRate` (12) x E / (E + D) a day, not cut by
  condition - boots finish what orbit cannot - against the clock's own run-down of a day a
  day, up to a cap of 1.2 x the wear days. No raid reaches a fortification any more;
  on a hive only the Fabrication Core is raidable. Danger close (a tactical day with your own front down) lands the day on the
  Core and port too.
- **The Core wears in proportion** (2026-09-05): its fabrication factor is `coreDownFactor`
  (0.8 since 2026-09-28; at 1.0 a Core under ~45 days still grew at full pace) x
  (1 - clock / `defenseWearDays`) - one pass leaves 64 percent, 150 days 40, 300 days
  nothing - and the hive's health, growth and counter-attack interval follow it smoothly
  instead of quartering the moment the Core is touched. A
  missing or unbuilt Core fabricates nothing. Swarm fabrication stays a hard gate: no new
  Defense Swarms while the Core or the Nexus is disrupted at all.

## The stratum campaign

- **Push** (automatic when troops land at HOLDING strength - `autoPush`, knob
  `frontAutoPush` - or ordered by the player; requires HOLDING strength): takes
  `frontPushBaseDays` x (defense/strength ratio, clamped 0.5-3x) days, at
  `frontPushLossPer30Days` (0.30) attrition - pushing is how you win and how you
  bleed. A pushing front is exposed (no cover) and its entrenchment ramp is frozen.
- **Stratum taken**: strips one size-worth of the base defense (SwarmNexus) and one
  size-share of fabrication output (`computeFabricationMult`) - momentum is real,
  and each stratum weakens the colony. Growth halts entirely while a front is on
  the surface.
- **Checkpoint**: after each stratum the front CONSOLIDATES for
  `frontCheckpointDays` (10), announces a reinforcement request, and - told
  nothing - **pushes on by doctrine**. Orders at the checkpoint: push now,
  entrench and stand fast, or withdraw (docking gates enforce that pulling out
  requires the space over the planet).
- **Counter-attacks**: every `frontCounterAttackDays` (40) / colony health - so a
  starved hive attacks 4x more rarely, which is what economic strangulation buys
  now. Attack = current defense figure vs the front's strength x its cover when dug in
  (1 + (`frontEntrenchDefenseBonus` - 1) x how dug in it is, so up to 1.5). Losing costs
  `frontCounterAttackLossFraction` troops and a held layer, and spoils half the
  entrenchment either way; a beachhead beaten 2:1 with no layers is overrun.
- **The final stratum** destroys the Core: eradication, survivors evacuated
  (player fronts), announced.

## NPC fronts

Purge expeditions (`ThreatPurgeFGI`) sail only from a **mobilised** faction's base, drawing
that base's war reserve (2026-09-05: `IncursionManager.tryPurgeBombardments` skips factions
not in war mode; the old abstract purge from a faction never struck is gone, as is the
player's purge commission - the faction view's Siege button is the one way to raise one).
**They fight for the orbit first** (2026-09-05, `ThreatPurgeFGI.SiegeRaidAction`, knob
`siegeFightsForOrbit`): while Defense Swarms hold a target's orbit no siege pass is delivered
there, and the expedition's fleets **at that world** are made aggressive and un-blinkered so
they hunt the swarms down.

**The fight for an orbit ends at that orbit** (2026-09-07, `ThreatFleetOrders.siegeLeash`,
knobs `siegeHuntRange` 1500, `siegeLeashRange` 6000, both theatres). Vanilla is why a human
expedition stays on its target and it is one flag: `FGRaidAction` refreshes
`$doNotGetSidetracked` on every fleet every 0.4 days, and raid fleets never go looking for a
fight. This stripped that flag off **every** fleet in the system for as long as **any** target
was contested, and a Threat fleet is born `MAKE_AGGRESSIVE` with `ALLOW_LONG_PURSUIT`
(`DisposableThreatFleetManager`), so one defender that ran drew the whole expedition after it.
Once it left, nothing friendly was near the world, so "contested" read true forever and the
aggression renewed itself daily - a Thanatos strike spent three weeks at 0/3 passes chasing one
fleet across the sector, with the intel stuck on "suppressing the defences" the whole time.

Now the leash decides, per fleet, per tick: inside `siegeHuntRange` of a **contested** world it
hunts - the same 1,500 units the contest itself is measured over, so a defender that breaks off
is one there is nothing left to fight for; anywhere else vanilla's blinkers go back on and
aggression comes off; beyond `siegeLeashRange` of its nearest target, or out of the system
where the raid's own `MilitaryResponseScript` cannot reach it, it drops what it is doing and is
sent back. `ALLOW_LONG_PURSUIT` is stripped at spawn *and* every tick, so a fleet already
chasing in an old save lets go. In-system, that flag hygiene is the whole fix: vanilla's
response scripts walk a fleet back to the target on their own once nothing overrides its
heading. **Who holds an orbit is a strength contest** (2026-09-07,
`ThreatGroundFronts.orbitHeld`, knob `orbitContestFraction` 0.5, both theatres): the armed
fleets hostile to the besieger within 1,500 units of the planet - a hive's Defense Swarms, a
colony's station and patrols - hold it only while their fleet points are at least that
fraction of the besieger's own warships there; with nothing friendly there, any defender holds
it. Before this a hive's orbit was contested while ANY garrison fleet was alive anywhere in
its list, and the hive refills that list every poll (local respawn, sibling redistribution),
so four Defend fleets of 850 points over Gamma Hero II sliced on the rare poll with no swarm
alive and looked as if they bombarded for nothing. `orbitContested(marketId)`, the plain
head-count, stays for the convoy refuse rule: an unarmed run is turned back by any swarm. Vanilla's raid action re-flags its fleets every tick not to get sidetracked,
which is why a single swarm ship used to stall a siege for its whole stay - the landing gate
refused every pass and each fell through to a commando raid at ~1,200 marines (pirates and
the player alike, Gamma Gibidigi, seen in the log). The stage's vanilla time limit still
bounds the hunt; an expedition that cannot clear the orbit withdraws having done nothing,
which is the honest outcome. Once the orbit is clear, and the war-strata are
fully worn or the troops could hold as they are (`ThreatGroundFronts.readyToLand`, the strike's
gate), they land an NPC-owned front (troops = combined expedition ground strength,
armaments = `npcFrontSupplyDays` of that force's own burn) instead of their first commando raid.
The front runs a stance AI - push when strong enough and supplied, entrench
otherwise - through the same checkpoint pacing. When its supply runs dry it breaks off any
assault and digs in for its convoys (2026-09-06), calls for a pickup once it is also below
grind strength, and withers meanwhile; follow-up expeditions land fresh fronts. This is how factions erase hives without
the player. The player cannot direct or resupply an NPC front (dialog shows status
only), and cannot land where one already fights.

### Player-facing readouts

`ThreatGroundWarCondition` (`threatinc_ground_war`), on the colony screen for as long as
the front stands: districts held of size and what they cost, what is seized, which
defences are suppressed and to what, invader strength vs what the colony fields and what
they need to hold, days to the next counter-attack, and the invaders' supply - or, dry,
whether they are waiting for an expedition (and the days to their final push) or making
it. The colony's own Defenses tooltip shows each suppressed structure's surviving
multiplier. `HiveVitalityCondition` still covers the hive side. Announcements
(`announceAlways`), one sentence each: the landing, each stratum taken, a wave
reinforcing, the front battered / overrun / withered, and the colony falling.

### Engine changes this needed

- `ThreatGroundFronts.resolveMarket(marketId)` - the front engine used to resolve through
  `ThreatIncData.resolveColonyMarket`, which returns null for anything not flying the
  Threat flag and would have deleted a Threat front on a human world on the next poll.
- Whose ground it is lives in one object: `ThreatGroundFronts.Theatre` (`HIVE` /
  `COLONY`) answers `defenderStrength`, `counterAttackInterval`, `keyStructures`,
  `defenseStructures`, `needsSoftening`, `orbitHeldAgainst`, `carriesSiegeState` and
  `victory`; the tick asks it instead of branching, and a third theatre is a third
  subclass. The static helpers (`defenderStrength(market)`, `orbitContestedFor`, the
  requirement helpers, the old `orbitContested(marketId)`) keep their signatures and
  delegate. The front's owner is the other axis and stays on the front (`ownerOf`, never
  null - `deploy` writes `Factions.PLAYER` for the player).
- The landing doctrine is the engine's, not the expeditions': `needsSoftening`,
  `landingBlocked` (another army / the orbit, one reason string for the
  expeditions and the board alike) and `landOrReinforce` (deploy or resupply, the
  announcement, the Defend-contract failure for a Threat landing) are called by both
  `ThreatPurgeFGI` and `ThreatStrikeFGI`.
- `upgradeInFlightStrikes` no longer clamps ground-doctrine strikes (they have no
  bombardment type, and their passes ARE the siege).

## Reading a front - the board (2026-09-05, untested in-game)

The war board's **Ground fronts** table (docs/war-board.md item 4) is the ground war's
console: the hive view shows the selected system's fronts, a faction view the fronts that
faction is party to (its own landings, and Threat landings on its worlds). Every figure comes
from the readout helpers at the foot of `ThreatGroundFronts` ("readouts for the board"),
which reuse the tick's arithmetic, so the table quotes what will happen:

| Column | Helper | What it is |
| --- | --- | --- |
| Attackers | `effectiveStrength`, `entrenchMult` | troops x entrenchment (0.75 on landing -> 1.0 over 30 days of holding) x 0.5 when dry |
| Defenders | `defenderStrength` | the world's current figure; holding needs 17% of it, grinding 10% |
| Defenses | `defensesTrend` / `defensesLabel` | **worn** (holding front, disruption clocks fed faster than they run), **held** (grinding front keeps the defense structures down), **recovering** (foothold keeps nothing down), **intact** |
| State, Held, Stance | the front's fields | holding / grinding / foothold; strata or districts held/size; push / regrouping / dug in |
| Attrition | `attritionPer30Days` | 8% of troops per 30 days holding, 30% pushing (x the Lanchester skew), x3 when dry |
| Attack | `daysToCounterAttack`, `counterAttackRepelled` | days to the next counter-attack; green if the front's `defenseStrength` (x its cover, up to 1.5, when not pushing) beats the world's current figure |
| Arms | `supplyDaysLeft` | days of heavy armaments at the current burn (x2 while pushing) |
| Falls | `pushDaysEstimate` | "~N d" until the last stratum or district falls while the front can push (what is left of the current layer plus whole ones after it) |

The row tooltip gives the arithmetic one line per fact, including which structures are
disrupted and for how many more days, the next counter-attack's figures and outcome
(`counterAttackLoss`, `counterAttackOverruns`: repelled / costs N troops and a layer /
overrun), a dry Threat front's wait for its expedition or its final push, the push in
progress or what one would cost (`pushCasualtyEstimate`), and what the front wants
brought (`ThreatConvoys.frontWants`). The word is *troops* everywhere a front is
described - the swarm has no marines; *marines* survives only on convoy rows, which carry
the commodity (user, 2026-09-06).

**Orders from the board** (the player's own fronts, while mobilised): **Push** / **Dig in**
(`ThreatFactionView.BUTTON_PUSH` / `BUTTON_ENTRENCH` -> `orderPush` / `orderEntrench`,
the same calls the planet dialog makes; gated by `pushBlockReason` - holding strength, as the
dialog - and `entrenchBlockReason`; either also answers a checkpoint), Escort, Pull out,
Supply (refused when the front wants nothing, `ThreatConvoys.supplyBlockReason` - unless
the **Supply run** ladder over the table is set to Max, which asks for everything the base can
spare above its floor (a hull load until 2026-09-29) whether the front wants it or not; `ThreatConvoys.supplyAsk`, docs/player-aid.md "load ladders").

**What "dig in" and "push" mean.** Stance is the front's *order*, state is what its strength
*earns*. Dug in (`STANCE_ENTRENCH`): attrition at the holding rate, armaments at base burn,
entrenchment growing, defends counter-attacks at its cover (up to
x`frontEntrenchDefenseBonus`, 1.5, once dug in); takes no ground. Push (`STANCE_PUSH`):
progress toward the next layer at `frontPushBaseDays` x the defense/strength ratio
(clamped 0.5-3x) while HOLDING strength lasts (progress pauses below it), attrition at
the push rate, burn x2, entrenchment frozen, exposed to counter-attacks (no cover).
Regrouping (`STANCE_CONSOLIDATE`): the checkpoint after a layer, `frontCheckpointDays`;
told nothing it pushes on if it still holds, digs in otherwise. The player's front pushes the
moment its troops land if it can hold (`ThreatGroundFronts.autoPush`, from `deploy` and from
any `resupply` that brings troops; knob `threatinc_frontAutoPush`, default on - user,
2026-09-07); too weak to hold, it digs in and pushes when a later landing brings it to
holding strength. A Dig in order (board or dialog) holds until the next troops land; an
armaments-only drop never changes stance. NPC and Threat fronts run the stance AI: push
whenever supplied and holding, dig in otherwise - and a front that runs dry mid-assault
breaks it off and digs in (2026-09-06).

**Bracing, and who goes first** (`shouldBrace`, knob `frontBraceEnabled`; 2026-09-08). A front
holding NO ground that is caught assaulting is destroyed OUTRIGHT rather than pushed back - the
overrun branch - and assaulting costs it every point of cover, since `coverMult` is 1 while
pushing. So a front digs in when an assault would get it overrun, and resumes when entrenchment
or the odds make one survivable.

**The order of operations is the front's, not the fleet's** (user, 2026-09-08). The troops take
cover first; the guns speak only if cover was not enough on its own. `defendBombards` therefore
refuses outright while its own front is on `STANCE_PUSH`: a bombardment that close to troops in
the open is friendly fire, and a front still assaulting has decided it needs neither. The three
rules compose into a combined-arms loop with no special-casing: land -> the assault would be
overrun, so dig -> now dug in, the fleet may bombard -> the defence falls -> an assault survives
-> push, and the fleet stops.

**The test is "would an assault get me killed", not "is a blow due soon".** An earlier cut used
a proximity window (`frontBraceDays`, 10) and was wrong in shape: cover takes `frontEntrenchDays`
to dig, so a front 22 days from a counter-attack it cannot survive should already be digging, and
instead it pushed on exposed while its own fleet bombarded to cover the gap.

`overrunIfPushing` asks the question at the cover the front would have **while pushing**,
whatever its current stance. That is what makes it stable: asked at the front's live cover it
would flip the moment the front took any, flip back the moment it moved, and oscillate every
tick. Entrenchment only accrues while dug in and never decays, so this projection improves
monotonically and settles.

Three limits stand. It applies **only while the front holds nothing** - once it has ground, a
counter-attack takes a district back instead of annihilating it, a setback rather than a death.
It does **not** fire if the assault lands first (`pushDaysRemaining <= daysToCounterAttack`):
taking the layer strips the defenders of its share *and* puts ground under the front, so the
counter-attack that follows takes a layer back instead of overrunning a beachhead - racing to
finish beats taking cover whenever the layer can fall in time, and the tick order allows it since
the push resolves before `hiveCounterAttack`. And a `finalPush` never braces: it is spent either
way.

Two traps this walked into, both worth remembering. The NPC stance AI runs ~180 lines LATER in
the same `tickFront`, saw the entrench the brace had just ordered, and pushed the front straight
back out of cover - so the brace was dead for every NPC and Threat front until it learned
`shouldBrace` too. And `orderPush` and `orderEntrench` BOTH zero `pushProgress`, so a braced
front restarted its assault from nothing each cycle and, with a push longer than the cadence,
could never finish one at all; `bracedPushProgress` carries it over.

**What "reinforces" the defenders.** Nothing explicit. A world's ground figure is a stat
rebuilt every frame - a hive's from `hiveDefensePerSize` x the strata it still holds x the
Nexus bonus scaled by disruption; a colony's from vanilla's ground-defense stat plus its
banked reserve marines - so the defenders "reinforce" exactly as their disrupted structures
recover, and lose exactly what a holding front keeps suppressed and what each stratum strips.
The Defenses column is that trend. The one enemy *action* is the counter-attack (every
`frontCounterAttackDays` / the fed-and-held pace on a hive, / stability on a colony, faster with a military
command), which retakes a stratum or batters the beachhead. Defense Swarms in orbit never
fight on the ground; they contest the orbit, which is what blocks runs and landings. On a
human world the swarm besieges, real reinforcement does exist: relief guards and marine
convoys (`ThreatFleetOrders.planRelief`, `ThreatConvoys.planRelief`) add to the reserve
marines the figure counts.

## Vanilla-tool integration (unchanged from phase 1)

- **Tac bomb + own front = danger close**: costs the front
  `frontDangerCloseLossFraction` (0.005 a day since 2026-09-28) of its marines, in
  exchange the day also lands on the Core and port. Player-owned fronts only. Warned
  before confirm.
- **Sat bomb** (2026-09-28): a day of saturation; a front on the surface survives it. Over a
  colony it is a day of razing, the bombs on the owner's layers only and the front's counted as
  already lost; over a hive the day's wear alone (2026-10-01, "Saturation presses a hive"). No
  fallout.

- **Military options menu - who the defenders are (2026-09-07, TESTED, works)**: for a
  Threat colony the defenders are (`ThreatincMarketCMD.threatDefenders`) the live swarm fleets
  in its system that are its garrison (`GARRISON_FLAG`), reinforcements bound for it
  (`REINFORCE_TARGET_KEY`), Defend Swarms stationed over it (`ThreatSwarmDefend`), plus any
  other swarm fleet within `threatinc_defendRadius` (1000 su) of the world. Not vanilla's scan,
  which reaches only `battleJoinRange` (500 su net of radii, straddled by the garrison orbit at
  400-700 su and left entirely by a swarm hunting the player), skips a fleet whose finished
  battle still hangs off it, and then reads the swarm's willingness and weight through its
  fleet AI - hence "Engage the defenders" enabled with nothing to engage (the 2026-09-06
  "otherFleet is null" crash) and raids/bombardment open under a full garrison or with the
  garrison a few hundred su out (2026-09-07, Alpha Novy Tayvay I). Rule (`gateOnDefenders`):
  a free defender in reach - Engage open, raid, bombardment and ground landing closed
  (`temp.canRaid`); free defenders but none in reach - all three closed and Engage closed
  ("still N units out"); every defender busy in a battle - raid open, bombardment closed
  (vanilla's distraction rule); none - vanilla's result stands, raid cooldown included.
  `getInteractionTargetForFIDPI` is overridden to the same list (nearest free fleet in reach,
  else nearest busy one), so vanilla's draw and its Engage click agree; `engage()` keeps its
  backstop. Vanilla's defender text is suppressed for hives and replaced by one line (fleets
  in orbit and their fleet points, or fleets inbound and the nearest distance). Every menu
  draw logs the fleets weighed (`Military options at <world>: near/inbound/busy [...]`).

- **`removeOption` drops the enabled state of the other options (2026-09-07)**: this is why
  the two rewrites above changed nothing in game, and why the sessions before them failed -
  the detection was being fixed over and over while the menu was discarding the verdict. The
  hive path gated first and then called `removeOption(GO_BACK)` + `addOption("Ground
  operations")` + `addOption("Go back")` to slot our option in above Go back; the
  `removeOption` dropped every disable already set, vanilla's own included. Hence Engage
  clickable over an empty orbit (vanilla disables it there itself, with "There are no
  defenders to engage") and raid and bombardment open under a full garrison. Meanwhile
  `temp.canRaid` is a plain field, not panel state, so it survived and Ground operations >
  Land ground forces greyed correctly - which made the landing look like the only option that
  bothered to check, and sent several sessions hunting the detection code.
  Confirmed against the decompiled panel (`com.fs.starfarer.ui.newui.OoOO` in
  `starfarer_obf.jar`), not inferred: `removeOption` rebuilds the whole panel, and
  `setEnabled`, `setTooltip`, `setShortcut` and `addOptionConfirmation` all attach to the
  *button widget* that the rebuild throws away. Only a tooltip passed into `addOption` itself
  survives. We had already been papering over this without knowing: the escape shortcut on "Go
  back" has to be re-set after the re-add for exactly this reason.
  Everything else in the block is innocent and provably so: a plain `addOption` after
  `setEnabled` appends a button and touches no existing one (vanilla's own `addOption("Go
  back")` in `MarketCMD.showDefenses` runs after it greys raid for the cooldown, and
  `groundOps` greys the landing and then adds its own "Go back" - both grey correctly). A
  human colony returns straight after `super.showDefenses` and touches nothing, which is why
  vanilla targets were never affected. Nothing runs after the rule either -
  `fireBest("DialogOptionSelected")` fires one rule and our row carries `score:1000`. And the
  log proved the gate itself ran and saw the defender (`near 1`) on the very draw whose
  bombardment then went through, so the verdict was reached and thrown away. Vanilla calls
  `removeOption` in exactly one place (the `RemoveOption` rule command), never inside a menu
  build - so nothing in the base game trips over it.
  Fix: settle the panel's shape first, write its state last. `gateOnDefenders` now runs after
  the structural block and sets Engage, raid and bombardment *every* time rather than only the
  ones it wants closed - after a rebuild there is nothing left to inherit.
  **Rule for this codebase: in any dialog menu, do all addOption/removeOption/clearOptions
  first, then all setEnabled/setTooltip.**

## What replaced "decline" in the UI

- War board cards: `Front 1,250 push 2/6` in place of Reach; forecast column shows
  `s6 -> core ~85 d` while a strong front fights, `s6 contested` otherwise; card
  border lights up when a front is on the ground. Vitality bar threshold now
  `ThreatColonyManager.CRITICAL_HEALTH` (0.35, presentational only).
- HiveVitalityCondition, InfestedSystemIntel, ThreatSiegeReportIntel,
  ThreatMissionIntel: decline meters/forecasts replaced with strata-taken figures.
- Purge follow-up "wounded" test: front present or organs disrupted.
- Legacy decline persistent-data maps are kept but unread (old saves load fine).

