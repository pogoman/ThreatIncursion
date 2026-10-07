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
3. **Losing** (`ThreatStance.losing`: consolidating, the exchange lost, or fewer hives than 90
   days ago): only targets within `offensiveNearLY` (10) of a staging hive system; a forward
   base or a base staging against a hive weighs ten times; the near first.
4. **Means**: the fund now + `strikeFundPerMonth` (the share of today's fabrication) x the
   months to the campaign's **deadline** = its start (the last launch, `threatinc_offensive`)
   + `offensiveHorizonMonths` (12; `offensiveLosingMonths` 3 when losing).
5. **The campaign** is every prong, best score first, whose bills the means pay by the
   deadline. Nothing sails until the fund holds the whole bill - the log reads `Offensive:
   saving for N of M target(s) ... launch in K month(s)` - then every prong launches in the
   same pass (`Offensive launched: N strike(s) ... at ...`), each paid from the fund as
   `launchStrike` does. At the deadline the fund pays what it can and a new campaign starts.
   A set the fund already pays launches at once, so a swarm richer than its known targets
   strikes them all every month.

The deadline counts from the campaign's start, not the pass: a plan sized by "fund + 12
months of income" every month would grow as the fund did and never launch. The opening
floor stays per strike, so an opening campaign is one or more 8,000 FP prongs; with a
12-month horizon the war opens later than before (the fund saves for more than one) - the
first thing to read in the runs.

**Arriving together** (the user, 2026-10-07): at launch the farthest prong sails and every other is
held for the difference in its arrival (`ThreatOffensive.arrival`: the muster `ThreatReach.STRIKE_PREP_DAYS`
plus the crossing at the board's estimated speed), its bill taken out of the fund and put back the day it
sails (`poll`, daily from `IncursionManager.advance`; `threatinc_offensiveSchedule`). A held prong whose
target or staging is gone, or that the fund cannot pay on its day, is dropped with its bill refunded. A
world with a held prong is no candidate (`scheduled`).

What it does not do yet: touch the 70% of fabrication that becomes garrisons.
