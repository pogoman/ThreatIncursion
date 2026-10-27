# Occupation without ownership (2026-10-27)

The user's direction (2026-10-27): the Threat never takes a human world. A landed army
occupies it, infests and feeds on it, and the owner keeps the market, so the world can be
pushed off and reclaimed. Industries are claimed one by one, each marked, each pinned
disrupted at the maximum; what the industry exports feeds the army, so the more it holds
the more troops it has - strata on the world without a hive. This note says what the engine
(0.98a-RC8: `starfarer.api.jar` by javap, sources in `starfarer.api.zip`) allows, what the
mod already has, and the choices left. Read before `strata-are-the-threat.md` 13, which
this supersedes at its end state (a ground victory no longer seeds a hive).

## 1. What already exists

- **The infestation (phase E, built 2026-10-25, untested).** `ThreatGroundWarCondition.apply`
  multiplies every industry's supply by (1 - `ThreatGroundFronts.infestShare`), the share =
  districts held / size; `feedOnWorld` grows the front's strata (`GroundFront.marines`) by
  `coreStrataPerSizeMonth` x size x share / 30 a day. The fronts table reads "+N/d".
- **Industries seized one by one.** `ThreatGroundFronts.seizedIndustries`: held / size of the
  world's disruptable civilian industries (not Population, not the defence structures, not
  ours), in id order so the same ones stay seized poll to poll, each pinned disrupted at
  `districtSeizeDays` (`pin`), released when the district is retaken. The condition's tooltip
  already names them.
- **The end state is the only part the user does not want:** `colonyGroundVictory` ->
  `ThreatColonyManager.convertConquered` tears the market down and founds a hive
  (`conquestConverts`). Everything above runs while the world is still the owner's.
- **Reclaiming** exists both ways: Push / Dig in, counter-attacks and the line retake
  districts (`ground-war-defenders.md` "The defenders choose too"); `pin` recovers.

So the ask is a change of end state plus three visible things: per-industry claims on their
own clock, a marker on each claimed industry, growth from what each one makes.

## 2. What the engine allows (verified)

**Ownership.** `MarketAPI.setFactionId` is the only switch and only our code calls it
(`convertConquered`, outposts). Not calling it keeps the world the owner's for ever, with no
engine objection: a front, a condition and pinned industries are all per market and faction
blind.

**One engine rule can still take the world away:** `DecivTracker` (vanilla, monthly) warns
and then decivilises any market with stability 0 for `decivMinStreak` 3 months running and
half its 12-month history at 0, at 10% a month over the streak - and our own
`processPendingDecivChecks` would then turn the ruin into a hive. A fully infested world
(every supply x0, shortages everywhere) will sit at 0. The escape is vanilla's own: the
market memory flag `$core_noDeciv` (`DecivTracker.NO_DECIV_KEY`) stops both the warning
and the deciv. Set it while a Threat front stands, clear it when the front is gone.
Story-critical worlds are exempt anyway.

**Per-industry state.** `Industry` objects are per market; `getAllSupply()` and
`getSupply(commodity).getQuantity().modifyMult(id, mult, desc)` are per industry (the
condition already walks them). A claim clock per industry is a map in `GroundFront`
(industry id -> days), which is already persisted in the save. Industries can appear
(build queue) or vanish (shut down, raided) under a front: the map is reconciled against
`market.getIndustries()` each poll, as `seizedIndustries` is today.

**Disruption.** `Industry.setDisrupted(days)` writes an expiry into market memory; no cap
in the engine. `canBeDisrupted` is false only for Population & Infrastructure (and our own
structures). The industry card reads `getBuildOrUpgradeProgressText`: "Disrupted: N days
left", counting down, red. A clock that climbs reads "Disrupted: 361 days left" rising a
day a day, which is odd; a clock pinned at a fixed figure (as `pin` does) reads one steady
number. Either is a one-liner.

**The catch: a disrupted industry produces nothing.** `BaseIndustry.isFunctional` is false
while disrupted and vanilla's `apply` then books no supply and no demand. So "disrupted"
and "the swarm eats its export" cannot both be read off the industry: once it is disrupted
there is no export figure. Two shapes, both engine-trivial:

