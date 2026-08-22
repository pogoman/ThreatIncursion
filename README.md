# Threat Incursion

A standalone sector-doom mod for Starsector 0.98a. The Threat - the abyssal
fabricator swarms from the vanilla endgame content - does not stay in the
abyss once woken.

## How it works

**Trigger** (whichever comes first):
- The vanilla story wakes the swarm: finding the Onslaught Mk.I, gaining the
  Threat detection sensor mods, or encountering the Threat in battle.
- A player colony reaches size 6 (configurable) - the sector's largest
  concentration of technology is exactly what the Threat exists to destroy.
  You don't have to find them for them to notice you. Note: waking them this
  way means facing their extreme stealth without the Mk.I sensor mods until
  you go earn them.

**Spread:** seeded systems (uninhabited fringe first) grow Fabrication Hives
(~4 months), then saturate (~5 more). The swarm creeps to nearby systems -
the more hives live, the faster it spreads. Hard cap on total infestations.

**Phases:**
1. *Fringe expansion* - atmosphere and salvage opportunities only.
2. *Outlying colonies struck* - saturated hives launch real incursion fleets
   (interceptable, joinable battles) that saturation-bombard colonies up to
   size 5. NPC defenses resolve engagements on their own if you stay away.
3. *Core in reach* (default on) - size 6+ worlds become valid targets.

**Counterplay:** every infested system has a Fabrication Hive fleet.
Destroy it and the system is cleansed - and since spread rate scales with
hive count, pruning early genuinely slows the doom. Your colonies get a
configurable grace period between strikes aimed at you; NPC worlds get none.

**The Remnant immune system:** systems guarded by a live Remnant Nexus
resist infestation - the swarm must win a machine war first (35% per
attempt). Every Nexus you destroyed yourself is a hole in the sector's
immune defenses. Pairs thematically with Remnant Retribution; works with
plain vanilla too.

## Configuration

LunaLib settings menu ("Threat Incursion") when LunaLib is enabled;
`data/config/settings.json` as standalone fallback. Debug section includes
Force Start (skip the story trigger) and a 10x Fast Clock for pacing tests.

## Building

`compile.ps1` with a JDK 17 (`JAVA_HOME` if not on PATH).

## Save compatibility

Safe to add mid-save. Removing it from a save with an active incursion will
break that save.
