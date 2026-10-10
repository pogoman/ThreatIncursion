# Hive economy - posture and stance

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
- **The fleet count locks the quiet hive's spread (hw133, 2026-10-09, new seed from day 0):** no forge can launch a wave before the war: `ownAvailableForLaunch` lets a colony send only the fleets it holds above `garrisonReserve`, a count of swarms from the size table (half `desiredGarrisonCount`, 1-6), and counts FLEETS (`countLiveGarrison`), while under posture the builder stops once held FP reaches the want (`wantsGrowth`) - one or two fleets (the founding's ~700 FP seed fleet, grown fleets embodying several swarms). hw133a on the eve of the opening strike: Unhcegila 5 fleets against a reserve of 6, holding 2,018 FP of a 1,678 want; every colony above its FP want and under its fleet reserve, from day ~640 to the strike (`Spread target: none - candidates 176, no forge 176`). The strike's war unlocks it (claims within days of it). ck5's pre-war spread (from day 1,553, on the 2026-10-08 13:15 build) had pressure transfers between its hives (14 reinforcements arrived before the first claim) that gave colonies extra fleets.
  `IncursionManager.pickSpreadTarget` logs `Spread target: none - ...` with each forge's first failing gate
  (`ThreatColonyManager.forgeSourceBlock`). Fixed 2026-10-09 20:32 (the user: option A): under posture a colony launches what it holds above `minimumFP`, fitted largest first, never its last fleet; the fleet-count gate stays only with posture off. hw135. hw135-hw136: one founding fleet a colony (never its last) and the fit recycling the carved piece - `launchOrder` (count and muster read one fitted list), `splitLaunchSwarm` + `ThreatFleetComposer.splitOff` (a swarm's worth off the largest fleet when nothing fits), and the fit spares the launch stock (`fitFloor` + `stockFP`, `launchStockFleets`) and wants only supplies-held sends. hw137.
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

**The line (S1, 2026-10-10, after hw148b/c; `threatinc_postureLine` true, `threatinc_postureLineDays` 365).**
An exposed colony needs at least the siege the swarm has seen come: `ThreatSwarmIntel.siegeLineFP` is, for each
faction, the median FP of the sieges first seen bound for a hive system within the year (`recordSiege` from
`note`, the last 40 per faction kept in the intel store's `sieges` section), and of those the strongest faction's;
the pass (`ThreatPosture.poll`, where wants and needs are set) raises every colony's need to that x
`siegeBreakOffRatio` x `postureMargin` x the system's exposure. A need, not a base: the losing hold and defence
first pay it from the strike fund (`defenceShortFP`), a strike called home covers it, the forge builds to it
(`maintainGarrisons`), and the want follows. The quiet core (exposure 0) wants nothing more; the reserve rule
is untouched. Why: hw148b lost 19 frontier hives in 300 war days to sieges of 0.9-1.9k FP (median by faction)
landing on garrisons of 76-1,200 FP - vanilla's patrols for the size - with the defender below the siege in 77
of 78 fights, while the fund held 66-96k FP and 33k FP of garrison sat in three quiet systems at pressure 0.
A siege turns home only at `siegeBreakOffRatio` x its FP in orbit, so the hives were lost on the ground, not in
orbit (the exchange ran 1.5:1 the swarm's way). Logged once per pass and system as `Posture: the line at ...`.


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
    outweighed >= `stancePressRatio` exists - or the chest is full. Held while that ratio stays >= 0.8 x `stancePressRatio`.
  - Else EXPAND.
  - **The chest (2026-10-08, after hw62; built for hw63, to confirm):** the strike fund holds a
    horizon's saving (`ThreatColonyManager.strikeFundPerMonth` x `offensiveHorizonMonths`) and a known
    target exists. **And, since hw70 (the user's pick, 2026-10-08), the fund could field the cheapest
    priced prong the fuel in stock can sail (`ThreatOffensive.cheapestProngCost`): a 4-hive fund saving 150 FP a month read full at
    1,834 of 1,800 with the cheapest prong at 8,244 FP, fed the fleets before expansion for 1,400 pre-war
    days, and ck3's swarm opened the war at 13 hives where ck2's (checkpointed before the rule) opened at 37.** hw62 banked 38k -> 141k FP in every game and fielded none of it: the colonies'
    spare for new trips kept sustenance at its share (an expansion tithe of 1/`sustainShare`), and
    seedings took 5k each, so spread took every month's supplies while hw62a's hives fell 76 -> 47.
    Full (`ThreatStance.chestFull`, held until the fund is spent below `CHEST_LEAVE` 0.5 of the
    horizon), whatever the stance: the feed's spare is the production less the fleets away, the navy
    charge and what the colonies actually took (`ThreatColonyUpkeep.feed`), and nothing is founded
    the supplies surplus does not pay (`ThreatFuel.canFound`). State: `threatinc_stance`[10]. Log:
    `Stance: the chest is full|spent - N of M FP a horizon; the fleets are fed before|after expansion`;
    every stance line carries `chest N of M FP a horizon (full)`.
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


### Fuel, parity, structures, size upkeep - moved

The hive paying passage, wartime parity, structures costing supplies, size upkeep and the feeding order:
`hive-stocks-and-upkeep.md` (split out 2026-10-09 at 43.5 KB).
