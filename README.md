# The Abyssal War

A standalone sector-doom mod for Starsector 0.98a. The Threat - the abyssal
fabricator swarm from the vanilla endgame - colonises the fringe, grows, and
invades the core worlds. Its colonies are real markets; you beat it by
starving, bombing and invading them, not by killing fleets.

## How a game goes

- At game start a hidden fringe system is seeded with several Threat
  colonies ("hives"). They grow, spread and scout the core, and strike once
  they can afford to beat the defence they saw.
- A faction whose world is struck mobilises: war economy, patrols, scouts,
  one grand fleet. Factions, and you, find hives only by flying into their
  systems.
- Press Mobilise on the war board (intel screen "The Abyssal War") and your
  colonies bank hulls, marines, heavy armaments, fuel and supplies, which
  you spend on orders: Deploy a fleet, Transfer stock, Attack a hive, Recall.
- A hive dies when its strata reach zero. Kill every hive and the war ends.
  The core holds for years alone and usually loses without you.

## The Threat, explained

- **Strata** is a hive's one number: its ground defence, its hit points
  and its troops at once. Fight it on the ground or bomb it from orbit and
  the number falls; below each size's threshold the colony drops a size; at
  zero it is destroyed. Each size needs twice the strata of the last.
- **Strata is also its fleets.** A hive has no standing garrison: it turns
  strata into ships when attacked or to strike (budding) and ships back into
  strata when they return (absorbing). Over your world its ships break up
  into troops and land; landed strata never come home.
- **Reclaimed hulls.** The swarm consumes and repurposes the fleets it
  destroys. Each hive world keeps a count of the hulls it has taken; a fleet
  budded there includes those hulls, with Threat weapons, beside its own
  ships. A wreck also adds strata to the world.
- **The Fabrication Core** makes strata from metals and fuel, more of each
  the bigger the colony. Both met: it grows a size at a time. Short: it
  holds. Starved: it shrinks until it dies. Blockade a hive and it imports
  nothing; only its own mines, refineries and fuel plants still feed it, and
  saturation bombardment disrupts those. Accessibility is swarm density, so
  an isolated hive starves.
- **Strata move.** Quiet worlds bud fleets that the front absorbs; a world
  under attack pulls strata in; the hive stages strata at its front worlds
  toward size 8 and buds the mass as a strike. Its best mine, refinery and
  fuel plant never give strata away below their own size.
- **Occupation.** The swarm never takes a human world. Each district its
  army holds lets it eat one industry: consumed over its build time, then
  removed. What it eats becomes strata - troops, then Infestation Swarms in
  orbit. Retake the district and the industry rebuilds; nothing left to eat
  and the army lifts off.

## Defending and attacking

- A colony's defence is its ground defences manned by marines plus its
  spare marines armed with heavy armaments. Marines arm over days, bleed,
  gain veterancy.
- A ground fight is one line moved by the net of both sides' pressure; a
  district changes hands when it moves far enough. Push spends cover to move
  the line, Hold regrows it. The board shows Objective, ETA and Victory for
  every front.
- Kill a hive from the dock (tactical bombardment razes strata; Ground
  operations lands marines) or from the board (Attack raises a fleet at your
  chosen colony, sized by levers, and blockades, bombs and lands for its
  term).
- Every order is None / Med / Max of what the sending colony can spare.
  Every fleet and convoy pays its passage and burns its hold. Nothing of
  yours moves unless you order it, and no order of yours is refused.
- Call for aid with a toggle per good and accept what factions offer from
  their surplus; their asks of you are sized to what you can field. Standing
  on arrival, one rate either way.

## Install

Download the release zip, extract into your Starsector `mods/` folder,
enable in the launcher. Saves from 0.8 carry on; removing the mod mid-war
breaks the save.

## Requirements

- Starsector 0.98a-RC8
- Vanilla-only; LunaLib optional (in-game menu of the knobs that matter;
  the rest live in `data/config/settings.json`)
- Nexerelin compatible

## Building

`compile.ps1` (JDK 17) rebuilds `jars/ThreatInc.jar` from `src/`.
