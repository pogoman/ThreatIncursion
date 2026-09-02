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
  hive's heavy machinery. Knock it out and the whole colony starts dying.
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

**The siege.** Every hive lives deep underground behind ground defenses
anchored to its size (400 points per size, immune to unrest, multiplied
by its defense industries - a mature hive's true figure runs into the
thousands), and **no bombardment can reduce its population**. Saturation
costs fuel equal to the full defense figure and buys only days of
disruption; tactical bombardment costs a quarter of that and suppresses
the exposed war-strata for months (halving their defensive effect);
marine raids cut deepest against a chosen organ. A colony whose
Fabrication Core is down - or whose supply lines are cut - stops growing
and **declines**, losing population faster the longer it stays
suppressed, until its population falls to size 1 and the hive collapses.
That is the only way a Threat colony dies. Supply-chain strikes cascade:
one disrupted port or forge can push several sister colonies into
decline at once.

**The sector fights back.** Struck factions dispatch task forces against
the staging colony; siege expeditions work over hive worlds with tactical
bombardments and commando raids that push them into decline (rarely, with
extra escorts, assaulting even defended hives), posting a detailed
after-action sitrep of what they did to each planet; from phase 3 the
colonial defense boards offer contracts against hive infrastructure -
ordinary missions that arrive over the comm network and must be accepted
before they expire, then completed within 120 days. A strategic tier names
the decisive target and an immediate tier the cheapest worthwhile cut,
re-scored as the network shifts, with active raiders jumping the queue.

**Commission your own.** From the infested-system intel, hire the same
siege expedition NPC navies run - mustered at your nearest colony with a
military structure (within its fuel reach), sized to the target system's
defenses, built with your doctrine and blueprints. You pay up front,
priced by flotilla size and distance; the expedition is autonomous and
reports back with a full sitrep.

## Install

Download the release zip, extract into your Starsector `mods/` folder,
enable in the launcher.

## Requirements

- Starsector 0.98a-RC8
- Vanilla-only; LunaLib optional (in-game config menu for everything)
- Safe to add to an existing save; removing mid-incursion breaks the save

## Building

`compile.ps1` (JDK 17) rebuilds `jars/ThreatInc.jar` from `src/`.
