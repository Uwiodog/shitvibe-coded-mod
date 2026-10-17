# Volcano Disaster (Forge 1.20.1)

A volcano you can drop into any world, made for wrecking city maps. Inspired by the volcano in the
1.12.2 *General Disasters* mod, but written from scratch. No other mods needed.

## What happens

1. **The caldera opens** (about 10 s): a lava lake whose surface sits exactly at the Y you spawned it
   at, with a low rim. Not a mountain. Everything above it is blown away.
2. **It erupts for days** (3 in-game days by default = 1 real hour) with an **intensity from 1 to 20**
   that rises and falls like a real volcano: quiet lulls, steady stretches around its usual strength,
   surges, and every so often a paroxysm far above it (big roar, shaking, lightning in the plume).
   Everything below scales with the current intensity.
   - **Barrage**: close to the caldera (mostly within ~100 blocks, thinning out gradually so there's
     no visible edge) fire bombs rain down non-stop, small and big bangs mixed.
   - **Bombardment**: the rest of the city, out to 500 blocks, gets the occasional meteor - mostly
     small, sometimes medium, now and then a huge one that leaves a massive crater. Random bangs every
     few seconds, slowly wrecking the whole city over the eruption. Big ones can be heard hundreds of
     blocks away.
   - **Lava** spills out of the caldera along a few channels and creeps further over time.
   - Every hit blasts a crater, sets fires and leaves obsidian and magma; meteors leave an obsidian
     lump the size of the meteor.
   - **Scorched zone**: the land around the caldera slowly burns. Grass turns to dirt, trees lose
     their leaves and char to basalt, windows shatter, fires break out, ash piles up. It spreads
     outwards over the first day.
3. **Pyroclastic flows** (about 2 per day, the first 3-6 minutes in): the volcano roars and the
   ground around it scorches for a few seconds... then the column collapses with a huge bang and
   a wall of burning ash races out across the city at 20 blocks a second, up to ~260 blocks.
   Everything it passes is torched: glass shatters, trees are stripped, wood, wool and soft blocks
   near the volcano are knocked down, fires start and a blanket of ash is left behind (thick close
   in, a dusting at the edge). Mobs caught in it burn. Stone, brick and concrete buildings survive
   as burnt-out shells.
4. **Ash sky**: everything within 500 blocks becomes the **Ashfall** biome: dark brown-grey sky,
   ash falling like snow, grey grass and trees, rumbling ambience, and a thick haze you can only
   see ~100 blocks through. Ash keeps settling on the ground while the volcano erupts.
5. **Cooling**: the caldera and spilled lava harden into obsidian, then the volcano is gone.
   The scorched land, the ash and the Ashfall biome stay.

**Far-away destruction:** fire bombs and meteors aimed at places no player is near still land:
the chunk is loaded, wrecked and kept burning for a while. Pyroclastic flows remember every chunk
they passed that wasn't loaded and torch it the moment it loads. The ash biome also spreads into
chunks as they load.

## Commands (need cheats / op)

| Command | What it does |
|---|---|
| `/volcano spawn` | Volcano at your feet |
| `/volcano spawn <x y z> [intensity] [days]` | `intensity` 1-20 = the volcano's usual strength (default 8; it still varies above and below). `days` = in-game days of eruption (decimals ok), `0` = until stopped |
| `/volcano intensity <1-20> [id]` | Change a volcano's strength; it builds up or calms down to it over half a minute |
| `/volcano flow [id]` | Start a pyroclastic flow right now (build-up, then boom) |
| `/volcano list` | All volcanoes in this dimension: stage, current intensity (and whether it's rising or falling), time left |
| `/volcano skip [id]` | Jump to the next stage (nearest volcano if no id) |
| `/volcano stop [id\|all]` | End the eruption and let the lava harden |
| `/volcano remove [id\|all]` | Delete it immediately, leaving the lava as it is |
| `/volcano ashzones clear` | Stop the Ashfall biome from spreading into more chunks (already-converted land stays) |
| `/disaster spawn volcano [x y z]`, `/disaster skip volcano [x y z]` | Same syntax as the old mod |

## Settings

`config/volcanodisaster-common.toml` is created the first time you start the game. Sections:
`caldera`, `eruption` (days, default intensity, whether it varies, barrage radius and rate,
bombardment radius and rate, blast sizes, lightning), `scorch`, `pyroclasticFlow` (flows per day,
radius, speed, height, build-up time, damage), `ashBiome` (radius, haze, screen shake),
`lavaSpill`, `cooling` and `unloadedChunks`.

## Tips

- **Back up the map first.** The damage and the biome change are permanent.
- `/volcano spawn` with no position opens the caldera right under you. That's fine in creative;
  in survival, give a position a little away from you instead.
- Fires only spread if the `doFireTick` game rule is on (it is by default).
- Far-away hits load the chunk they land in. If your city map has empty, never-generated land
  around it, set a world border around the city (`/worldborder center` + `/worldborder set`) so
  nothing lands outside it and generates new terrain.
- If the game lags, lower the intensity (`/volcano intensity 4`), `barrageBombsPerSecond`, the
  pyroclastic flow `speed` or `radius`, or `unloadedChunks.maxLoadedChunks`.
- If you uninstall the mod later, Ashfall areas fall back to a vanilla biome when loaded.
- Ash blocks and ash layers are in the Natural Blocks creative tab if you want to build with them.

## Building the jar

**On your PC:** install Java 17 (for example Temurin 17 from adoptium.net), open a terminal in this
folder and run `gradlew build` (Windows) or `./gradlew build` (Mac/Linux). The first build downloads
Minecraft and Forge and takes several minutes. The mod ends up in `build/libs/volcanodisaster-1.2.0.jar`;
put it in your `.minecraft/mods` folder next to Forge 1.20.1.

**Without installing anything:** make a new GitHub repository, upload all of these files (including
the `.github` folder), and open the **Actions** tab. The "Build mod jar" run produces a
`volcanodisaster-jar` download with the mod inside.
