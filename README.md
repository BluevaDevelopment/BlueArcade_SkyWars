# BlueArcade - SkyWars

This resource is a **BlueArcade 3 module** and requires the core plugin to run.
Get BlueArcade 3 here: https://store.blueva.net/resources/resource/1-blue-arcade/

## Description
Spawn on floating islands, loot chests, and be the last team standing.

## Game type notes
This is a **Minigame**: it is designed for standalone arenas, but it can also be used inside party rotations. Minigames usually provide longer, feature-rich rounds.

## What you get with BlueArcade 3 + this module
- Party system (lobbies, queues, and shared party flow).
- Store-ready menu integration and vote menus.
- Victory effects and end-game celebrations.
- Scoreboards, timers, and game lifecycle management.
- Player stats tracking and placeholders.
- XP system, leaderboards, and achievements.
- Arena management tools and setup commands.

## Features
- Team size and team count configuration.
- Storm/zone mechanics for late-game pressure.
- Refillable loot chests with configurable loot tables.
- Votes to choose the type of chests, number of hearts, time, and weather.
- Store with kits and cages ready for use.

## Arena setup
### Common steps
Use these steps to register the arena and attach the module:

- `/baa create [id] <standalone|party>` — Create a new arena in standalone or party mode.
- `/baa arena [id] setname [name]` — Give the arena a friendly display name.
- `/baa arena [id] setlobby` — Set the lobby spawn for the arena.
- `/baa arena [id] minplayers [amount]` — Define the minimum players required to start.
- `/baa arena [id] maxplayers [amount]` — Define the maximum players allowed.
- `/baa game [arena_id] add [minigame]` — Attach this minigame module to the arena.
- `/baa stick` — Get the setup tool to select regions.
- `/baa game [arena_id] [minigame] bounds set` — Save the game bounds for this arena.
- `/baa game [arena_id] [minigame] spawn add` — Add spawn points for players.
- `/baa game [arena_id] [minigame] time [minutes]` — Set the match duration.

### Module-specific steps
Finish the setup with the commands below:
- `/baa game [arena_id] skywars team count <value>` — Set the number of teams.
- `/baa game [arena_id] skywars team size <value>` — Set the players per team.
- `/baa game [arena_id] skywars team spawn add` — Add a spawn point to the next free slot (run once per team).
- `/baa game [arena_id] skywars team spawn set <team_id>` — Overwrite the spawn point for a specific team (1, 2, ..., N).
- `/baa game [arena_id] skywars searchchests` — Scan the arena bounds and store chest locations.
- `/baa game [arena_id] skywars region set` — Select and save the regeneration region.

## Vote permissions
Voting requires permissions for each category and option. Use the following format:

`bluearcade.skywars.votes.<category>.<option>`

### Chest loot
- `bluearcade.skywars.votes.chest.basic`
- `bluearcade.skywars.votes.chest.normal`
- `bluearcade.skywars.votes.chest.op`
- `bluearcade.skywars.votes.chest.*`

### Hearts
- `bluearcade.skywars.votes.hearts.10`
- `bluearcade.skywars.votes.hearts.20`
- `bluearcade.skywars.votes.hearts.30`
- `bluearcade.skywars.votes.hearts.*`

### Time
- `bluearcade.skywars.votes.time.day`
- `bluearcade.skywars.votes.time.night`
- `bluearcade.skywars.votes.time.sunset`
- `bluearcade.skywars.votes.time.sunrise`
- `bluearcade.skywars.votes.time.*`

### Weather
- `bluearcade.skywars.votes.weather.sunny`
- `bluearcade.skywars.votes.weather.rainy`
- `bluearcade.skywars.votes.weather.*`

### Global wildcard
- `bluearcade.skywars.votes.*`

## Technical details
- **Minigame ID:** `skywars`
- **Module Type:** `MINIGAME`

## Links & Support
- Website: https://www.blueva.net
- Documentation: https://docs.blueva.net/books/blue-arcade
- Support: https://discord.com/invite/CRFJ32NdcK
