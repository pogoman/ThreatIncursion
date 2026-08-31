# Threat Incursion

A standalone sector-doom mod for Starsector 0.98a. The Threat - the abyssal
fabricator swarms from the vanilla endgame content - does not stay in the
abyss once woken. It colonizes.

## What it does

**Trigger** (whichever comes first): the vanilla story wakes the swarm
(Onslaught Mk.I, Threat sensor mods, or meeting them in battle), or a player
colony reaches size 6 (configurable) - the sector's largest concentration of
technology is exactly what the Threat exists to destroy.

**A real hive economy.** The swarm founds actual colonies on the uninhabited
fringe - markets that grow from size 1, build Mining, Refining, Heavy
Industry and Fuel Production, and trade with each other in a closed econ
group. Ore feeds refineries, metals feed forges, forges build the ships;
fuel buys reach. Shortages are real: cut a link and the whole network feels
it.

**Hive organs.** Every colony runs three custom structures:
- **Fabrication Core** - the population strata as industry: supplies the
  hive's heavy machinery, and carries the colony's **Fragment Fabricator**.
- **Swarm Nexus** - the military organ: continuously grows Defense Swarms
  (idling, and consuming nothing, once the garrison is full). Expeditions
  are MUSTERED from the standing garrison - every fleet comes from
  somewhere - so killing a colony's swarms IS disrupting it. Disrupt the
  nexus itself and no replacements grow at all.
- **Hive ground defenses / heavy batteries** - run on machinery and metals,
  not marines; they keep firing at reduced effect even while disrupted.

**Strikes.** Colonies launch real, interceptable expeditions at inhabited
worlds, mustered from their own Defense Swarms (a full garrison sends what
stands above its defensive reserve; the fleets that leave orbit ARE the
expedition). Each strike sweeps every world in its target system,
bombarding each at most once (frontier colonies harass with tactical
bombardment; developed worlds deliver saturation passes - erasing a large
world still takes repeated expeditions). Expeditions still being fabricated can be killed by
sabotaging the staging colony; once departed they are autonomous. Worlds
they kill, the swarm colonizes.

**The kill chain.** Hive worlds cannot simply be bombed: the Fragment
Fabricator's screen unmakes ordnance in the upper atmosphere. Defeat the
Defense Swarms, raid the Fabrication Core to steal the fabricator (EXTREME
raid danger - the hive defends its heart), and only then does bombardment
land - priced by ground defenses that never fully go down.

**The sector fights back.** Struck factions dispatch task forces against
the staging colony; purge expeditions finish undefended hive worlds; from
phase 3 the colonial defense boards run live bounty boards - a strategic
tier naming the decisive target and an immediate tier naming the cheapest
worthwhile cut, re-scored as the network shifts, with active raiders
jumping the queue.

## Install

Download the release zip, extract into your Starsector `mods/` folder,
enable in the launcher.

## Requirements

- Starsector 0.98a-RC8
- Vanilla-only; LunaLib optional (in-game config menu for everything)
- Safe to add to an existing save; removing mid-incursion breaks the save

## Building

`compile.ps1` (JDK 17) rebuilds `jars/ThreatInc.jar` from `src/`.
