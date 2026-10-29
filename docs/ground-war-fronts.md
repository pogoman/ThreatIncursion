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

## The line (2026-10-23, built, untested)

The user, Sindria falling with 255 marines against 212 troops: "do away with counter attack
which is a bit random ... state column with values gaining losing and grinding". One line per
front moves by the net of both sides' pressure, and the counter-attack cadence is gone
(`docs/front-line-proposal.md` has the reasoning; this is what runs).

- **The rate** (`ThreatGroundFronts.lineRate`, -1..1, positive toward the landing) - THE SHARE RULE
  since 2026-10-28 (the user, Arkoman: "pushing as inferior force should still gain ground just
  less ... both sides push is just one subtracted from the other"): with a' = A x coverA, d' = D x
  coverD, p' = D' x coverD and k = `frontPushShareExponent` (1.0),
  `gainA = landingPushes ? a' x (a' / (a' + d'))^k : 0`, `gainD = worldPushes ? p' x (p' / (p' +
  a'))^k : 0`, `rate = (gainA - gainD) / (A + D)`. At k 1 both pushing is the old `a' - d'` exactly;
  a lone pusher gains `a'^2 / (a' + d')` (before: `max(0, a' - d')`, so an inferior push gained
  nothing): equal forces, one pushing, take a district in 24 d, a half-strength pusher in 54 d
  (dead in 33 by the attrition rule). Dug in never holds a push by cover alone, it slows it and
  cuts the losses; only a push back cancels it, so every hold figure (`deployWant`, the tooltip's
  "Needs N marines pushing to hold") sizes marines pushing back. A is `effectiveStrength`; D is the hive's
  `defenderStrength`, or for a colony MANNED DEFENCES (2026-10-28): `mannedGarrison` - the
  ground-defence score only as far as the armed marines man it, `groundDefencePerMarine` (20) a
  marine, none man nothing - plus the armed marines at weight 1; an NPC colony keeps `manningNeed`
  marines as a reserve floor (`ThreatReserves.floor`). The older text: D is the hive's
  `defenderStrength`, or a colony's garrison plus armed marines at weight 1 (`colonyGarrison +
  colonyMarineDefense`); D' is `defenderPressure` - `counterAttackStrength` (the force
  figure itself; the military command x1.5 went 2026-10-24), or x `hiveCounterAttackPace`
  for a hive (starve it and it cannot push). Both sides fight at their entrenchment's cover
  whatever they do (`coverIfDugIn`, `defenderCoverIfDugIn`; the user 2026-10-24 "Pushers keep
  their cover" - until then a pushing side fought at cover 1 against the other's full cover, and
  Volturn was asked 819 more marines to push back 603 troops; now about 260). A hive always pushes; a colony's
  marines push unless dug in (`defendersPush`).
- **Movement** (`tickFront`): `pushProgress += rate x days`. At `+frontLineBaseDays` (6) the
  landing takes a district (`takeStratum`), at `-frontLineBaseDays` it gives one back
  (`loseStratum`: a held layer, or "Beachhead Overrun" and the front is gone). A Push or Dig
  in order keeps negative progress and clears positive. Both sides earn XP per district change
  on the strengths of the day (`ThreatMarineXP.frontEarn` / `colonyEarn`).
- **Cover under pressure** (`coverStep` / `coverPressure`, 2026-10-28, the user: "a dug in force
  should be breakeable under enough pressure"): a side pushing spends its entrench days as
  before; dug in under an enemy push it loses the force ratio (the pusher's covered push figure
  over its own covered figure) x `frontCoverStripMult` (1.0) a day instead of gaining one, to 0
  and never into over-extension; dug in with nobody pushing it gains a day a day. An equal push
  strips 30 days in 30, a 2:1 push in 15; the cover regrows only once the push stops.
- **Doctrine** (`doctrinePushes`, `doctrineDefendersPush`, `pushPaysOff`, 2026-10-28): since a
  lone push always gains, NPC and swarm doctrine pushes while the push takes the next district
  before the force bleeds out at its push losses (days to the district <= troops / losses a day x
  `doctrinePushBleedMargin` 1.0), else digs in; a push that only slows a loss digging in would not
  stop is made regardless; a garrison with no marines loses nothing and pushes for free. Hysteresis
  (2026-10-28 late, hw314a: at parity the two clauses sat a hair apart and the swarm flapped push /
  dig in every tick - Sphinx 50 times in 130 days - each flip wiping its progress, so the garrison
  retook the world): a front already pushing keeps at a push that slows a loss; a dug-in one resumes
  only when the push pays, so parity settles dug in, cover kept. THE DWELL (2026-10-29,
  hw316a on the hysteresis jar: Eldfell still flapped daily - pushing spends the cover that made
  the push pay, digging in regrows it, so the bleed-out test itself alternates): a stance the
  doctrine chose holds `doctrineDwellDays` (10) before it looks again, the landing's
  (`GroundFront.doctrineDaysLeft`) and the defenders' (`defenderDoctrineDaysLeft`) alike.
- **The State word** (`stateWord` / `stateWordOf`, 2026-10-28, the user: "sub statuses like
  entrenched, dug in, holding, breaking retreating overrun"): the table's State cell from the
  reader's side. Pushing: overrunning past `frontOverrunRate`, gaining past `frontBreakRate` (0.1),
  grinding while gaining more slowly, stalled at 0, checked while pushed back inside the break rate.
  Holding (hold or regrouping) inside the break rate: entrenched past 2/3 of its cover days, dug in
  past 1/3, holding below. Pushed back, both sides: breaking inside `frontPushBackRate` (0.25),
  retreating inside the overrun rate, overrun past it.
- **States** (`lineState`, the rate-only word the push notices still use): overrunning past `frontOverrunRate` 0.5,
  gaining past `frontGrindBand` (0 since 2026-10-28: any movement shows its ETA, stalled means
  nobody pushes), grinding inside the band with both pushing, stalled inside
  it otherwise, losing and overrun the mirror. `front.state` (what the landing suppresses) is
  derived from the rate by `lineSuppressionState`: HOLDING (key structures worn) while it
  gains, GRINDING (defence structures held) inside the band, FOOTHOLD while it loses.
- **Readouts**: the fronts table's State is the word from the tab's side, Line (the old
  Counter column) the days to the next district either way (`daysToLineChange`), Falls on the
  rate (`pushDaysRemaining` / `pushDaysEstimate` = base / rate); the row tooltip quotes the
  rate, the next district and what the world pushes with; the defenders' Push button and prompt
  quote `defenderPushQuote` (the rate as the marines would push it). `holdRequirement` is the
  world's defence figure itself and `grindRequirement` its pressure over the best cover.
- **The marines ask** (`reliefNeed(market, rate)`): the marines banked that bring the rate to
  `-rate` from the swarm's side, found by halving on `lineRateOf` - 0 holds, `frontPushBackRate`
  0.25 pushes the army back (the default every convoy, Reinforce and battle request uses),
  `frontOverrunRate` overruns it. Sindria asks for the marines that out-push 212 troops, not the
  10 that won one blow.
- **Gone**: brace, `counterAttackInterval` / `daysToCounterAttack` / `counterAttackRepelled` /
  `counterAttackLoss` / `counterAttackOverruns` / `counterAttackOverwhelms` / `counterAttackTempo`
  / `hiveCounterAttack`, and knobs `frontHoldFraction`, `frontGrindFraction`,
  `frontStateHysteresis`, `frontPushBaseDays`, `frontCounterAttackDays`,
  `frontCounterAttackLossFraction`, `defenderCounterAttackLossFraction`, `counterAttackRatioClamp`,
  `frontOverrunMusterDays`, `frontBraceEnabled`, `frontBraceWhenBeaten`. `beachheadTroops` /
  `beachheadSurvives` still size an NPC landing on `counterAttackStrength x siegeBeachheadMargin`.

## The front (ThreatGroundFronts)

Marines + heavy armaments landed on a hive world ("Ground operations" on the military
options menu; commits everything aboard). Ticks on the colony poll at flat rates:

- **Supply**: armaments burn per MARINE per 30 days (`frontArmamentsPerMarinePer30Days`,
  0.25 - x`frontPushUpkeepMult` while
  assaulting). The stockpile IS the countdown; a dry front fights at
  `frontDryEffectivenessMult` (0.5) effectiveness (the tripled attrition went with THE
  ATTRITION RULE, 2026-10-24: a weaker force bleeds more by the odds) - "not enough armaments"
  is exactly the reinforce-before-pushing moment.
- **Effectiveness** = troops x entrenchment x supply factor, vs the world's CURRENT worn
  defense figure (`MarketCMD.getDefenderStr`). **Entrenchment is the attacker's cover, and
  it is earned** (user, 2026-09-06): a landing fights at `frontLandingMult` (0.75) and digs
  in to `frontEntrenchMaxMult` (1.0) over `frontEntrenchDays` (30) of holding - the ramp is
  spent while pushing, and past zero the push is over-extended to the same degree (strength
  / (1 + 0.5 x over-extension), full after 30 days of pushing past zero; `overExtension`) -
  and a layer changing hands resets both sides to 0 (the user's rules 2026-10-24: never
  worn, diluted or cut otherwise). The defenders' own cover is `defenderEntrenchDays`
  (ground-war-defenders.md), the same rules.
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
  `frontPushBaseDays` x (defense/strength ratio, clamped 0.5-3x) days; losses by THE
  ATTRITION RULE (`attritionPerDay`, 2026-10-24) with no cover - pushing is how you win and
  how you bleed. A pushing front spends its cover and its ramp day by day (the user, 2026-10-24: "pushing should reduce cover" - before, a push was exposed at cover 1 outright).
- **Stratum taken**: strips one size-worth of the base defense (SwarmNexus) and one
  size-share of fabrication output (`computeFabricationMult`) - momentum is real,
  and each stratum weakens the colony. Growth halts entirely while a front is on
  the surface.
- **Checkpoint**: after each stratum the doctrine picks the front's stance AT ONCE
  (`frontCheckpointDays` 0, the default since 2026-10-27 - the user, Haven: the 10-day regroup
  after every district was a flat pause for every side, 50 days on a six-district world, that
  gave nobody an order they could not give anyway). With the knob set the front regroups
  (`STANCE_CONSOLIDATE`, counts as dug in) for that many days first.
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

**One table per tab, read from that tab's side (user, 2026-10-18: "if you are on the threat tab you see
all the fronts from the context of the threat. if you are on sindria tab u see all fronts from sindria
perspective etc").** The Threat tab lists every front above the hive list (the swarm is party to all of
them; until 2026-10-18 only the selected system's showed, under its heading). A faction's tab lists the
fronts it is party to - its own landings and the swarm's landings on its worlds, `frontRowsFor` - above
its Colony Reserves. There is no crest column: the tab says whose side the row reads from, and the World
column is in its owner's colour. Every cell is that side's; the enemy's strength is the right-hand half
of Force and the rest of its arithmetic is the row tooltip, which keeps both sides' lines in the words
*marines* and *the swarm* (the user, the same night: no attacker / defender lingo anywhere). `addFronts`
takes the viewing faction; `weLanded` per row is "the viewing side landed here" and flips every
colour - a worn defence, a repelled counter-attack, a Falls estimate are the landing's good news.

| Column | Helper | What it is |
| --- | --- | --- |
| Force | `effectiveStrength`, `defenderStrength` | this side's strength, an arrow, the enemy's: `983 <- 144` on this side's world under a landing, `1,450 -> 2,080` where this side has landed. A landing's figure is troops x entrenchment (0.75 on landing -> 1.0 over 30 days of holding) x 0.5 when dry; a colony's is garrison + armed marines x cover (x 0.5 with no heavy armaments in stock); a hive's its defence figure. A landing holds at 17% of the world's figure, grinds at 10% |
| Disruption | `disruptionFraction` | the besieged world's military structures (`militaryStructures`: the theatre's key + defence lists less the port and a hive's Core and Nexus - the user, 2026-10-24), each clock over the suppress cap (`suppressCap`: wearDays x 1.2), averaged, as a percentage - whichever tab reads it; coloured the landing's gain, the world's loss (the user, 2026-10-24) |
| State | the landing's | holding / grinding / foothold |
| Held | `heldCell` | from this side: districts or strata this side's landing has taken (on a hive, the strata still standing - a `held/size` read as a reset when starvation took a size off, user 2026-10-11); on this side's own colony, the districts still its own of its size |
| Stance | `marineStanceLabel` / `swarmStanceLabel` | this side's: a landing's own push / regrouping / dug in; a garrison's push (= counter-attacks on the cadence, `defenderStanceLabel`) or dug in; a hive has none ("-") |
| Losses | `marineLossPer30Days` / `swarmLossPer30Days` | this side's, per day, THE ATTRITION RULE (`attritionPerDay`, the user 2026-10-24): troops / `attritionTroopsPer` (100) x (enemy force / own force) x 1 / own cover x veteran - a landing's `landingLossPerDay` (+ orbit), a colony's marines' and a hive's landed troops' `worldLossPerDay`; a hive's structure bleeds nothing |
| Counter | `daysToCounterAttack`, `counterAttackRepelled` | days to the next counter-attack - the garrison's against a Threat landing, the hive's against a marine landing; "held" while a garrison is dug in; coloured for this side (a repelled one is the landing's gain) |
| Arms | `supplyDaysLeft` / `defenderArmsDaysLeft` | this side's days of heavy armaments at the current burn: a marine landing's own (x2 while pushing), a colony stockpile's; "dry" when out; "-" on the Threat tab, the swarm fights on none |
| Objective | `daysToLineChange` | days until THIS side takes the next district or stratum at the line's rate; "-" while the line stands (inside `frontGrindBand`) or moves against it (the user, 2026-10-24) |
| Victory | `victoryDays` | "~N d" until this side wins the world at the line's rate: a landing takes the last layer (what is left of the current one plus whole ones after), a world pushes the landing off (every layer it holds back, then the beachhead); "-" otherwise (the user, 2026-10-24) |
| Space | `spaceHolder` | whose warships hold the orbit |

The row tooltip gives the arithmetic one line per fact, including which structures are
disrupted and for how many more days, the next counter-attack's figures and outcome
(`counterAttackLoss`, `counterAttackOverruns`: repelled / costs N troops and a layer /
overrun), a dry Threat front's wait for its expedition or its final push, the push in
progress or what one would cost (`pushCasualtyEstimate`), and what the front wants
brought (`ThreatConvoys.frontWants`). Every line names its side - "Swarm 144: 133 troops x ...",
"Marines 983: holding needs 167 ...", "Marines losing 0.74 a day", "Swarm pushing district 1 of 5",
"Next marine counter-attack in 5 days: 983 against the landing's 169" - and the swarm's bodies are
*troops*, never marines (user, 2026-09-06; the side names 2026-10-18).

**Orders from the board** (the player's own fronts, while mobilised): **Push** / **Hold** (named Dig in until 2026-10-28)
(`ThreatFactionView.BUTTON_PUSH` / `BUTTON_ENTRENCH` -> `orderPush` / `orderEntrench`,
the same calls the planet dialog makes; gated by `pushBlockReason` - holding strength, as the
dialog - and `entrenchBlockReason`; either also answers a checkpoint), Escort, Pull out,
Supply (refused when the front wants nothing, `ThreatConvoys.supplyBlockReason` - unless
the **Supply run** ladder over the table is set to Max, which asks for everything the base can
spare above its floor (a hull load until 2026-09-29) whether the front wants it or not; `ThreatConvoys.supplyAsk`, docs/player-aid.md "load ladders").

**Reinforce** (2026-10-17, the user: "once youve cleared space of your own colony that is
under attack. there is no way to send marines to defend ... like you can when you are
sieging. theres also no way to aid allied worlds under attack"). A Threat army's row on a
human world gets a button row too (`ThreatWarBoard.playerCanReinforce`: the player's own
colony while their faction may order, an ally's while `ThreatAid.canAid` allows) with one
button, **Reinforce** (`ThreatFactionView.BUTTON_REINFORCE`, keyed by the world's owner). It
sails a plain convoy of marines and heavy armaments into the colony's stockpile - not a
front run: there is no friendly front to land on, the marines join the garrison through the
arming ramp (`ThreatReserves.armedMarines`, `marineArmingDays`) and the armaments feed the
defenders' burn (ground-war-defenders.md "Defenders burn armaments"). The load is the
defence's want (`ThreatConvoys.reinforceWants`: `ThreatGroundFronts.reliefNeed` marines and
`defenderArmsNeed` armaments, net of what is already at sea) at the **Supply run** ladder -
Min the want, never under one reference load (`convoyMarineCapacity` marines and their `frontResupplyDays` of armaments - a defence that already outnumbers the army is not refused; the player decides what is enough), Med x`convoyExtraLoadFactor`, Max everything the donor can spare - from the
colony of the player's that can spare the most of it (`reinforceDonor`, non-staging colonies
first; `ThreatAid.quoteReinforce` over every player colony for an ally, fitted to the
source's own free hulls). Own colony: `reinforceTo` -> `dispatch`; ally: `dispatchReinforce`
-> `dispatch` with the recipient, standing on landing as any aid. The button's tooltip quotes
the load and the source; disabled it says why (`reinforceOffReason`, the quote's reason).
The convoy holds off at the jump-point while the Threat blockades the world
(`ThreatConvoys.holdsOff`) - clear the orbit first, as the user's report had it.

**What "dig in" and "push" mean.** Stance is the front's *order*, state is what its strength
*earns*. Dug in (`STANCE_ENTRENCH`): attrition at the holding rate, armaments at base burn,
entrenchment growing, defends counter-attacks at its cover (up to
x`frontEntrenchDefenseBonus`, 1.5, once dug in); takes no ground. Push (`STANCE_PUSH`):
progress toward the next layer at `frontPushBaseDays` x the defense/strength ratio
(clamped 0.5-3x) while HOLDING strength lasts (progress pauses below it), attrition at
the push rate, burn x2, cover and entrenchment spent day by day (exposed outright until 2026-10-24).
Regrouping (`STANCE_CONSOLIDATE`): the checkpoint after a layer, only with `frontCheckpointDays`
set (default 0 since 2026-10-27, so the stance resolves at once); told nothing it pushes on if
it still holds, digs in otherwise. The player's front pushes the
moment its troops land if it can hold (`ThreatGroundFronts.autoPush`, from `deploy` and from
any `resupply` that brings troops; knob `threatinc_frontAutoPush`, default on - user,
2026-09-07); too weak to hold, it digs in and pushes when a later landing brings it to
holding strength. A Hold order (board or dialog) holds until the next troops land; an
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

**Since 2026-10-18 the first limit is gone** (`frontBraceWhenBeaten`, the user: "if a counter-attack
is incoming attackers dig in and entrench, right?"): the brace test is `beatenIfPushing` - would
the blow beat the front caught at cover 1, ground held or not - through `counterAttackDanger`
(`overrunIfPushing` remains as the knob-off rule). The 2026-09-08 "press on and take the setback"
had been written against counter-attacks at `marineCounterAttackMult` 0.25; at 1.0 (0fcb269e)
every counter-attack won and took a stratum (hw191: 0 repelled in 370), so a front that pressed
on was bled a layer each cadence. The other two limits stand: the assault that lands first still
goes in, and a `finalPush` never braces. The landing is sized to the same line: `beachheadNeeded`
draws it at 1:1 x `siegeBeachheadMargin` (REPEL the first counter-attack - `beachheadRepels`,
`BattleRules.beachheadOdds`), not at the 2:1 overrun odds; about twice the marines a landing
carried before (0.83 -> 1.67 of the defence), both sides.

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
`frontCounterAttackDays` / the fed-and-held pace on a hive, / the force ratio on a colony, faster with a
military command, in `frontOverrunMusterDays` when it would overrun - and not at all while a colony's
defenders are dug in, `ground-war-defenders.md` "The defenders choose too"), which retakes a stratum or
batters the beachhead. Defense Swarms in orbit never
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
  ("still N units out"), but only within `defendInboundRange` (3,000 su; 2026-10-13, after three
  engaged reinforcements drawn 13-15,000 su off locked the menu for weeks) - farther out a fleet is
  not in the fight, counts for nothing and the text says so; every defender busy in a battle - raid open, bombardment closed
  (vanilla's distraction rule); none - vanilla's result stands, raid cooldown included.
  Whether raid and bombardment CLOSE is then the navy's own question asked the navy's own
  way - `ThreatGroundFronts.orbitContestedFor(player)`, the call `ThreatPurgeFGI.SiegeRaidAction.orbitHeld`
  makes: armed swarm fleets within `ORBIT_HOLD_RANGE` 1,500 su weigh at least
  `orbitContestFraction` 0.5 of the player faction's warships there, the player's own fleet and
  the navy's fleets alike. Held: raid and bombardment closed. Not held: vanilla's own verdict
  stands, raid cooldown included, defenders busy or not, and the text adds "Your side holds
  the orbit: N fleet points against the swarm's M". The user's rule (2026-10-13): if the AI
  navy can bombard, the player can. (Delta Mengryla I again that evening: two Defense Swarm
  scraps of 7 and 12 FP, kept 1,200-3,000 su off by six navy fleets and never arriving, locked
  raid and bombardment while those fleets bombarded from the board). The log line now ends
  `- swarm X FP vs ours Y at the orbit: held/not held`.
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

