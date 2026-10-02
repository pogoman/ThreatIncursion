# The swarm's strategy controller - DESIGN, not built (2026-10-01)

For the user to mark up. Written overnight after the user's rule of 2026-10-01: "there should be no
cheating by either faction we want asymmetric strategy the threat aren't some all knowing all seeing
deity their goal should be to spread and purge so when you get to their strategy controller it should
be far more offence oriented". The human side has `ThreatWarCouncil` (`war-council.md`); this is
the swarm's counterpart, and deliberately not a mirror of it.

## 1. How the swarm attacks and spreads today (code map, 2026-10-01)

Mapped read-only in session fbb85ee0 against the h53d run (14 monthly ticks, 19 to 30 hives). By
symbol.

**Cadence.** `IncursionManager.tick` every `tickDays` 30: `advanceStages`, maintenance, `trySpread`
(opens with `ThreatColonyManager.tryExpandInSystem`), `tryConversions`, `tryStrikes`. Posture
(`ThreatPosture.poll`) and stance (`ThreatStance.evaluate`) run every `postureDays` 5 in the poll: they
build stock and wants continuously, but launches happen once a month.

**Strikes** (`tryStrikes`, phase >= 2). The hive systems are shuffled and each gets one pass:
`ThreatColonyManager.pickStrikeStaging` (biggest colony, size >= `strikeMinSize` 4, `shipsAvailable`,
`garrisonAvailableForLaunch` >= 2 over the system's `launchPool`, fuel stock > 0, an operational
Nexus) -> `IncursionManager.pickStrikeTarget` -> `launchStrike`. So at most one strike per hive
system per 30 days, and no global cap ("the bank is the limit").
- Target filter: `isStrikeableWorld`, core worlds only at phase 3, `playerGraceDays` 120, `warOpen`
  (before phase 3 only factions at war, grudged or never mobilising), one strike per world
  (`isActiveStrikeTarget`), `swarmKnows` and, under the swarm's fog, `strikeSeen`;
  `ThreatFuel.canPay` the passage (a muster every world of which waits only on fuel books what the stock is short of the cheapest
  passage as demand, `ThreatFuel.heldShort("strike from ..", passage)`, knob `strikeWaitBooksFuel`, 2026-10-02; the shortfall alone since 2026-10-03); `strikeOutweighed` skips a world whose defence >= (strike + the
  swarm's own strength already in that system) x `siegeBreakOffRatio` 1.0.
- Weight: `strikeValue` (size squared or a link's weight, x (1 + 0.2 x grudge)) /
  `ThreatReach.strikeDays` (4 x ly + prep) x `ThreatStance.strikeTargetMult`. A 0-ly world weighs
  about 18x a 42-ly one. A dry Threat front gets x4 (`strikeReinforceWeight`) in a separate relief
  pick that wins outright.
- Size: NOT sized on the target. `launchStrike` takes every sendable swarm (`peekMuster`: above
  `garrisonReserve`, not `regrowing`, within `launchSpareFP` under pressure), re-embodied at size
  3-9, packed into few fleets, trimmed by `ThreatFuel.canPay`, `ThreatReach.canSustain` (about 0.78
  supplies per FP a month away) and `canAffordFP`. Prep 7-14 days; then `siegeOrbitDays` 120 per
  world; it sweeps every eligible world in the target system (a relief strike goes to its front only).
- Stopped by: the hive's fuel stock (the binding limit in h53d: 3-3,000 in stock against 50-77k a
  month spent; up to 31 sends held a month), the supplies spare, a bank that cannot re-embody, a
  Nexus or forge raid while preparing (`abortStrikesFrom`), CONSOLIDATE (non-spoiling targets weigh
  0), and the strike gate (104 "passed over" lines on 33 targets, defence a mean 2.4x the strike).

**What a strike does** (`ThreatStrikeFGI.AnnihilationAction`): besiege (`siegePass`,
`ThreatGroundFronts.siegeSlice`), land (`readyToLand`, `beachheadLanding` breaks hulls into troops at
`fabricateTroopsPerFP` when the share is short), reinforce (`landOrReinforce`; really the next strike,
since a dry front weighs x4). Troops: `strikeTroopsPerPoint` 20 x difficulty points, per world
max(300, pool / worlds), called off under `frontMinMarines` 50. The landing fleet stays as a
`ThreatSwarmDefend` station; an unspawned strike builds its first fleet over the world for it
(`guardUnspawned`, 2026-10-02, `ground-war-orbit-control.md`). A human colony falls only by `colonyGroundVictory`; with
`conquestConverts` it becomes a size-2 hive at once (`convertConquered`, paid by `conquestPayer`),
else it is decivilised and `tryConversions` claims the ruin. Harassment and saturation passes are
dormant (`strikeSaturationEnabled` false). Most strikes resolve off-screen (15 of 21 in h53d).

**Spread** (`trySpread`): (1) `tryExpandInSystem`: a wave per unclaimed planet in held systems,
uncapped and not stance-gated; (2) free forges (size >= `spreadMinSize` 3, stable, system not
pressed); (3) stop when pending claims >= `ThreatPosture.claimCap`; (4) `pickSpreadTarget` takes the
single best uninhabited system (weight (1 + need) x `holdShare` / (days to the nearest hive x days to
the nearest world of a faction not at war) x `spreadMult`). One outward claim per tick.
`advanceStages` sends the Seeding Swarm `seedToColonyDays` 120 later if `pickWaveSource` finds one
(one Defense Swarm plus a 5,000 fuel and supplies kit). New colonies launch waves at size 3 and
strikes at size 4. CONSOLIDATE stops only new outward claims: in-system waves, matured claims and
conversions go on.

**Coordination: none.** No plan object, no joint strike, no timing. The only links between hive
systems: one strike per world, relief follow-ups, retaliation (one caller,
`ThreatGroundFronts.hiveGroundVictory`), and posture's defensive transfers. Also offence-adjacent:
`ThreatRaiders` (48 detachments in h53d, 33 held for fuel) and `ThreatSwarmScouts`.

**h53d outcome**: 23 strikes (2-13 swarms, 297-6,694 FP, mean ~2.2k; 13 within 4 ly, 8 at 0 ly),
11 landings, 5 beachheads overrun, 7 strata taken, 0 human worlds taken in 14 months. 22 waves, 12
colonies founded. Launch stance: EXPAND 15, PRESS 6, CONSOLIDATE 2.

## 2. What is wrong with it, for an offence-led swarm

1. **Scattered.** Every hive system spends its fuel on its own monthly strike at the nearest
   target. The fuel runs dry, so no world gets the follow-up waves it takes to finish a ground war:
   beachheads are overrun, strata are taken and lost, nothing falls.
2. **Reactive at heart.** Posture (defence) runs every 5 days and decides what may launch; offence is
   whatever is left over. CONSOLIDATE fires on pressure alone.
3. **No intent across months.** Nothing says "this year we take Corvus". Each tick is a fresh,
   independent choice, so the swarm never concentrates.
4. **Spread is slow and blind to war.** One claim a month, 120 days to seed, and the claim avoids
   factions at war. Seeding never serves the offence (a forward hive that shortens reach to a target).

## 3. Design: the Hive Mind

One controller for the whole swarm, `ThreatHiveMind`, evaluated each month before `tryStrikes` and
`trySpread`. It owns two drives, **Spread** and **Purge**, and turns them into campaigns that last
months. Defence becomes a by-product: posture still keeps each hive's reserve, but the Hive Mind
decides what everything above the reserve is for.

**Asymmetry with the human council** (the user's rule):
- The council chooses among Hold, Starve, Roll back and Decapitate; it can sit still. The Hive Mind
  never holds: it always runs a Purge or a Bloom, and its only retreat is to re-target.
- The council sizes from its means and judges plays by damage. The Hive Mind measures itself by worlds
  taken and hives founded per fuel spent.
- The council reads its own reports. The Hive Mind reads only the swarm's: `ThreatSwarmIntel`
  places and contacts, and `swarmKnows` charts. No exact remote read (`threat-fog.md`).

**Campaigns** (one Purge at a time per front, a Bloom alongside):
1. **Purge** - take one human system and convert it. The Hive Mind picks a target system from its
   places (value = sum of world size squared, x grudge, / days from the hive's nearest staging; the
   last-seen defence only weighs the choice, the user's "no sizing on enemy FP" kept). It then names
   the hive systems in reach as contributors and books a fuel budget for the campaign (a share of the
   hive's stock and monthly income). Strikes stop being one per system per month: contributors send
   waves timed to arrive together (the Tide), then follow-up waves every month to feed the fronts
   until each world falls. The strike gate weighs the campaign's combined strength, not one strike's.
   Ends: every world converted (success), the budget spent with no stratum gained in N months
   (failure), or the target's report shows a defence the campaign cannot meet even combined
   (re-target).
2. **Bloom** - a spread drive. Several claims at once (the one-claim-per-tick limit lifted within
   the fuel and supplies budget), aimed at uninhabited systems that shorten the reach to the next
   Purge target (forward hives), and at resource systems the economy needs. While a Purge runs, Bloom
   seeds toward its target, so the next wave starts closer.
3. **Sever** (seen only) - when the swarm has seen human staging, convoys or a forward-base chain
   aimed at it, the Hive Mind may spend a slice on raiders and strikes at the links whose loss cuts
   most (`ThreatFrontlines.cutBy`). Offence as the defence.
4. **Spoil** (seen only) - a strike on a base the swarm saw staging against it (a place with
   `stagesFor` set), before it sails. Replaces today's CONSOLIDATE spoiling blow.
5. **Probe** - when the best target's place is stale or no target is known, extra Scouting Swarms
   toward the candidates. Cheap; runs alongside anything.

**Choosing** (non-deterministic, like the council): each month the Hive Mind scores options (Purge
targets, Bloom directions, Sever and Spoil openings) and draws with a temperature. A running Purge
keeps its target unless it fails or a much better target is seen (hysteresis). A temperament knob,
`hiveAggression`, scales the share of stock committed to offence (default high).

**Budget, not leftovers.** Each month the Hive Mind splits the hive's fuel and supplies: the reserve
posture needs for each hive's minimum garrison and upkeep first, then the campaigns' shares (default:
Purge 60%, Bloom 25%, Sever and Spoil 10%, Probe 5%). Raiders and waves draw from their share instead
of first-come, so a dry month no longer starves the strike that was about to finish a world.

**Defence as a by-product.** Posture keeps computing pressure and wants, but:
- while a Purge runs, contributors' wants are their reserve plus the campaign's stock; transfers
  toward a pressed system stay;
- CONSOLIDATE becomes "hives actually lost": the hive count falling, or a ground front on a hive. A
  threat merely seen (pressure) slows a Bloom, never cancels a Purge.

**Learning.** Per target class (size band, distance band, faction) the Hive Mind keeps worlds taken
and fuel spent, and leans toward what worked, with a floor so nothing is ruled out.

## 4. Decisions for the user

1. **One Purge at a time, or one per front?** *Recommended:* one per front (a hive cluster), so a
   far-flung swarm can press two wars, with the budget split between them.
2. **Budget split** Purge 60 / Bloom 25 / Sever and Spoil 10 / Probe 5? *Recommended:* yes, as data in
   settings.json.
3. **Does the swarm keep the strike gate at all?** Today it skips any world whose seen defence >= the
   strike. *Recommended:* keep it, weighed against the whole campaign's strength, so a Tide can take
   what one strike cannot; a world that outweighs even that is re-targeted, not fought.
4. **Bloom toward the war?** Today seeding avoids factions at war. *Recommended:* while a Purge runs,
   Bloom seeds toward the Purge target (forward hives); otherwise as today.
5. **CONSOLIDATE only on real losses?** *Recommended:* yes (hive count falling or a front on a hive);
   pressure alone no longer stops claims.
6. **Retire `ThreatStance`?** Its PRESS / EXPAND / CONSOLIDATE becomes the Hive Mind's state.
   *Recommended:* keep `ThreatStance` running underneath for one build behind a knob
   (`hiveMindEnabled`), then fold it in.
7. **Board.** *Recommended:* the Threat tab gains one line, "Campaign: Purge (Corvus) for 92 days",
   and nothing else (less is more).

## 5. Build order (proposed)

1. `ThreatHiveMind` skeleton: monthly evaluation, the budget, logs; Purge only, with the existing
   strike machinery called per contributor and timed arrival.
2. Follow-up waves and the campaign's end conditions; the strike gate on combined strength.
3. Bloom (several claims, forward seeding).
4. Sever, Spoil, Probe.
5. Learning, the board line, the doc's "As built".

Test: a long new-game run, compared with the run of the swarm's fog alone (`facts.md`, "Big changes
get a long new-game test").
