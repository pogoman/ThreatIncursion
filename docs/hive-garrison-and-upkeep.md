# Hive economy - posture, stance, fuel, parity, structure and size upkeep

Split out of `hive-economy.md` on 2026-10-01. One-line answers: `facts.md`.

### Posture - the garrison the war calls for (2026-09-29, built, untested)

Until now every nexus built past its size table whenever its bank paid, and only upkeep stopped it:
90-100k FP of mostly idle standing fleets. `ThreatPosture` weighs the war around each hive system and
holds the garrison that war calls for, no more. `postureEnabled` false gives the old behaviour.

- **Pressure** (per hive system, fleet points, read every `postureDays`): P = max(A, B x
  `postureStagedShare`) + C + D + F. It rises at once and decays over 30 days (`DECAY_DAYS`).
  `postureStagedShare` is 0 since 2026-10-04: B is still read (the log, the stance) but is no pressure -
  see "The swarm's restraints removed" below.
  - **A - attacks:** the warship points of every live NPC siege booked on the system's worlds, plus
    hunts aimed at it and Support / Defend orders over its worlds (each fleet once), from dispatch
    and at any distance. A siege still off-screen counts its as-sailed flotilla (`abstractNow`,
    knob `threatSeesBookedSieges`, decision 10 of 2026-10-01; before it, Gamma Sonora under a
    4,900 FP daily siege read "attacks 0"). With the swarm's fog on (`threat-fog.md`) A, B and F
    count only what the swarm's eyes or scouts saw (`ThreatSwarmIntel`; no radar since 2026-10-02).
  - **B - staged capacity:** what the bases staging against the system could pay a force there
    (`ThreatSoftening.payableFP`, with hunt donors - capacity, not a siege's sizing, which reads the
    garrison and would chase its own tail). Weight 1.0 for a base staging for its siege, 0.5 for one
    staging for a hunt (`stagesForHunt`). The most any one base of a faction could, then summed across
    factions. `swarmsMet` is never used. Not weighed by the grudge any more (2026-10-01): capacity is
    the worst a faction can bring, and the need below already out-holds it by `postureMargin`; x (1 +
    grudge x `alarmTargetMult`) rode the grudge's ratchet in h38a to x5.5 on average and x14.7 at worst
    (243 swarms at a size-2 world; sends took half the fuel, feeding six frontier worlds that fell
    anyway). The grudge still picks strike targets.
  - **C - losses:** Threat ships lost in the system lately, decaying (`noteBattle`, fed by
    `ThreatSwarmBountyIntel`'s battle listener; each ship once).
  - **D - hostiles:** hostile fleets present in the system that A did not already count.
  - **F - forward guards:** frontline garrison fleets near the system (within `frontlineKeepLY`) but
    outside it.
  - **E - wounds** are not pressure: a ground front on a world, a disrupted organ or a recently thinned
    system (`recentlyThinned`) force BESIEGED.
- **Want** (per colony) = max(base, its share of need). Need = P / `npcSiegeOrbitMargin` x
  `postureMargin`, split across the system's colonies by their size tables' cost (`floorFP`) - until
  the attack comes down on a world: then it stands at the worlds a force is over or an army is on
  (`overWorlds`, 2026-10-04, below).
  Base (`baseFP`) = the colony's `garrisonReserve` swarm count costed at the table's cheapest rows
  (`minimumFP`, always at least one swarm) plus, at a forge colony of at least
  min(`spreadMinSize`, `strikeMinSize`), `LAUNCH_STOCK` (2) more rows (`stockFP`) - the substance of
  its next wave or strike. There is no "floor" of the whole size table any more (2026-09-29 test: the
  full table cost more than upkeep let the hive hold, so the want never left a surplus).
  While the sector stance is PRESS, a staging colony's want also gains the strike stock its target
  needs (`ThreatStance.extraWantFP`, added in `wantFP`; see "Stance").
- **Triage** (write-off; `postureTriage`, off since 2026-10-04). A system whose P / `npcSiegeOrbitMargin` exceeds what the hive could
  gather there - its held + its bank + the sector's held above its bases elsewhere - is written off:
  its need is zeroed, so its want falls back to its base and transfers stop feeding it (a 37k want
  against a 40k-FP hive would have stripped the quiet hives into it, ti-h8d). Read every pass, so it
  is lifted when the pressure or the hive's strength changes. Logs `Posture: X written off - pressure N
  needs N FP, the hive could gather N` / `... defensible again - ...`.
- **The size table** only decides the shape and tiers of what is built, not how many. Under posture
  `maintainGarrisons` builds only while held + inbound < want (`wantsGrowth`; held counts the garrison,
  raiders out and reinforcements inbound), or while the colony has no fleet at all. Nothing above the
  want is built; the bank keeps the rest for waves, strikes and foundings.
- **"Full strength" for launches** means held >= want (`ownAvailableForLaunch`), not the size table -
  with slack: a colony counts as regrowing (and launches nothing) only when it is short of want by
  more than its cheapest swarm (`ThreatPosture.regrowing`); held against the want itself, a colony a
  few FP short - a learned swarm cost crept up, a scratch, a bank a swarm short - sent nothing at all.
  `garrisonTargetCount` is the swarm count a colony fills toward - the table's
  (`desiredGarrisonCount`) with posture off, `ThreatPosture.baseCount` (reserve + launch stock) with
  it on. The war board's garrison "x/y" and fabrication trend read it, so a quiet colony shows full
  at its lean target (docs/war-board.md).
- **Modes** from ratio = (P / `npcSiegeOrbitMargin`) / held, with hysteresis (one threshold each flipped
  a system every few days as transfers moved its held FP across it). WATCHFUL is entered at 0.25 and left
  below 0.15; THREATENED is entered at 1.0 (the hive outmatched at the siege's own margin) and left
  below 0.8 (1 / `postureMargin`, the hive holding its want) - at 0.75 a system that had built to its
  want read 0.8 and stayed THREATENED, forges home, for as long as anything stood staged against it.
  THREATENED also when nothing is held and P > 0. Wounds give BESIEGED; a healed BESIEGED system
  leaves through the THREATENED band (it stays THREATENED until the ratio falls below 0.8). THREATENED
  and BESIEGED are "pressed". A change is logged.
- **Under attack** (`underAttack`) is narrower than pressed: BESIEGED, or attack fleets or hostiles
  present, or Threat ships lost there in the last 30 days (state field `S_ATTACKED`). A THREATENED
  system only faces a threat.
- **Launch spare** is held - need (`launchSpareFP`): what a launch may take from a colony. In a quiet
  system (need 0) that is everything above `garrisonReserve` - a quiet hive thins to its reserve to
  spread (its lean base is that reserve plus the launch stock).
  Under pressure only fleets that fit inside what is held above the need go, largest first.
- **Surplus** (held above want x (1 + `postureBand`) and one swarm, `releasableFP`) is released in this
  order:
  1. The pressure pass in `redistributeGarrisons` (`redistributeByPressure`). Under posture only this
     pass runs; the nominal pass toward the size tables (`redistributeNominal`) runs only with posture
     off. A colony holding less than its want (inbound counted), the neediest first, draws whole
     fleets from siblings: a donor gives what it holds above its own want x (1 + band) and one swarm,
     and must hold at least two fleets. Only a receiver in a system UNDER ATTACK may also draw the
     colonies of quiet systems down to their minimum (`thinnableFP`), one fleet at a time: a donor must
     still hold its want to give, so it gives one and stops below it until it refills. Same system
     first, then the nearest, the most to spare breaking a tie (with billed reach off,
     `ThreatReach.enabled`, the most to spare comes before the nearest); the fleet must be covered
     whole by the donor's spare and leave the receiver no surplus to send back (the largest within
     the deficit, else the smallest). No
     circuits (a test sent 89 fleets in a month round a ring of three systems and back):
     - a donor gives only from fleets on station (held less what is inbound), and never while below its
       own want;
     - a colony that received a transfer rests 30 days (`DECAY_DAYS`, `threatinc_postureReceived`)
       before it can donate;
     - a fleet that was moved rests 30 days (`$threatinc_postureMoved`) before it is sent again;
     - each transfer counts as inbound to the receiver while it travels.
     - **Fabrication** (`fabricatorFor`, `fabricateFor`; 2026-09-29, ti-h8f - 60-80k FP lay banked at colonies at
       their want while pressed systems held 5-10k short): when no garrison in reach can spare a fleet for a
       receiver short of its want, a colony that holds its own want, can fabricate and reach the receiver, and
       whose bank holds its own want plus the swarm's cost builds the receiver's cheapest garrison row
       (`cheapestRow`) and sends it as a normal reinforcement (same rests and inbound). Richest idle bank
       (bank - want - cost) first, same system first. The cost is paid from that bank (`chargeFP`).
  2. Waves and strikes, through launch availability.
  3. Recycling (`recycleSurplus`), only after the break-even: (1 - `hullShare`) / upkeep x 30 days -
     150 days at the defaults - one fleet per colony per pass, weakest first, out of battle; or at
     once, as much as it takes, for a waiting bill (`buildWaiting`, or the founding of a pending claim
     the colony would source).
- **Appetite and claims.** Appetite = what the quiet systems hold above their want (as a share of want;
  fleets above want and banks above want both count) plus the share of forges whose system pays the next
  founding (`poolableFP` >= 5 x `foundingFPPerStructure`, `forgesCovered`; a waiting bill reads it too). Forges in pressed systems are not free
  (`trySpread`, `pickForgeSource` skip them), and pending claims are at most
  `claimCap` = ceil(free forges x appetite x `ThreatStance.expansionShare`), appetite capped at 1, at
  least 1 claim while that product is above 0. The share is 1 expanding, `stanceSecondaryShare` pressing,
  0 consolidating (no claims).
  `pickForgeSource` prefers a forge whose system can pay the founding (`poolableFP` >= 5 x
  `foundingFPPerStructure`) over one that cannot, then ranks by launch-available swarms, then distance
  (before this the swarm-richest forge was picked, failed its bill and the claim waited months, ti-h8e).
- **How fast it answers** (mapped 2026-10-01 for the war council's feints, `war-council.md` section 9):
  - `ThreatPosture.poll` runs every `postureDays` inside the manager's 0.4-0.6 day tick, so passes are
    5.0-5.6 days apart. An attack counts from its dispatch, so the swarm knows of it within a pass.
  - Transfers (`redistributeGarrisons`) run every tick on the wants of the last pass, one fleet per
    loop until no receiver can be served. A transfer is a real fleet (`GO_TO_LOCATION`) that can be
    intercepted, counted inbound at once; `ThreatReach.days` assumes 0.5 ly a day. They land 0-15 days
    after the pass, some 30+.
  - What one attack pulls: about 0.83x the system's pressure (P / 1.5 x 1.25) less what it holds. A
    force smaller than the garrison it threatens pulls nothing unless it sinks swarms (C). In the log,
    Hadreel at P 13.6k holding 8.1k had 3.6k FP inbound one pass later, in fleets of 330-1,008 FP.
  - A donor refills one swarm per colony per tick (`maintainGarrisons`) while its bank holds a swarm's
    cost and its Core and Nexus stand (`canRebuildGarrison`): a banked donor is thin for 1-5 days.
    Delta Sonora I sent 150, 151 and 164 FP and fabricated 140, 164 and 139 on the next three passes
    (bank 604 -> 310). The lasting effect is the 30-day rest of the colonies that received.
  - A force staging against a system raises its pressure through B before anything sails, so the
    swarm reinforces the target first, and that system turns THREATENED and stops donating.
- **What it does not do.** There are no reply swarms: `ThreatResponseIntel` is the human task force and
  `ThreatSwarmDefend` holds conquered worlds. Retaliation comes only after a hive is eradicated
  (`hiveGroundVictory`: grudge +10, then `IncursionManager.retaliate` strikes the winner at once).
  Raids and strata add grudge (0.5 and 2), which reweights strike targets (`alarmTargetMult`);
  `ThreatRaiders.consider` hunts convoys only; ground fronts counter-attack on a timer
  (`frontCounterAttackDays` 40). Until 2026-10-04 pressed systems' forges sent no waves
  (`pickForgeSource`, `trySpread`) and the CONSOLIDATE stance (half the systems pressed, or the hive
  count falling while any is attacked; no dwell) left strikes to spoiling blows; both rules are now
  off by default (below).
- **The defence since 2026-10-04** - the swarm's restraints removed, strikes come home, the defence
  massed (`postureMass`, off), the system defending as one (`systemDefence`): `swarm-defence.md`.
- **Exposure - DEFENCE IN DEPTH** (the user, 2026-10-07, "yes lets build two suggested to start", after
  hw58-59 held 6.5-10k of 15-19k wanted while rear worlds sat on 11-14 fleets nobody came for). A quiet
  system's base is scaled by its exposure (`ThreatPosture.exposure`, `postureExposure`): want = max(one
  swarm, base x exposure, its share of need). Exposure is 1 while hostiles were seen in the system (the
  pass's `attacked`: attacks, hostiles present or ships lost; `S_SEEN` keeps the day) within
  `postureExposureSeenDays` (180), or the nearest human world the swarm has seen (`ThreatSwarmIntel.places`,
  forward bases included; `nearestKnownHumanLY`) lies within `postureExposureNearLY` (10); it falls
  linearly to 0 at `postureExposureFarLY` (30) and beyond, and is 0 with no human world known at all - so
  before the first scout report every hive holds one swarm a colony and banks the rest (more foundings,
  a bigger fund). Need is untouched: a pressed system has seen its attacker and reads 1. The rear's
  garrison then counts as surplus and flows to the exposed systems through the pressure pass (a donor
  gives above its want), a strike's gather takes it (tier 1 now), and nothing is rebuilt there. The swarm
  reads only what it has seen; with the fog off exposure is 1 everywhere. State fields `S_SEEN` (7) and
  `S_EXPOSURE` (8), `S_LEN` 9 - a 7-field reading loads as unread, once. Logs `Posture: <system> exposure
  0.00 -> 1.00 (hostiles seen 3 d ago | no hostile seen, nearest known human world 12 ly | none)` on a
  change of 0.25, and `x 0.40 exposure` after the base in the mode-change line. `exposure(system)` reads
  the last pass's figure. **The front** (`frontline`) is any exposure above 0: no strike takes a front colony below its
  want (`IncursionManager.stagedSpares`) and its patrols fly the corridor toward the humans
  (`ThreatSwarmPatrols.planRoute`) - the user, 2026-10-07, after hw59 stripped besieged systems for one strike.
- **Settings:** `postureEnabled` (true), `postureMargin` (1.25), `postureBand` (0.25), `postureDays` (5),
  `postureStagedShare` (0), `postureTriage` (false), `postureNeedAtAttack` (true),
  `posturePressedForgesHome` (false), `postureRecallLY` (10), `postureMass` (false), `systemDefence` (true),
  `systemDefenceMargin` (1.25); settings.json only, no
  LunaLib rows but `postureRecallLY`.
  State is primitive maps (`threatinc_posture`, `threatinc_postureLoss`, `threatinc_postureReceived`);
  the per-session wants are forgotten on load. A state of the older six-field layout reads as unread,
  so the first pass after loading an old save starts its pressure afresh, once (the 7-field layout
  adds `S_ATTACKED`). Day stamps are days since timestamp 1 (`today()`) - see the gotcha in
  docs/README.md.
- **Logs:** `Posture: <system> QUIET->THREATENED pressure N (staged ..., attacks ..., losses30d ...,
  hostiles ..., forward ...) want N (base N, need N) held ... (+N inbound) bank ...` on a mode change; `Posture: <donor> sent N FP to ...`; `Posture: X fabricated a N FP swarm for Y (B FP banked)`;
  `Posture: <colony> recycled a N FP surplus fleet`; `Posture: <system> written off` / `defensible again`;
  and a monthly `Posture sector: ...` line, ending `; forges paying n/N; banks <top 4 colony banks>`.
  NPC staging raises Threat pressure (docs/strategy-orbit-outposts.md "Staging").

Design rule and reasoning: docs/design-theory.md "Two design rules".

### Stance - what the surplus is for (2026-09-29, built, untested)

Posture decides what each system holds; `ThreatStance` decides what the rest is for. One stance for
the whole sector, evaluated at the end of every posture pass (`ThreatPosture.poll` hands it the pass).
`stanceEnabled` false, or posture off, reads as EXPAND everywhere (posture alone).

- **PRESS:** the hive outweighs a rival in reach and knows a weak world of theirs. **EXPAND:** the
  default. **CONSOLIDATE:** pressed at home, or losing hives under attack.
- **Breathing room:** no system under attack and sector pressure no higher than at the last pass (true
  on the first pass). It blocks CONSOLIDATE.
- **Enter and hold:**
  - CONSOLIDATE: no breathing room and (pressed share >= `stanceConsolidateShare` with at least
    `stanceConsolidateMinPressed` systems pressed, or all of them - `StanceRules.pressedEnough` - or hive
    count down over the window while a system is under attack). Held until the pressed share falls below
    0.8 x `stanceConsolidateShare` (the floor still applies). Checked first.
  - PRESS: not losing, pressed share < half of `stanceConsolidateShare`, and a weak target of a rival
    outweighed >= `stancePressRatio` exists. Held while that ratio stays >= 0.8 x `stancePressRatio`.
  - Else EXPAND.
  - A stance holds `stanceDwellDays` before it changes; entering CONSOLIDATE never waits.
- **Strength per rival:** the hive's held FP in the hive systems facing that rival (systems it stages
  against, attacks or guards forward bases facing, plus systems within fuel reach of a known world of
  theirs - billed reach, the systems that would strike that rival first, `ThreatReach.facedFaction`)
  over the rival's FP in reach (staged capacity, attacks running, forward guards, as posture read
  them). The rival with most FP in reach is the strongest.
- **Weak known targets:** each hive system not pressed at home, from its strike staging colony
  (`pickStrikeTarget`'s source), over known strikeable worlds (`swarmKnows`, `strikeAllowed`) within the
  staging colony's `fuelRangeLY` - billed reach, any whose passage the stock pays, the muster capped
  at what the spare supplies keep away and the score taken per day away; needs phase 2. Muster = its
  colonies' garrisons above minimum + banks.
  Odds = defence / (muster's strength x `siegeBreakOffRatio`), the strike gate's own figure. Weak when
  odds <= `stanceWeakOdds`. Ranked by `IncursionManager.strikeValue` x (1 - odds). Need = the fewest of
  the staging colony's heaviest size-table rows that bring the odds to weak.
- **Trend:** the sector attrition ledger (Threat FP lost, enemy FP sunk, `ThreatPosture.noteBattle`),
  decaying over 60 days. Losing = lost > 5% of held FP and killed < lost. Hive count change over the last
  90 days (live colonies).
- **Home pressure:** pressed share = THREATENED or BESIEGED systems over hive systems; breathing room
  as above.
- **Wiring:**
  - PRESS: each staging colony's want gains max(0, need - its stock) (`extraWantFP`); `claimCap` x
    `stanceSecondaryShare`; `pickStrikeTarget` weights worlds by `strikeTargetMult` (docs/strategy-orbit-outposts.md
    "Strike target weight").
  - EXPAND: `trySpread`'s candidate weight x `spreadMult` = sqrt(1 + LY to the nearest known world of the
    strongest rival), so seeding leans away from it.
  - CONSOLIDATE: `claimCap` 0; `redistributeByPressure` treats a THREATENED receiver as under attack
    (`feedsPressed`), so quiet colonies may feed it.
- **Settings:** `stanceEnabled` (true), `stancePressRatio` (1.5), `stanceWeakOdds` (0.5),
  `stanceConsolidateShare` (0.5), `stanceConsolidateMinPressed` (2), `stanceDwellDays` (30), `stanceSecondaryShare` (0.5), in `settings.json`
  and LunaLib. Persistent state, primitives: `threatinc_stance` {stance, day entered, sector pressure},
  `threatinc_stanceTrend` {lost, killed, day}, `threatinc_stanceHives` {day, live colonies, ...}. The
  targets and extra wants are rebuilt each pass and forgotten on load (`ThreatStance.forget`).
- **Logs:** `Stance: A->B - pressed n/n, attacked n, pressure ..., exchange ..., hives ..., rivals ...,
  best weak target ...` on a change and on the first pass after load; the monthly `Posture sector:`
  line ends `; stance X`.

### Fuel - the hive pays passage (2026-09-30, `ThreatFuel`)

Before this the swarm moved for free inside its fuel range while the factions paid for every
light-year from shipped stock. In the 75-month test (h12a) it shipped 1,015 swarms between systems,
grew to ~100k FP and massed 8-9k FP over any system a faction stocked a forward base against, while
no faction could fuel a hunt of that size. Now both sides pay the same rate.

- **Stock:** one for the whole hive (vanilla's broadcast availability already shares one fuel
  plant's output with every hive world). It fills by a faction reserve's rule
  (`accrualPer30` x `productionShare`): each world's fuel, `getSizeMult` of it x the fuel econ unit x
  `hiveSurplusMult` (1.0; the hive's own rate since 2026-10-02, split from `reserveSurplusMult`), a month, summed and held to the better of what the hive makes above its own
  demand and the sector's best exporter x `reserveBankImportsMult` (`ThreatFuel.perMonth`, accrued
  each `maintainGarrisons` poll). A faction world banks only what is left over its peacetime demand,
  its trade and civilian traffic; a hive world runs no trade fleets, so all its fuel is its fleets'.
  Read as surplus, the imports that meet a hive Megaport's demand banked 0 and grounded every fleet
  (first test). The cap made it ~9,000 a month on the test save, about one faction's banking. A save
  from before it starts with `FAB_ENDOWMENT_DAYS` of banking. Cutting the hive's fuel (plants or
  imports) now grounds it by stock as well as by range.
- **Passage:** fleet points / 25 x light-years x `expeditionFuelPerPointLY` - the factions' rate,
  which is their round trip. Strikes (`launchStrike`, the muster trimmed to what the stock fuels) and
  raiders (`ThreatRaiders.detach`, to the convoy) pay both ways; Seeding Swarms
  (`launchColonizationWave`) and reinforcements (`sendReinforcement`; the pressure pass's donor and
  fabricator picks skip what the stock cannot fuel) stay where they are sent and pay the way out
  only (`RETURN_LEG_SHARE`). Scouts pay one way along their whole route and home. Same-system moves
  are free. Nothing is refunded.
- **Founding (2026-09-30):** a Seeding Swarm carries what a faction's forward base costs
  (`ThreatOutposts.npcCost`: `outpostSupplies` 1,500, `outpostFuel` 800) on top of its hulls,
  structures and passage - a single swarm founded a hive for its structures' fleet points alone. The
  hive keeps a supplies stock beside its fuel, filled by the same rule (`ThreatFuel.perMonth(id)`;
  a save from before it starts with `FAB_ENDOWMENT_DAYS` of banking). `launchColonizationWave` waits
  while the stocks cannot pay (`canFound`), `loadFounding` draws them onto the fleet's memory, and
  they go into the colony when it is founded (`settleFounding`), back into the stocks with a wave that
  withdraws, and down with a wave shot down (`refundFounding`, `unloadFounding`). First test (18
  months): supplies bank 4.5-7.5k a month and pile up (126k) with nothing else to spend them on;
  fuel, spent on passage as fast as it banks, is what holds waves (5 in 18 months).
- **Setting:** `threatPaysPassage` (true), in `settings.json` and LunaLib; it gates the founding
  cost too.
- **Logs:** `Hive stock: <what> held, N fuel and M supplies in stock` (once per kind), and the monthly
  census line ends `; fuel N (+M/mo, spent S); supplies N (+M/mo, spent S), sends held H`.
  `Hive stock sources: <c> makers, output, available, own demand, made` (units) follows each census.
- **A wave that stalls (2026-10-06, hw34b):** a Seeding Swarm neither founded nor dead
  `seedingWaveStallDays` (180) plus 30 a light year after launch (`WAVE_LAUNCH_KEY`, `WAVE_LY_KEY` on
  the fleet) is re-steered once (`Wave re-steered`); stalled again, a bootstrap wave is moved into its
  system beside its planet (`Wave unstuck` - the Abyss's incursion, before anyone can see it) and a
  colony's wave is withdrawn and refunded (`Wave stalled`). A wave in a battle or held by an outpost
  does not count. hw34b: the bootstrap wave to Blue, the Ala system's volatiles world, sat in the
  system from war day 159 to 3579 with every hull; `tryBuildLink` builds no fuel plant without
  volatiles in the hive, so the hive had fuel 0 for ten years and nothing could sail - no seed, no
  scout, no strike - and the humans never learned it existed.

### Parity - wartime fuel, plants for shortages, supplies upkeep (2026-09-30)

- **Fuel at the war rate (A, replaced by structure costs below).** A hive plant's whole output
  counted, as a faction's does under `reserveWartimeFuel`. h32a (60 months from the base save): the
  hive went from 3 plants to 17 by month ~25, banked 108-186k fuel a month (4.8M stock) and took the
  sector - 91 hives, 142k FP, the Hegemony down to 6 colonies, the independents gone. Back on the
  surplus rule (h33a) a hive Spaceport's fuel demand (size-2) eats a same-size plant's output: 21
  plants banked 0, and the hive's 9k a month was the sector's best exporter x
  `reserveBankImportsMult`, whatever it built. Supplies the same: 4 forges made 24 units against 199
  wanted by Spaceports.
- **Plants for shortages (B).** A send held for fuel (`held`, not a Seeding Swarm) or founding short
  (`canFound`), or supplies upkeep unpaid, notes the stock short for `SHORT_DAYS` (30;
  `noteShort`/`shortOf`). `planHiveEconomy` answers after the bootstrap with a fuel plant or a forge,
  one at a time: each gets `SHORT_DAYS` to show before the next (`mayAnswer`/`answered`). h29a,
  unpaced, built 20 fuel plants in one tick and 25 forges in two months. Replaced 2026-10-01 by
  the trailing demand ("Idle stock" below): only unpaid colony sustenance still notes a shortage.
- **Supplies upkeep (C, `threatSuppliesUpkeep`, true).** Threat fleets away from home - raiders,
  reinforcements in transit, and everything on the ledger (strikes, waves, scouts) - burn their
  hulls' vanilla supplies a month (`ThreatFrontlines.maintenancePerMonth`) from the hive stock
  (`paySupplies`), as the factions' fleets on the war's orders do (`ThreatUpkeep`). A garrison at
  home is the hive's patrol: vanilla feeds patrols from the market's own supplies demand, which the
  hive's structures already take, so it keeps the FP upkeep (`upkeepPerDay` on `garrisonFP`).
  What goes unpaid notes supplies short and is owed; a month's worth owed turns the colony's
  raiders home and aborts its strikes (`starveAway`, "Swarm Out of Supplies"; logged `Upkeep: X owes
  N supplies for its fleets away`). A month paid in full clears the debt.
- **First try, garrisons paying too (h29a from h26 month 50, 5 months; h30a 3 more).** The 50k FP swarm burned 29-39k
  supplies a month against 4.5-6.75k banked; the 310k stock ran out in 11 months and the recycling
  took the swarm to 8-17k FP, with 70-137k FP banked and unspendable. Forges do not help:
  37 forges make 178 units of supplies, the hive's own structures (spaceports, stations, defences)
  demand 208, so 6 units a month reach the stock. Fuel went the other way: 27 plants, 138 units,
  ~200k a month banked, 4.9M in stock. (The 208 is Spaceport demand, size-2 a hive - not the
  stations' or defences', which demand none.)
- **The navy above the patrols pays supplies (2026-10-07, the user; `payNavySupplies`).** A hive's
  garrison FP above its patrol figure (`ThreatPosture.minimumFP`) - its launch stock and spare, the
  hive's counterpart of a faction's built hulls - pays the swarm's maintenance per FP x
  `standingUpkeepMult` a month from the hive stock, for the days `paySupplies` charged; the unpaid is
  demand and owed (`KEY_NAVY_OWED`), and once the owed reaches the smallest swarm's month that swarm
  is lost, never below the patrols. Month line: `Upkeep month: … navy upkeep X of Y supplies, lost K
  swarm(s) …`. "Might as well charge them supplies if threat has surplus anyway" - the swarm sat on
  0.4-1.4M supplies on the first sector (hw40-41), so it binds there not at all.
- **The garrison pays no supplies (2026-10-06, the user, after hw36-37).** One evening charged it
  (`payGarrisonSupplies`: garrison FP x the swarm's maintenance per FP from the hive stock, the
  smallest swarm lost once its month was owed) and then gated its growth on the hive's supplies
  surplus (`suppliesKeepSwarm`). hw36a: grown to its want on forge FP, the garrison took every supply
  the hive made (76k/mo, 43 forges built to feed it), its strikes starved, no war in 3,750 days;
  hw37: both navies under the supplies line, the swarm spending all it made on a garrison it could
  not use, humans 3-0 (`game-runs-2.md` 36-37). Cut: the garrison is vanilla's patrols - the Nexus
  sets the patrol table a human Military Base sets, and a hive market's shortages cut its fleet size
  and quality as a human world's do - and vanilla keeps it. The mod's stocks pay the mod's actions:
  fleets away (above), structures, waves, strikes; the navy a faction's yards build beyond its table
  (`ThreatHulls.maintain`, `docs/hull-pool.md` 2); the hive's navy above its patrols the same (`payNavySupplies`, above). A held strike now books its supplies shortfall as
  demand (`ThreatFuel.held`), as its fuel always did - kept from that evening.


### Structures cost supplies (2026-09-30, user's call; `ThreatBuildCost`)

The Threat conjured industries: a structure cost one founding's FP and stood at once, and the stocks
filled from the sector's best exporter whatever the hive built - so cutting a fuel plant or a forge
changed nothing it could afford, and the planner copied every industry into every system. The two
sides are not the same war (the Threat has years to spread before the factions mobilise), so this
is asymmetric by design where it has to be, and the one rule where it can be.

- **Cost.** A structure costs its vanilla build cost in credits at the supplies base price (100),
  times `structureSuppliesMult` (1.0): Heavy Industry 5,000, Fuel Production 4,500, Refining 2,250,
  Mining 1,000, Orbital Works 3,000 (an upgrade), Patrol HQ 3,000, Military Base 4,500, a station
  2,500-10,000. The hive's ground defences and heavy batteries are priced as vanilla's (1,500 /
  3,000). Logged once each: `Build cost: <id> N supplies (spec cost C, D days)`. Credits were ruled
  out - nothing the player can hit. Metals were ruled out too: humans would have to stockpile them,
  and cutting metals already bites through Heavy Industry.
- **Time.** Vanilla build time (`startBuilding`; Orbital Works by `startUpgrading`, the forge
  running on meanwhile). A structure under construction supplies nothing.
- **The hive** (`buyStructure`, `affordStructure`) pays from its supplies stock; a build it cannot
  pay waits (`buildWaiting`, the cost in `KEY_BUILD_WAITING_SUPPLIES`), notes supplies short, and
  `buyWaitingStructures` retries once the stock holds it. Garrisons no longer wait behind a build:
  fleet points are for hulls. Founding structures cost no FP (`foundingFP` is 0): a Seeding Swarm's
  `npcCost` supplies and fuel pay for them, as a forward base's founding does. Free builds (save
  heals, the debug war) stand at once.
- **The hive earns what it makes** (`ThreatFuel.perMonth`): every plant's and forge's whole output x
  econ unit x `hiveSurplusMult`. No Spaceport demand is taken off - it stands for trade traffic
  and a hive runs none - and no import fallback, as a hive does not trade. At the base save that is
  4 forges (24 units, ~18k supplies a month) and 8 fuel plants (48 units, ~72k fuel).
- **Forward bases** (`ThreatFrontlines.startNew`, `upgrade`, `payBuild`) pay from the link's supplies
  above its floor and staging bank, then the faction's other markets in reach (what a hunt may
  take, `payFromOthers`). One project at a time, as before: an unaffordable one waits
  (`Frontline: X waits on N supplies for <id>`). The swap of a fuel plant for a Heavy Industry
  checks the price before it tears the plant down. Founding stays `npcCost`.
- **Setting:** `structuresCostSupplies` (true). Off restores the FP costs, instant hive builds, free
  link builds and the surplus-and-imports stock rule.

### Size upkeep - growth is paid for (2026-09-30, user's call; `ThreatColonyUpkeep`)

Growth was the last free thing: a hive grew a size every 60 days x size at full vitality whatever
it cost, and the base save's hive stood on 33 size-8 worlds, 30 of them without a forge. Now a
colony pays for its size and grows on what it is paid - the same rule for a hive world and a
forward base, commodity-bound the way vanilla's economy is.

- **Upkeep.** Size 3 and up costs `sizeUpkeepAt3` x `sizeUpkeepRatio`^(size-3) supplies a month:
  100, 250, 625, 1,563, 3,906, 9,766 for sizes 3-8. A ratio of 2.5 is population^0.4 - vanilla's
  population is x10 a size, which nothing could pay. Sizes 1 and 2 are free: a seed costs nothing
  until it is a world, and a new chain has no income to pay with.
- **Growth is the share paid** (`growthRate`). At `upkeepBreakEven` (0.5) of its upkeep a colony
  holds its size; paid in full it grows at the old pace (60 days x size a size); below break-even
  it starves through a level every `starveDaysPerSize` (90) paid nothing. Progress is continuous
  across a size lost: a short siege costs progress, a long one sizes, and what is lost regrows at
  the growth pace. The size goes half a level below it (`SHRINK_MARGIN`), so a colony just grown
  does not lose it to the first lean week and one that loses it lands halfway back; a world at
  its cap banks a full level while fed. A fed size-8 fortress paid nothing holds 135 days, then
  loses a size every 90; regrowing 7 to 8 takes 420. A ground front or saturation holds growth,
  never hunger. h35a, before the margin: the base save's 23 capped size-8 worlds, progress 0, all
  lost a size in the first week. The Fabrication Core no longer gates growth. Vitality
  (fabrication x supply) is retired with it: reach is the bill (below) and a colony's
  counter-attack pace is the supplies it is paid.
- **Feeding order** (`feed`, each poll, out of the production of the days fed after the fleets
  away are paid):
  1. Sustenance: the break-even share of every colony, forge worlds first, then the worlds nearest
     the humans - up to the largest stance share of the production (`sustainShare`, 0.7), never
     more. The hive always keeps a tithe for forges, waves and fleets: h35a let sustenance take
     everything, and the base save's hive shrank until it did (26k of 29k a month) with nothing
     left to buy the forges that would have fed it again. The leeway is the half level below its
     size a colony's progress runs before the size goes (below), not the stock: a raided forge
     stops growth at once and costs sizes only if it stays down.
  2. Growth: the rest of each colony's upkeep, out of its stance's share (`feedShare`: expanding
     0.5, pressing 0.7, consolidating 0.5), to a colony only while that share still holds its next
     size's sustenance. Forges whose next size adds more output than sustenance first; then
     pressing feeds the front, consolidating the biggest worlds, expanding the smallest. A stance
     change never starves anyone - sustenance keeps its 0.9 - it only moves what growth gets.
  - A size-2 world grows into size 3, where upkeep starts, only as it is fed for it (priced at
    size 3's upkeep); a size-1 seed and a forge world below size 3 grow free. h35a let size 2
    grow free: 76 worlds grew into size 3 unpaid and starved back.
- **Blockades and ports.** What a world's own forge does not make it imports. A human blockade over
  it cuts that half or all (`ThreatBlockade.hiveCut`, docs/ground-war-defenders.md "Blockade") and a disrupted
  port halves it (`importCut`). A blockaded forge world keeps what its own upkeep takes and the rest
  of its output cannot leave (`reachesStock`, in `ThreatFuel.perMonth`). So a blockade starves the
  worlds that import; a forge world declines once its forge is raided too.
- **Founding.** The forge no longer retools after a launch (`retoolForge` is skipped): a wave's
  price is its cargo and its swarm. The cargo carries the colony's four structures too
  (`ThreatBuildCost.foundingKit`: the Spaceport 500 and the Swarm Nexus priced as the Patrol HQ it
  stands in for, 3,000; Population and the Core cost nothing in vanilla) - 5,000 supplies with
  `npcCost`'s 1,500 - and the colony's first build is bought from the stock like any other.
- **The opening chain's first forge** (`SEED_FORGE_KEY`). Each landing's one free build is Mining
  wherever there are deposits, and a chain may be deposit worlds alone: with structures paid in
  supplies and supplies made only by forges, such a hive could never buy its first forge (the
  stock starts at 0). `launchOGChain` marks the leanest planet that is not the chain's best ore,
  rare ore or volatiles world (else the leanest), and it lands with Heavy Industry, free. The
  Pristine Nanoforge follows onto it (`maintainHomeRelics`).
- **Sieges** (`ThreatPurgeFGI.raidValue`): a working forge is the top prize (100); the Core drops to
  the Nexus's 60 - it still halts the swarms, not the growth.
- **Readouts.** Hive Vitality's tooltip: the share of the month's upkeep paid, any import cut, and
  the growth pace or the days to the next size lost. The board's size line counts down
  (`s5 -> s4 ~40 d`). Census: `Colony upkeep: bill B (N/mo), paid S sustenance + G growth, short X;
  stance S share F; growing a, holding b, shrinking c, free d; blockaded e, ports down f`. A size lost
  logs `Starved a level of X: size a -> b`; a blockade's change `Blockade of X: imports cut N%`.
- **Why these numbers** (h31a-h34a logs and saves, vanilla's source). A forge makes 750 supplies
  and 100 FP a month per unit, size-2 of them (+1 Corrupted Nanoforge, +3 Pristine); Orbital Works
  makes the same; nothing else makes supplies and a colony counts one forge. Against that straight
  line an exponential curve gives every forge world a best size and a size where it stops paying
  for itself. At these settings a forge's next size pays for its sustenance up to size 6; a plain
  size-8 forge cannot hold itself (4,500 made, 4,883 sustenance), only the Pristine one can; a new
  chain of five with one forge reaches size 5 with a surplus to buy its second. The base save's 33
  size-8 worlds ask ~161k a month of sustenance against ~16k made, so it sheds toward forges at 6
  and the rest at 4-5. A faction banks ~7k supplies a month; a size-4 link's sustenance is 125, a
  size-6 link's 781. The one-off growth fee first proposed would have cost h34a 2.6k a month, 13%
  of its output - too little to change a decision.
- **Tests** (5 minutes each from the base save, ~19 months).
  - h35a (sustenance drawn from the stock, no margin, size 2 growing free): 23 capped size-8
    worlds lost a size in the first week; the hive shrank until sustenance took 26k of its 29k
    a month and bought nothing more; 76 size-2 worlds grew into size 3 unpaid and starved back.
  - h36a (the tithe, the margin, paid growth into size 3): size 269 -> 192 over ~13 months, then
    held - sustenance ~31.5k of 35-39k a month, nothing short. The tithe bought forges: supplies
    income 15.75k -> 39k a month, FP income 2.1k -> 5.2k. Fleets 101k -> 44k FP as the garrisons'
    sizes fell (83k FP banked). No world bounced between sizes; links grew without flapping;
    humans blockaded Ohai (224 FP over the swarm's 21) and Vassago (1,447 over 773). No colony
    grew: an oversized hive holds at sustenance's 0.9 edge until its forges lift production past
    its stance's share. No exceptions.
- **Settings:** `sizeUpkeep` (true; off restores vitality growth, retooling, the free founding
  structures and links that grow on shortage-free days), `sizeUpkeepAt3` (100), `sizeUpkeepRatio`
  (2.5), `upkeepBreakEven` (0.5), `starveDaysPerSize` (90), `feedShareExpand` / `feedSharePress` /
  `feedShareConsolidate` (0.5 / 0.7 / 0.5; consolidating was 0.9 until the simulator's round 20,
  `war-sim-rounds.md` 16 - the largest of the three is `sustainShare`).