| Shape | The industry | What the swarm grows on | How the card reads |
| --- | --- | --- | --- |
| A. Disrupted | pinned disrupted, zero output and demand | a figure of what it WOULD make: its spec's base supply at the world's size (`hive-economy.md` "Vanilla industry numbers"), or a flat per-industry rate | "Disrupted: N days left" in red, vanilla's own look, nothing else to draw |
| B. Eaten | runs, its supply x0 for the market (`modifyMult`), demand still booked | the industry's real output units (`getSupply(c).getQuantity().getModifiedValue()`) or its credits (`CommodityOnMarketAPI.getExportIncome`) | the card looks normal; its tooltip lists "Threat infestation x0" on each output |

Shape A matches the user's words (disruption climbs to the max). Shape B gives a true
"export eaten" figure. A hybrid - disrupted, and the growth read from the base supply
table - is what the user described and is what section 4 recommends.

**A marker on a claimed industry.** The card draws `Industry.getCurrentImage()` and
`getCurrentName()`, and the tooltip comes from `createTooltip`. Vanilla's `BaseIndustry`
returns the spec's image and name, so only a plugin subclass can change them per market.
Four routes:

1. **Own every vanilla industry plugin.** The precedent is `ThreatPlanetaryShield`
   (`industries.csv` row with our class, XStream alias to vanilla's name so saves stay
   portable - holds while the subclass declares no instance fields, which an override of
   `getCurrentImage` / `getCurrentName` / `addRightAfterDescriptionSection` satisfies).
   Vanilla has 17 plugin classes across ~33 rows (10 station rows share `OrbitalStation`).
   Cost: 17 three-method subclasses, the full vanilla rows copied into our `industries.csv`
   (every column, re-copied on a vanilla patch), and one "infested" overlay image or ~30
   variants. `IndustrySpecAPI.setPluginClass` also exists, so the re-pointing can be done
   at mod load without copying the csv - but an industry instance already in a save keeps
   its old class until replaced (the shield migration at `ThreatIncModPlugin` 178-194
   shows the remove / add / carry-over of AI core, improvement, special item, build
   progress and disruption), on every market in the sector. This is the only route to a
   true per-industry icon, name ("Mining - infested") and tooltip line. It collides with
   any other mod that overrides the same rows; vanilla-only is the platform, so acceptable.
2. **One condition icon on the world.** The existing `threatinc_ground_war` condition with
   its own icon (`market_conditions.csv`), shown on the planet card and colony screen,
   tooltip listing the claimed industries and the growth. Zero engine risk, already half
   there. Per world, not per industry.
3. **A visible structure card "Threat Infestation"** in the industry grid, with our image,
   beside the industries it has claimed (the hidden `ThreatSiegeMalus` carrier made
   visible, as `FabricationCore` is). Its tooltip is the list. Per world again, but it sits
   where the player looks at industries.
4. `IndustrySpecAPI.setImageName` - global, every market at once: no.

**Growth and troops.** `GroundFront.marines` is the army's strata, persists, shows on the
fronts table and fights the line. "Convert it to more swarms" in space is mod code, not
engine: `ThreatBudding` spawns the hive's fleets from strata and is hive-only today, and
spawning a fleet at any planet is routine. Whether an occupied human world buds fleets or
only grows ground troops is a design choice, not an engine one.

**What vanilla keeps doing to an occupied world the owner still holds.** Its patrols spawn
while the military structure is functional (not while we disrupt it); trade fleets keep
coming for goods it no longer makes; its shortages and stability hit the owner's economy and
the player's own markets when the world is theirs (the intended ripple, phase E); the
player can still queue builds there; pirates and other factions raid it as before. The
mod's own council sees no hive (`isHiveTarget` false), so NPC grand fleets will not sail
for it; relief (`relief-holds-and-bombards`) does answer a front on an owned world and is
the doctrine that fits.

## 3. What is not possible or not worth it

- No per-industry icon without owning the plugin class (route 1). Vanilla exposes no
  overlay, badge or per-instance image hook.
- No "disrupted and still producing": disruption is a hard off in `BaseIndustry.apply`.
- The disruption text cannot be reworded without the subclass either; the number can be
  chosen (steady pin or climbing clock).

## 4. Recommended shape (to decide)

