# Staged strikes and the offensive - how the swarm attacks

One-line answers: `facts.md` ("What makes the swarm take human worlds", "What paces the swarm's
strikes"). Run records: `game-runs-2.md` 17-28 (the staged trials), 42 on (the offensive).

## 1. A staged strike (`IncursionManager.stagedPlan`, 2026-10-05)

A strike is built for its target from the hive's strike fund at a staging world; the garrisons
stay home and nothing is recalled.

- **Staging** (`ThreatColonyManager.pickStrikeStaging`): a hive system's biggest colony of size
  `strikeMinSize` (4) or more with a ready Nexus, ships over it and two spare swarms.
- **Size**: `strikeStagedMargin` (1.5) x the defence last seen at the target
  (`targetDefence`: the swarm's fog, `ThreatSwarmIntel.place`; a world never seen is no
  candidate), at least two swarms, every swarm built from the bank at the staging colony's
  size + 3. The war's OPENING strike - no faction mobilised - sails with at least
  `strikeStagedMinFP` (8,000) so the hive is strong before the first attack wakes the humans
  (the user, 2026-10-06); every later strike is sized to its target.
- **Paid** from the strike fund (`ThreatColonyManager.strikeFund`): `strikeFundShare` (0.3)
  of every colony's fabrication never reaches its bank. `spendStrikeFund` moves the bill to the
  staging colony, `launchStrike` draws it. With no fund the pooled banks pay.
- **Reach is the bill**: the passage fuel (`ThreatFuel.passage`, round trip) must be in stock
  and the spare supplies must keep the strike away `ThreatReach.strikeDays` (`canSustain`).
- **Candidates** (`stagedCandidates`): strikeable, open to war at the phase (`warOpen`), not
  under a strike, known to the swarm with a defence seen. Forward bases are worlds like any other.
- The strike sweeps every eligible world of the target system (`launchStrike`), a relief strike
  only its front.

## 2. The offensive (`ThreatOffensive`, the user, 2026-10-07)

Before it (`tryStagedStrike`, `swarmOffensive` off): one strike a monthly pass, the closest
payable target of the whole hive. hw41a: 122 strikes in 125 months, 62 of them under 1,000 FP
at 120-defence forward bases, the fund 2-7k FP, 268 waits on a closest target it could not pay
while 107k FP of garrisons stood - no strategy, a trickle. The user: "they should go through
periods where they are saving and then do a multi pronged attack on all known systems ... if
they are losing ... focus on nearby bases only ... it depends on their resources vs the known
resources of their enemy."

Every monthly pass (`ThreatOffensive.pass`, from `IncursionManager.tryStrikes`):

1. **Relief first**: a front of the swarm's own short of troops (`ThreatGroundFronts.
   wantsExpedition`) gets its strike at once from the nearest staging system, outside the
   campaign (`strikeReliefFirst`).
2. **Prongs**: one a target system, at its most-defended known world, from the nearest hive
   system that can stage, priced by `stagedPlan` against an unlimited bank (the fuel and the
   supplies away still gate it). Score = `strikeValue` x the stance's `strikeTargetMult` /
   cost (value per fleet point).
3. **Losing, by degree** (`ThreatStance.losingPressure` p, 0-1, section 4): the reach shrinks
   from the farthest known target toward `offensiveNearLY` (10 ly at p = 1); a forward base or a
   base staging against a hive weighs 1 + 9p; the score is divided by ly^p. Nothing within the
   reach: all known.
4. **Means**: the fund now + `strikeFundPerMonth` (the share of today's fabrication) x the
   months to the campaign's **deadline** = its start (the last launch, `threatinc_offensive`)
   + the horizon, `offensiveHorizonMonths` (12) shortened toward `offensiveLosingMonths` (3) by p.
5. **The campaign** is every prong, best score first, whose bills the means pay by the
   deadline. Nothing sails until the fund holds the whole bill - the log reads `Offensive:
   saving for N of M target(s) ... launch in K month(s)` - then every prong launches in the
   same pass (`Offensive launched: N strike(s) ... at ...`), each paid from the fund as
   `launchStrike` does. At the deadline the fund pays what it can and a new campaign starts.
   A set the fund already pays launches at once, so a swarm richer than its known targets
   strikes them all every month. A prong that burns no new supplies (spare swarms, whose home
   charge moves with them) is priced in whatever the flow, as `canSustain` keeps it - until hw54 the
   pricing's two supplies checks had no such floor, so with the spare negative (hw54b: -2.7k a month,
   sustenance and the navy charge over the output) a campaign of 7.6k FP of spare swarms needing 0
   supplies read "nothing the fund pays by the deadline" for 1,850 days while the fund held 36k.

The deadline counts from the campaign's start, not the pass: a plan sized by "fund + 12
months of income" every month would grow as the fund did and never launch. That is what hw42-49 did
anyway: "no start yet" was -1 against a calendar of day -642,000, so the start was reset every pass,
every saving line read "deadline in 12", and the deadline's launch never ran (NaN since hw50). The
pricing's spare flow is signed, as `canSustain` reads it (floored at 0, it passed sets the launch gate
refused); the per-faction prong counts are rebuilt each time the tail is dropped; the prongs sail in the
order the spare was shared, the farthest first. Dropping while the means no longer pay takes first a prong no
budget pays on its own (hw58c: a 19k FP prong beyond the fuel budget sat at the head while the tail went one
by one, "nothing the fund pays" for months with 44-47k in the fund - fixed 2026-10-07 for hw61), else the tail. The opening
floor stays per strike, so an opening campaign is one or more 8,000 FP prongs; with a
12-month horizon the war opens later than before (the fund saves for more than one) - the
first thing to read in the runs.

**Arriving together** (the user, 2026-10-07): at launch the farthest prong sails and every other is
held for the difference in its arrival (`ThreatOffensive.arrival`: the muster `ThreatReach.STRIKE_PREP_DAYS`
plus the crossing at the board's estimated speed), its bill taken out of the fund and put back the day it
sails (`poll`, daily from `IncursionManager.advance`; `threatinc_offensiveSchedule`). A held prong whose
target or staging is gone, or that the fund cannot pay on its day, is dropped with its bill refunded. A
world with a held prong is no candidate (`scheduled`).

**Sized for the answer, not the day's patrols** (the user, 2026-10-07: "what determines what a world
answers with? ... the threat wouldn't know what that is unless it knew all worlds"). A strike meets
the system's fleets on the day (`liveTargetDefence`, what a scout records - a core world's patrols are
74-290) and then the faction's response from its free hulls, thousands of FP the swarm cannot see. So a
prong is priced for `expected` = (the defence seen + the faction's **navy seen elsewhere**
(`ThreatSwarmIntel.knownNavyFP`: every place of the faction's, defence + guards + staged, bar the target's
system) shared among the campaign's prongs at that faction) x the faction's **answer ratio**
(`responseRatio`: what its worlds met the swarm's earlier strikes with over what they were sized for,
`ThreatStrikeFGI` samples the hostile strength daily while in the system - an off-screen strike by its
route's place - and at the off-screen fight (`noteMet`), reported on ending; decayed 0.7 a strike).
Unfloored since hw60 (2026-10-07): floored at 1 the swarm never learned that the humans answer with
less - hw60 met 0.41-0.61 of sized over 240 strikes, every prong twice its need and twice its supplies
away - so the ratio now runs both ways and `price` takes the larger of the defence seen on the day and
the priced answer: never under what stands there, never again double it on a memory. Until hw50 no strike on the second sector ever reported: "not sampled yet" was a day of -1 and
that sector's clock reads day -642,000, so every strike of hw42-49 "ended with no read" and the ratio
stayed 1 (a flag, `metRead`, since). Splitting a faction's navy among the prongs is
the multi-prong's payoff. The campaign is priced once at the candidates' counts and again at its own
(`price`), dropping from the tail while the means no longer pay it; `launchStrike(..., expectedDef)` sizes
the real strike for the same figure (log: `defence expected N`).

## 3. The spare garrison in the prongs (`strikeStagedGarrisons`, the user, 2026-10-07)

"Spend it": the navy above the patrols goes on the offensive rather than sit at home paying the
navy charge. On since 2026-10-07 (hw48).

- **Up to the need**: with the offensive on, `stagedPlan` takes the hive's Defense Swarms
  (`stagedSpares`, below) only until the strike reaches its need (two swarms, the opening
  floor, `strikeStagedMargin` x the expected answer), then builds the rest from the fund. Off
  (one strike a pass), the old rule: every spare the means pay, "the mass".
- **Where the swarms come from** (`IncursionManager.stagedSpares`, the user 2026-10-07: "priority pull
  from safest, then from those highest above posture threshold ... no hard never take, just weighted ...
  prefer not to strip a world clean"): every colony of the hive offers its fleets in two tiers - the
  fleets above its posture want (tier 1; above its reserve with the posture off; what stands on station
  alone since hw60 - `ownedFleetFP` counts reinforcements still in flight, and hw60b's Chlorr with 430 FP
  inbound offered its whole station and was stripped to zero, then eradicated) before any colony's
  fleets below it (tier 2, down to its last fleet) - and within a tier the colonies go by score,
  `threatinc_spareGatherDistanceShare` (0.55) of the colony's distance from the nearest known human
  world (the safest first: the humans strike what is near them) and the rest its surplus (fleets above
  its want in tier 1, live fleets in tier 2), each against the hive's largest of that tier. One entry a
  colony a tier; `launchStrike` musters each entry from its colony, largest fleets first, with no
  availability gate again (`ThreatColonyManager.consumeFromColony`). The offensive's held prongs'
  earmarks come off the least preferred end. Until hw58 the systems nearest the STAGING gave first,
  each to its reserve and nothing while regrowing: three frontline systems staged every strike and were
  milked to 1-2 fleets - Strathcona gave 46 fleets in 22 musters - while a rear world sat on 11 (hw58a).
  `threatinc_strikeStagedGatherLY` (0, off) skips entries farther than that from the staging.
- **Shared in a pass** (`ThreatOffensive.Spares`): prongs are ranked each against the whole
  spare (a prong's size is the same whoever pays it; its price is its fleet points plus the
  supplies its trip burns beyond the spare's home charge), then chosen in rank order, each re-priced on
  what the earlier prongs left. The chosen set is priced once more in **sail order**, the farthest first
  (hw48: each muster takes the first fleets of a system's walk on its day, so a set shared in rank order
  met bigger fleets on its days than it was priced for). `price` accepts a prong the spare pays alone.
- **Held prongs earmark** their fleets (`earmarked`, the schedule entry's 8th field,
  `system:fleets;...`): `stagedSpares` leaves them out for every other strike until the day,
  when the prong re-plans with what is there; a system with an earmark donates no garrison
  (`redistributeGarrisons`' donor loops) and recycles none for upkeep (`recycleForUpkeep`) until it
  sails (hw50c: eight sends drained the staging system in the days after a launch, and all eight held
  prongs re-planned bigger from the fund and were refused on supplies). At launch the held prongs are scheduled first,
  then each prong sailing now launches with the spare the others leave it (`PENDING`). On its day the
  prong's own earmark is offered to it whatever its systems' reserves read
  (`IncursionManager.setLaunchingEarmark`, read by `stagedSpares`): the earmark kept every other
  taker off those fleets, but `ownAvailableForLaunch` reads a colony that gave the earlier prongs its
  swarms as "regrowing" and offers nothing - hw54c, a 3-prong campaign of 25k FP of spare swarms, the
  third prong on its day "0 FP of spare swarms", re-planned 8.1k FP from the fund and refused. The count
  alone was not enough (hw56: 29 of 42 held prongs still found 0): the walk behind it,
  `ThreatColonyManager.musterPool`, gated each colony of the system on `ownAvailableForLaunch` too and
  fell back on the staging colony's own garrison, and a system whose staging gates (forge, fuel, nexus)
  had closed since the pass was skipped. Since hw57 the launching prong's earmark musters live fleets
  from every colony of the system (`IncursionManager.launchingEarmarkCovers`, read by `musterPool` for
  the peek and the consume alike), its staging pick is ungated (`pickStrikeStaging(id, false)`), and
  each earmarked system logs "Earmark at X: N fleet(s) set aside, M found (...)". That line showed the
  last layer (hw57a: "13 found", "0 FP of spare swarms"): `stagedPlan` gates each spare fleet on
  `canSustain` as it adds it, a prong of spare swarms alone prepays 0 supplies, and the gate was skipped
  only for a prepaid amount above 0 - the first fleet's re-embodied estimate, a few FP over its own, was
  a "new burn" against a negative flow. Since hw58 the prepaid MARK skips it whatever the amount
  (`ThreatReach.setPrepaid` / `clearPrepaid`), and the pass's prongs sailing now launch under the same
  mark and the priced-size cap as the held ones: the campaign's supplies check stands for its prongs.
- **Supplies**: the navy charge the garrisons above the patrols pay at home is in the feed's spare
  (`ThreatColonyUpkeep.navyChargePerMonth`, since hw50 - before, the spare left it out and was overstated
  by the whole charge, hw48's "credited in full, the strikes away went unpaid" and hw49's -1,562 a month
  the month after a launch; two corrections layered on the symptom, `navyPaidShare` and a shed add-back,
  are gone). So a garrison swarm that leaves moves its burn: a strike's supplies away count only what it
  burns beyond its swarms' home charge (`ThreatReach.awayFP`, `standingUpkeepMult` capped at 1) in the
  campaign's sum, `canSustain` at launch and `ThreatReach.commit`, and the next feed reads the charge
  lower by itself. A trip with no new burn is always sustainable. Fleets away are paid before the navy
  charge (`maintainColonyGarrisons`). Nothing sailed: "none of N planned prong(s) could sail today", the
  campaign kept to its deadline.
- **Held prongs prepay their supplies** (hw52): the schedule entry's 10th field is the supplies its trip
  burns (the 9th, a month's, is for the Reach log), taken out of the supplies stock at launch and put
  back the day it sails, as its passage fuel is (`ThreatOffensive.heldSupplies`). hw49-51 held it as a
  flow instead (`ThreatReach.spare` kept the sum back): `canSustain` lets a trip short of flow draw on
  the stock, so in hw51 the sends refilling the staging systems (21 after one launch) and the first prong
  spent the stock the campaign was priced on, and 8 of 9 held prongs were refused on their days -
  "the spare supplies do not keep 651 FP away 138 days" with the spare at -1,086 a month. During a
  launch only the entries from before it count (every prong of the launch was priced against the whole
  stock). On its day the prong is not gated on supplies again (`ThreatReach.setPrepaid`, read by
  `canSustain`) and its plan is capped at the size it was priced at (`IncursionManager.setPricedFP`,
  the entry's 11th field; `stagedPlan` takes spare swarms and builds no further) - the campaign paid for
  that size and that trip. hw52 returned the supplies to the stock and gated the whole trip again, so
  each held prong alone was charged the flow the campaign as a set had made negative (-283 a month x
  five months against a stock under the founding kit), 8 of 9 refused; hw53 gated only the burn beyond
  the prepaid trip, and a plan grown past its priced size on the bigger first fleets of the day's walk
  (Gilead priced 1,136 FP, planned 1,468; Athulf 565 to 995) was refused against a stock at the kit.
  The earmark is a count of fleets a system (`earmarked`), so which fleets a held prong finds on its
  day is the walk's order, not the pricing's; the cap makes the difference a smaller take, never a
  refusal.

**The front is never stripped** (the user, 2026-10-07, after hw59: "frontline worlds should never be stripped,
whether humans know about them or not ... the point of the frontline is to be strong"). hw59a: one 10,460 FP
strike at Sentinel Forward Base (13 ly, a spoiling blow under the losing rule) took 52 fleets from all 15
colonies, besieged systems among them, and nine of them were eradicated in the months after - 18 of hw59a's 21
falls and 16 of hw59c's 19 followed a strip to zero (hw58a: 2 of 31). Since then tier 2 holds only REAR
colonies: a colony of a system with any exposure (`ThreatPosture.frontline` - within `postureExposureFarLY`
of a human world the swarm has seen, or hostiles seen in it lately) offers only what it holds above its
want. The weighting stays for the rear. Before a human world is known there is no front, so the opening
strike may still take everything.

## 4. Losing, by degree (`ThreatStance.losingPressure`, the user, 2026-10-07)

"How would you evaluate if the swarm is actually losing? likely more a series of worlds falling
not just a one off". Before: consolidating, or the 60-day exchange lost (5% of held, more lost
than sunk), or fewer hives than 90 days ago - hw47 flipped on its first lost opening.

p = the larger of two trends over `losingWindowDays` (365), `StanceRules.losingPressure`:

- **Hives**: fallen = the window's peak live colonies - now (`hivePeak`, from `hiveTrend`'s
  change points). One fallen is the cost of war (0); from two, fallen / (`losingHiveShare` 0.5
  x peak). A hive re-founded lowers it again.
- **Exchange against means**: Threat FP lost beyond enemy FP sunk over the window (monthly
  buckets, `threatinc_stanceTrendYear`), over what the forges made in it
  (`hiveFabricationPerMonth` x window, at today's rate) x `losingExchangeShare` (1.0). Nothing
  while it sinks as much as it loses.
- **A war's verdict**: 0 while no faction is at war. Consolidating no longer counts.

Read each posture pass (`evaluate`, stored in `threatinc_stance` [5-9]); a change of 0.1 logs
`Stance: losing pressure a -> b (n of a k-hive peak fallen, exchange -x of y made in 365d)`.
The stance's own PRESS gate keeps the 60-day exchange; nothing reads a yes/no "losing" any more.

## 5. Defeat in detail (`ThreatOffensive.chooseFocus`, the user, 2026-10-07)

"You always suggest a knob to turn, but you never come up with strategic ideas" - then "yes lets build
two suggested to start" (this and the posture's exposure, `hive-garrison-and-upkeep.md`). hw58-59's
campaigns were 300-5,700 FP spread over four factions each fielding 20k: a prong at each shared no
navy with any other, so the multi-prong's payoff (section 2, "Sized for the answer") was never
collected, and nothing was razed.

With `offensiveFocus` (true) the pass keeps a **focus faction** (`threatinc_offensiveFocus`, a faction
id) and the campaign is every prong at it - its known systems share its one navy, so each prong is
priced at `def + navy / n` with n the whole set. Other factions' prongs are dropped, except spoiling
blows while losing (a forward base, a base staging against a hive: `spoiler`); relief strikes and
retaliation are outside the campaign as before.

- **Kept** while the faction's worlds still answer - `ThreatSwarmIntel.answerShare` (what its worlds met
  the last strikes with over what they were sized for, the `noteMet` ledger unfloored, decayed 0.7 a
  strike) at least `offensiveBrokenAnswer` (0.5) - and the means still pay a prong there: the fund, the
  fuel AND the supplies away, as the campaign loop gates them. On fund and fuel alone (hw61a) hegemony
  was kept through nine months of "nothing the fund pays" with 114-118k FP in the fund - every prong at
  it too big to feed, and no prong at anyone else allowed. The empty-campaign line now names the best
  prong and what kept it out ("nothing the means pay ... the best prong, X (N FP), is out on the
  supplies away: ...").
- **Broken** below that share ("its worlds answered the last strikes with 0.31x what they were sized
  for"): its navy is spent, the swarm moves on. **Exhausted** when nothing known of it is left to
  strike (its known worlds razed, taken, under a strike or scheduled).
- **Chosen** (first, or after a break) as the faction the means cover most completely with the most
  value: each faction's prongs priced at its own count against the whole spare, taken best value per
  price first while the budget (fund + income to the deadline) and the fuel pay; score = covered value x
  (covered / total value). A big faction half covered beats a one-base faction fully covered unless
  the base is worth more. Null when nothing pays a prong anywhere (the stored focus is cleared).
- Logs `Offensive: focus on hegemony - the means cover 4 of 7 known system(s) (value 61 of 98); persean
  broken - ...` on a change; the saving and launched lines end `, focus <faction>`.

What it does not do yet: scouts do not look for the focus faction's unknown systems first, and a
focus with the fund already paying its whole set launches at it every month until exhausted (section
2's "richer than its targets" rule), which is the intent. The share and the choice rule are my
defaults.
