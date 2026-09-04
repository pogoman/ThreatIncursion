# Ground war - eradication by ground victory

Design agreed with the user (Sept 2026, two sessions). This doc records what is
implemented, the judgment calls made, and the strategy-layer backlog. Every rate is a
config knob (settings.json / LunaLib).

## The model

**The colony is the fortress; the Core is the objective.** A size-S hive is S strata
deep with the Fabrication Core at the center. There is NO decline timer any more -
the old health-below-threshold decline engine is removed. Starvation and bombardment
only ever WEAKEN a colony; **eradication is a ground victory**: take every stratum,
destroy the Core, colony dies (`ThreatGroundFronts.groundVictory` ->
`ThreatColonyManager.eradicate`). This holds for NPCs too - purge expeditions land
their own fronts (below).

## The front (ThreatGroundFronts)

Marines + heavy armaments landed on a hive world ("Ground operations" on the military
options menu; commits everything aboard). Ticks on the colony poll at flat rates:

- **Supply**: armaments burn per size per 30 days (x`frontPushUpkeepMult` while
  assaulting). The stockpile IS the countdown; a dry front fights at
  `frontDryEffectivenessMult` (0.5) effectiveness AND attrits at
  `frontUnsuppliedLossMult` (3x) - "not enough armaments" is exactly the
  reinforce-before-pushing moment.
- **Effectiveness** = marines x entrenchment ramp (1.0 -> 1.5 over 30 days) x supply
  factor, vs the world's CURRENT worn defense figure (`MarketCMD.getDefenderStr`).
- **Suppression states** (unchanged from phase 1): HOLDING (>= 25% of defenses)
  suppresses Core/Nexus/port/defense structures by feeding their disruption clocks
  (the existing wear mechanic); GRINDING (>= 10%) harasses just the defense
  structures at half rate; FOOTHOLD suppresses nothing.

## The stratum campaign

- **Push** (ordered by the player; requires HOLDING strength): takes
  `frontPushBaseDays` x (defense/strength ratio, clamped 0.5-3x) days, at
  `frontPushLossPer30Days` (0.30) attrition - pushing is how you win and how you
  bleed. A pushing front is exposed (no entrench defense bonus).
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
  now. Attack = current defense figure vs the front's (entrenchment-boosted when
  dug in) strength. Losing costs `frontCounterAttackLossFraction` marines and a
  held stratum; a beachhead beaten 2:1 with no strata is overrun.
- **The final stratum** destroys the Core: eradication, survivors evacuated
  (player fronts), announced.

## NPC fronts

Purge expeditions (`ThreatPurgeFGI`), once the war-strata are softened below the
soften floor, land an NPC-owned front (troops = combined expedition ground strength,
armaments = `npcFrontSupplyDays` of upkeep) instead of their first commando raid.
The front runs a stance AI - push when strong enough and supplied, entrench
otherwise - through the same checkpoint pacing. When its supply runs dry it withers;
follow-up expeditions land fresh fronts. This is how factions erase hives without
the player. The player cannot direct or resupply an NPC front (dialog shows status
only), and cannot land where one already fights.

## Vanilla-tool integration (unchanged from phase 1)

- **Tac bomb + own front = danger close**: costs the front
  `frontDangerCloseLossFraction` marines, in exchange the strike's disruption also
  lands on the Core and port. Player-owned fronts only. Warned before confirm.
- **Sat bomb**: destroys ANY front on the surface (warned), sets
  `$threatinc_fallout` for `falloutDays` (40 >= the 20-day blackout - sat bombing
  must forfeit ground tempo). Role: theater shaping, never eradication progress.

## What replaced "decline" in the UI

- War board cards: `Front 1,250 push 2/6` in place of Reach; forecast column shows
  `s6 -> core ~85 d` while a strong front fights, `s6 contested` otherwise; card
  border lights up when a front is on the ground. Vitality bar threshold now
  `ThreatColonyManager.CRITICAL_HEALTH` (0.35, presentational only).
- HiveVitalityCondition, InfestedSystemIntel, ThreatSiegeReportIntel,
  ThreatMissionIntel: decline meters/forecasts replaced with strata-taken figures.
- Purge follow-up "wounded" test: front present or organs disrupted.
- Legacy decline persistent-data maps are kept but unread (old saves load fine).

## Strategy-layer backlog (the "proper strategy game" - see docs/strategy-layer.md for what was built on 2026-09-04)

Agreed direction from the user, in rough build order:

1. **Troops ride fleets for real**: marines/armaments as actual cargo aboard NPC
   expedition fleets (vanilla `CargoAPI` exists on every fleet), with troop-carrier
   and cargo capacity mattering. A pulled-out front boards a circling fleet and can
   be redeployed elsewhere.
2. **Fleet orders**: a way to order individual friendly fleets - orbit superiority,
   jump-point interception (vs Mutual Defense relief), logistics runs to fronts,
   troop redeployment. Missions the player could fly personally, delegated.
3. **Faction reserves, fully transparent**: every deployment of marines/armaments/
   fuel comes from a visible per-faction reserve, generated by functioning colonies
   and industries. War board gains a reserves/logistics panel.
4. Hive-side fronts on core worlds (Threat strikes reworked onto the same object),
   outposts on eradicated worlds (non-economy garrison entities, forward depots).

## Strategy-layer decisions (user, 2026-09-04, after the session above died)

- **Reserves are PER COLONY, with staging**: stocks live on the colonies that
  generate them and must physically ship to a staging point before they deploy.
  This pulls in the convoy layer from the start; that is accepted.
- **Everything fleet-related must be doable REMOTELY** - from the board, not by
  flying to a fleet and hailing it. The war board is the fleet-order home.
- **Faction selector on the board**: pick a faction from a list and the board
  shows THAT faction's systems and planets - state of its defenses, attacks
  against it, its fleets - so the player can send fleets to reinforce allies.
- **War mode gates all of it**: NPC factions behave exactly as vanilla until the
  Threat attacks them. Being struck by the Threat flips the faction into war
  mode; only then does the mod start tracking its reserves, defensive fleets,
  attack fleets and so on. Do not touch core-world fleets outside war mode.
  (Natural hook: the reactive-defense path in `IncursionManager` that already
  musters a faction task force when a Threat strike targets its colony.)

## Testing

`tools/test-harness` cycle + a cloned save with injected fronts (see
docs/testing-harness.md; persistent-data XML injection of
`threatinc.ThreatGroundFronts$GroundFront` works - element name uses `_-` for `$`).