Ownership never moves; `$core_noDeciv` while a front stands; the ground victory becomes
"every industry claimed" with the army staying as the garrison it is today
(`digInAtConquest` without the conversion). Each claimed industry pinned disrupted
(shape A), claims advancing one industry per clock in a fixed order (or on the district
line as now), each claim growing the army by the industry's base supply at the world's
size (a Mining at size 6 feeds more than a Farming at 3), the figure in strata a day as
`infestGrowthPerDay` is now. Marker: route 2 now (condition icon + list), route 1 later if
the user wants the icon on the card itself. Knobs: claim days per industry, strata per
output unit, whether the occupied world buds fleets.

**DECIDED (the user, 2026-10-27 dialog):** marker = route 3, a visible Threat Infestation
structure card; shape B, a claimed industry runs and its output is eaten (x0 to the market,
the units feed the army); claims on their own clock, one industry per `occupationClaimDays`
in a fixed order; an occupied world BUDS FLEETS from its strata too, a new source of swarms
in human space. Ownership never moves.

## 5. BUILT 2026-10-27 (jar 14:09, untested, uncommitted)

- `ThreatOccupation` (new): `occupies` (a Threat front on a human world in the economy, knob
  `occupationEnabled`), `holdsAll` (every district), the claim clock in `tick` (one industry
  per `occupationClaimDays` while a district is held, `claimable` in id order, Population /
  defence structures / ours excluded; claims of industries gone from the market dropped), the
  growth (`growthPerDay` = `eatenUnits` x `occupationStrataPerUnit`: troops to the front while
  districts are contested, `budPool` once every district is held), `bud` (a garrison swarm of
  the pool at `occupationBudFP`, "Infestation Swarm", on `ThreatSwarmDefend` over the world for
  as long as the front stands, home = none so it despawns into hyperspace when the front
  goes), `occupy` (the victory: stance entrench, "World Occupied" notice once), `release` (a
  district retaken drops the newest claim, "Industry Reclaimed"), `holdDeciv` (`$core_noDeciv`
  + `DECIV_HELD_KEY` renewed 90 d a tick, the `raiseUnrest` guard).
- `ThreatInfestation` (new): the visible structure card `threatinc_infestation`
  (industries.csv: structure, unraidable, no_saturation_bombardment, Swarm Nexus image, order
  9990), installed by `syncSiegeState` while anything is claimed; tooltip: Claimed, Next in N
  days, Eaten units / strata a day, troops on the ground or the budding pool.
- `ThreatGroundWarCondition.apply`: under the occupation, every claimed industry's supply
  x0 ("Threat infestation"), the units before the cut summed into `front.eatenUnits`; every
  other industry unmodified. Tooltip: "Claimed by the swarm", "Next claim", "The swarm eats N
  units a day". With the knob off, phase E's share cut as before.
- `ThreatGroundFronts`: `GroundFront.claims / claimDays / eatenUnits / budPool /
  occupiedAnnounced`; the tick calls `ThreatOccupation.tick` where `feedOnWorld` was (gone);
  the line resets its progress instead of taking a district past the last; the seized
  industries are no longer pinned disrupted under the occupation (`districtSeizeDays` only
  with it off); `colonyGroundVictory` occupies and returns before the teardown;
  `loseStratum` releases a claim; `infestGrowthPerDay` reads the occupation's figure.
- `ThreatWarBoard`: a front holding every district reads "occupying" in place of its stance.
- Knobs (settings.json, LunaSettings.csv, no migration): `occupationEnabled` true,
  `occupationClaimDays` 30, `occupationStrataPerUnit` 1.0, `occupationBudFP` 60.
- Not touched: `lastStratumProtected` (a story-critical world with `destroyStoryCritical`
  off is still never fully held), `ThreatAidMissionIntel.strikeLanded` (the defence contract
  no longer fails at the victory - the colony is not lost), the attrition rule (an occupying
  army at entrench still bleeds by it; the growth is what offsets it).

**Verify:** a Threat landing holding a district claims an industry on day 30 with the
notice, the colony screen shows the Threat Infestation card and the industry's supply
tooltip reads "Threat infestation x0"; the fronts table's Held cell reads "+N/d" once an
industry is claimed and N = units x 1 a day; the last district taken reads "World Occupied",
the market keeps its faction and the board's front reads "occupying"; at 60 FP of pool an
Infestation Swarm rises and stays over the world; a district retaken drops a claim and the
card goes when the last claim does; the front pushed off ends everything and the industries
produce again; no "Colony Lost" and no deciv warning on an occupied world through a year.

**Fix 1 (2026-10-27, the user's save: two districts of Arkoman held, no marker on the colony
screen).** On a save loaded onto the jar the clock started at zero, so the first claim and the
card were 30 days away, and the old pins on the seized industries had gone with nothing in
their place. Now (1) the card shows from the first district held ("Claimed: nothing yet",
"Next: X in N days"); (2) a front from before the occupation (`claims` null, a district
held) starts with the industries its districts had seized (`seizedIndustries`) as its
claims, their pins lifted (`setDisrupted(0)`), logged "starts with N industries claimed".

**Fix 2 (2026-10-27, the user's Haven screenshot: "no markers on the industries", "a new
military base saying threat infestation", "infesting the battle station next").** (1) The
card is retired: `ThreatOccupation.showsCard` false, `ThreatInfestation.isHidden` true, the
csv row kept so saves holding one still load and `syncSiegeState` removes it. (2) THE
MARKER is vanilla's own shortage icon: a claimed industry demands one unit of the mod
commodity `threatinc_infested` (`commodities.csv`: nonecon, hide_in_codex, no_drop, the Threat
crest as icon) that nothing supplies, set by the condition's apply (`modifyFlat` 1, `unmark`
off a released one and in unapply, `clearUnmodified` drops the empty entry) - the red
shortage icon on the card and a "Threat Infestation" line in the industry's demand list.
(3) Claimable = producers: `isIndustry()` (vanilla's "industry" tag) with a supply - never a
port, waystation, station, defence or Population; claims an older jar made outside the rule
are dropped on the next tick. UNTESTED: whether vanilla draws the deficit icon for a
nonecon commodity, and whether `market.getCommodityData` serves one (if the colony screen
dies in `getAllDeficit`, drop the nonecon tag and give the commodity no demand class).

**Fix 3 (2026-10-27, Haven again: "no icon saying what's been infested (2 districts
taken)").** A district taken claims an industry on the spot: `ThreatOccupation.catchUp`
claims one industry per district held that is not yet claimed, from `takeStratum`
(`onDistrictTaken`) and each tick (so a front from any earlier jar catches up at once); the
clock then claims the rest while ground is held. The dialog's "own clock" stands for the
claims beyond the districts; the user's expectation in play is that held ground reads as
infested industries at once.

**Fix 4 (2026-10-27, the user: "move the icon to the production area ... producing threat
swarm icon ... use the fabrication core icon ... shade the industry a dark green").** (1) The
marker is production, not shortage: a claimed industry SUPPLIES the commodity (renamed
"Threat Swarm", icon `fabrication_core.png`) at the units it feeds the swarm (`modifyFlat`,
at least 1), so its production list reads "Threat Swarm N" and nothing else - its real outputs,
cut to 0, drop off the list (`BaseIndustry.clearUnmodified` removes supply at or below 0).
`eatenUnits` keeps its last full read on a conditions-only pass (the cut entries are gone by
then). (2) THE GREEN IMAGE: `getCurrentImage` is the only per-market hook, so the seven
vanilla producing plugins (Farming, Mining, TechMining, Refining, LightIndustry,
HeavyIndustry, FuelProduction) get field-less subclasses `Occupied*` returning
`ThreatOccupation.image`: the dark-green copy under `graphics/threatinc/industry/occupied/`
(nine files, made from vanilla's by `tint-green.ps1`: luminance x (0.22, 0.55, 0.24)) while
claimed. Wired the planetary-shield way: `aliasPlugins` in `configureXStream` (our classes
write as vanilla's names, vanilla's names load as ours - every Mining in a save is an
OccupiedMining after load, and the save stays loadable without the mod), `registerPlugins`
in `onApplicationLoad` (`setPluginClass` on the nine specs so new industries are ours too;
`loadTexture` on the nine images). Another mod's industry or image is left alone. The colony
screen reads the image when it opens, so a claim made while it is open shows on reopening.

**Fix 5 (2026-10-27, the user's Mining tooltip: "no shading on the card ... I still want the
icon on the industry tile, like a surplus production so highlighted in green ... a png like the
other resource icons, just the main threat ship").** (1) WHY THE CARD STAYED VANILLA: a save
stores an industry under its class's SIMPLE name (`<Mining z=... id="mining">`, 91 of them in
the user's save), so fix 4's alias of the class name matched nothing and every Mining loaded
as vanilla's Mining - the same reason the planetary shield needed `migrateExistingShields`.
`aliasPlugins` now aliases the simple name (`Mining` -> `OccupiedMining`; ours still writes as
`Mining`, so the save stays loadable without the mod), and `ThreatOccupation.ensurePlugins`
is the fallback each tick: a claimed industry whose class is not its spec's plugin is rebuilt
on ours the shield way (AI core, improvement, special item, disruption carried; a build in
progress waits), logged "rebuilt on threatinc.Occupied...". The colony screen reads
`getCurrentImage` when it opens (`marketinfo.intnew`, javap 2026-10-27). (2) THE TILE ICON:
the tile's only commodity row is `getAllDeficit`, drawn in vanilla's red shortage style
(hard-coded `IconRenderMode`), so a green production icon cannot be put there; the swarm
icon is drawn INTO the occupied image instead - `make-icons.ps1` renders the nine images at
2x (380x190), shaded as before, with the icon in a green highlight box top-right (fill
18/58/24 a190, edge 86/190/96), clear of the shortage row bottom-left. (3) THE ICON:
`graphics/threatinc/icons/threat_swarm.png`, 80x80 with alpha like the cargo icons, is
vanilla's Fabricator sprite (`graphics/ships/threat/fabricator.png`, the ship at the centre
of the Fabrication Core image) alone; `commodities.csv` points at it, so the tooltip's
production list shows the same ship.

**Fix 6 (2026-10-27 evening, the user's save: Haven's Farming "says it's infested" but plain).**
Vanilla's plugins swap the image by condition - Farming draws `farming_low/med/high` by
farmland, Mining `mining_low` / `gas_giant_mining`, Light Industry `_low/_high` and the
`light_industry_orbital*` set, Fuel Production `advanced_fuel_production` - and `image` only
greens a file it has a copy of, so any variant drew vanilla's. All twenty now have a copy
(`ThreatOccupation.IMAGES`), made by `tools/occupied-images.ps1` (the fix-5 recipe, committed;
it reproduces the original nine byte for byte). A new vanilla variant goes in both lists.

## 6. THE HIVE POOL (2026-10-27 evening, the user's decision; built, untested)

hw292 (round 62, set A from day 0, the first games on the occupation jar) ran clean - 0
exceptions, 0 human worlds consumed, 35-45 worlds occupied a game - and the swarm won, but
the occupation fed on a factory's rule, not a hive's: the claimed industries' output went to
the front at ONE TROOP PER UNIT A DAY (section 5). Raesvelg (Hegemony, size 8) refilled
11,092 FP of troops against 4,738 marines landed in 29 convoys and 4,885 bombed off, and the
front never moved; once every district was held the same output budded a 60-FP Infestation
Swarm every fill (934-1,552 a game), every faction's navy sat on relief at home (5,972 relief
sorties against 730 on the jar before) and grand fleets sailed 3 / 3 / 3 times. The user:
"i want it to behave like a normal threat world, they can dynamically shift troops from
space and back to the siege at will (albeit over time) ... everything is a bit overtuned";
the claims stay ("so you are saying remove the infestation of individual industries?" - no).

**The rule: an occupied world is a hive of its size without the title.**

- **The pool.** The claimed industries feed ONE strata pool (`GroundFront.budPool`) at the
  rate a hive of the world's size grows when fed in full: `coreStrataPerSizeMonth` (200) x
  size a month x the share of the world's producers claimed (`ThreatOccupation.share`,
  claimed of claimable) x `occupationRateMult` (1). A size 8 with every producer claimed
  grows 53 a day, a size 4 with half of them 13. Cap (`cap`): the pool, the strata rising,
  the troops on the ground and the swarms above (`held`) never exceed `ThreatStrata.sizeAt`
  of the world's size - the strata a hive of that size begins at (size 4: 3,200; size 8:
  51,200). `eatenUnits` stays the marker's figure (the "Threat Swarm N" production line).
- **The flow.** Every move is at the hive's flow (`flowPerDay`): `strataFlowPerDay` (400) a
  day, or `strataFlowFractionPerDay` (0.02) of what the occupation holds; one direction a
  tick, the ground first (`shift`).
- **Down.** The ground short (`fabricateNeed`: the hold line or the counter-attack, with
  `fabricateHoldMargin`) draws troops from the pool at the flow, armed by `fabricateArms`.
- **Up.** A force over the world (`hostilePointsNear`) that the Infestation Swarms standing
  (`standingFP`, the swarm's `ThreatSwarmDefend` entries there) do not outweigh by
  `systemDefenceMargin` (1.25) draws a swarm, ONLY IF ENOUGH: the pool and the strata risen
  must cover the want (at least `occupationBudFP`, 60 FP); the strata rise into
  `budRising` at the flow and the swarm spawns WHOLE when they hold the want ("Swarm
  Rising", a garrison swarm on Defend over the world as before). The force gone before it
  spawns, the rising goes back to the pool; short of enough, the swarm stays in the ground
  ("cannot match N FP over it" in the log). No more 60-FP drips to be annihilated.
- **Back.** The orbit quiet for `defendLingerDays` (10): the swarms above are absorbed into
  the pool at the flow (`absorb`: the smallest first, a hull at a time, the fleet whole when
  down to the bite). The ground quiet (every district held, both gaps 0): troops beyond the
  ground's want (`groundWant` - the hold line and the counter-attack with margin, never
  under a stratum of `strataSizeBase` 400 a district held) re-embark into the pool at the
  flow.
- **Already there, unchanged:** a Defend swarm over its own short front breaks its hulls into
  troops (`defendFabricates` / `fabricateTroops`); the attrition rule bleeds the front; a
  front pushed off ends the pool, the rising and the swarms above with it (home = none).
- **Knobs:** `occupationRateMult` 1 (new, settings.json only), `occupationBudFP` 60 (now the
  least swarm the pool buds), `occupationStrataPerUnit` gone. New front fields `budRising`,
  `orbitQuietDays` (0 on older saves).

**Verify (from day 0 against hw292):** the fronts table's Held cell "+N/d" reads the hive
rate x share and 0 at the cap; a relief over an occupied world draws one whole swarm sized to
it or none; a quiet orbit absorbs the swarm within about 10 days plus the flow; a size-8
occupied world no longer fabricates 11k FP a war; grand fleets sail again (hw290 21 / 25 /
9 sails) and the navies come off relief.

**hw294 (round 63, set A from day 0, jar 18:19:09, capped at w3,500-3,800):** 0 exceptions, 0
worlds consumed, 43-48 occupied, relief sorties 435-536 (hw292 5,972), sends 1,379-1,927, buds
546-1,184, absorbs 682-1,023. **Fix 1 (19:40):** the pool had absorbed every swarm Defend fleet
over the world - the strike fleets that came to defend it - into pools of up to 179k strata
(Cibola, cap 12,800) and budded them back a top-up at a time. Now `absorb` takes only swarms the
pool budded (`BUD_KEY` in fleet memory, set in `bud`), `held` counts only those (`buddedFP`;
`standingFP` still counts every swarm for the want), and neither an absorb nor a re-embark takes
the pool above `cap`. `game-runs-2.md` 72.

**Fix 2 (2026-10-27 23:05, the user: "they should when no threat detected so they help grow colony"):** a quiet orbit (no hostile in the system for defendLingerDays) absorbs ANY swarm of the Threat's on station over the world into the pool - parked strike fleets included, not only the pool's buds (`absorb`, the cap still binds) - and `standDown` sends whatever the full pool cannot take home to its hive to despawn and re-bank (`ThreatSwarmDefend.sendHome`). Why: hw297a kept 60-90 strike fleets parked over sieged and occupied worlds for years, and vanilla's fleet AI weighing them was 60% of the frame (`performance.md`). **Fix 2b (23:18, hw298b first poll):** Athulf stood down 10 swarms (14k FP) with the pool at 0 of 3,200 - `absorb` had returned false on a tick whose bite rounded below one hull, and `standDown` read that as "full". The stand-down now runs only when `cap - held` is under one hull of strata (`ThreatStrata.strataOf(1)`); otherwise the quiet orbit waits for the next absorb tick.
