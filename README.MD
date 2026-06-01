# Flowing Fluids Reworked

A Forge 1.20.1 finite-fluid fork of Flowing Fluids by Traben, tuned for industrial survival packs where water is not just scenery: it is pressure, supply, runoff, machine input, cave danger, and something you can actually build around.

- Original project: [Traben-0/flowing_fluids](https://github.com/Traben-0/flowing_fluids)
- Original author: Traben
- Fork maintainer: kuroneko2310
- License: LGPL-3.0-or-later

## Why this fork exists

The original Flowing Fluids already makes water mostly finite, configurable, and removable without ruining old worlds. This fork keeps that foundation and pushes it toward a more physical, machine-friendly Forge pack experience:

- Create pipes, hose pulleys, basins, tanks, and water wheels are treated as real finite-fluid participants instead of a separate vanilla-source-only layer.
- Mekanism electric pumps, portable tanks in bucket mode, and dispenser tank behavior can pick up or place partial Flowing Fluids water amounts.
- Rain, thunder, puddles, ground wetness, catchments, runoff, snowmelt, river drought, and flood events are all part of the water cycle instead of simple random refill ticks.
- Oceans, rivers, swamps, beaches, and configured infinite-water biomes refill, drain, absorb overflow, and protect broad water surfaces with clearer rules and more tuning.
- Hydraulic blocks, pressure nozzles, waterway liners, flow anchors, rain collectors, water absorbers, and ocean refill suppressors give survival builds more control over finite water.
- The processing system has more guardrails for large packs: player-distance processing limits, adaptive tick delay, chunk-local slope caches, async snapshot planning, focused component graph snapshots, and cleanup on chunk or dimension unload.

In short: this fork is for worlds where reservoirs, canals, pumps, rainfall, caves, factories, and oceans should all feel connected.

## Headline changes from Traben-0/flowing_fluids

Compared with the original public mod line, this fork adds and retunes:

- **Forge 1.20.1 first.** This branch is intentionally focused on the Forge 1.20.1 modpack stack. Fabric, NeoForge, and broad multi-loader maintenance are not the goal here.
- **Machine-aware finite water.** Create and Mekanism integrations can consume connected partial water levels, output partial amounts, and avoid silently minting vanilla sources in places where finite water should matter.
- **A stronger water cycle.** Rain no longer has to be only "top off partial blocks"; it can generate shallow puddles, scale by storm intensity, use catchment and upstream terrain boosts, skip infinite-water biomes, and queue bounded placements.
- **Better ocean and infinite-biome behavior.** Sea-level refill, surface drain, broad-water preservation, deep-ocean ambient access, overflow evaporation, and local refill suppression are separated so oceans can be powerful without making deliberate draining impossible.
- **Hydraulics and siphons.** Pressure nozzles, guide blocks, enclosed water heads, and bounded terrain siphons can move water through crafted or natural shapes while preserving water volume.
- **Springs and floods.** Forge worldgen hooks add water and lava spring features, surface vents, cave springs, and pond-floor sources, with command/debug surfaces for testing and tuning.
- **Runtime cleanup and stability.** Async slope searches, siphons, waterlog data, equalizer queues, tick buffers, component graph state, and dimension data are cleaned when chunks or worlds unload.
- **Performance instrumentation.** Runtime counters, optional component graph assist, shared worker-pool plumbing, and focused tests make the heavy systems easier to observe and tune.

## Fork defaults

The default config is tuned for a heavier Forge 1.20.1 survival pack rather than a tiny vanilla test world.

- `fluid_processing_distance` now defaults to `160` blocks. Active water processing stays near players by default, while a visual grace range keeps nearby distant water from freezing in a strange half-updated look. Set it to `0` if you intentionally need chunk loaders far away from players to keep running fluid simulation.
- `maxWaterFlowDistance` defaults to `8` and `bfsMaxSearchDistance` defaults to `16`, matching the in-game command help and giving modern water analysis enough room to resolve pools, canals, and short pressure paths.
- `enableExtendedWaterlogging` defaults to `true`, so compatible blocks can hold virtual water more reliably and the client renderer can show that stored water instead of pretending the block is dry.

Existing configs are not force-rewritten. Delete `config/flowing_fluids.json` or run `/flowing_fluids settings reset_all` if you want a world to pick up the new defaults.

## What it feels like in game

- A Create hose pulley over a reservoir can drain or place partial levels instead of pretending only full source blocks exist.
- A Mekanism tank can scoop up a connected finite puddle, store the matching millibucket amount, then place that amount back into the world.
- Ocean edges and river mouths can keep themselves alive, but local suppressor blocks and drain rules let player-built works actually fight the refill.
- Thunderstorms can matter. Rain can form puddles, feed catchments, collect in machines, or be tuned back down for a calmer pack.
- Caves can receive spring water, pressure-fed rises, natural siphon behavior, or flood pulses without every stable pool becoming a permanent expensive scan.
- Big calm water bodies should spend more time being quiet, while active fronts, canals, drains, and pressure paths get the attention.

## Tradeoffs

- It is heavier and more opinionated than the original. Big floods, ocean draining, massive pipe networks, or aggressive spring settings can still cost real server time.
- The new `fluid_processing_distance = 160` default improves normal player-area performance, but far-away chunk-loaded machines may need `0` or a larger distance.
- Forge-only compatibility means this fork is not a drop-in continuation for Fabric or NeoForge packs.
- Some machine interactions intentionally consume water levels. That is the point, but it can surprise builds designed around vanilla infinite source behavior.

## Original project links

These links are kept as credit for the original Flowing Fluids project and Traben's release channels. They are not the distribution links for this fork.

- [Original CurseForge page](https://www.curseforge.com/minecraft/mc-mods/flowing-fluids)
- [Original Modrinth page](https://modrinth.com/mod/flowing-fluids)
- [Original GitHub repository](https://github.com/Traben-0/flowing_fluids)
- [Original author Ko-fi](https://ko-fi.com/traben)

---

# About

Flowing fluids is a mod adding more realistic fluid physics to the game, making fluids mostly finite, and adding an 
element of fluid management to the game. With dozens of settings for performance, flowing behaviour, gameplay elements, and visuals

It is a spiritual successor to some of the various other abandoned water physics mods over the years, but built from scratch for modern
Minecraft. 

Implemented in a way that is compatible with *(most)* mod added fluids, and that allows you to 
disable/remove the mod at any time to return to vanilla mechanics with any 'not currently flowing' fluids remaining fully intact for vanilla purposes.

## Server side or Client side?

This mod works best when on both `server` and `client` side. However, it is only **required** on the `server` side, if 
you don't want the visual features and don't mind some minor client-side de-sync when directly interacting with fluids, such as using buckets.

There is no harm in joining a vanilla server with this mod enabled, however you will get the same minor client-side de-sync
as you would when joining a server with the mod without it on the client.

## The mod is currently in early development
It is not yet feature complete, and I make no promises of stability at this stage, as there may yet be 
some unknown breaking bugs and further room for performance improvements.

---

# Features

Many of these can be tweaked or even disabled via the `/flowing_fluids settings` command, use `/flowing_fluids help` if you are confused.

## **Flowing Fluids**:

Fluids will flow and spread outwards and down inclines, filling up spaces and creating pools.

This can be tweaked in various ways, such as how far they flow, and how aggressively the fluid tries to level itself in pools.

![bucket](https://i.imgur.com/2hTCIJH.gif)

This means you can create things like aqueducts.

![aquaduct_2](https://i.imgur.com/grBktwe.gif)

or flood areas with water.

![water_2](https://i.imgur.com/NDWqyRH.gif)

or lava.

![lava](https://i.imgur.com/cchiXTD.gif)

or even modded fluids.


## **Finite Fluids**:

Fluids are finite, and will flow until they run out, or are blocked.

![finite_1](https://i.imgur.com/dTs4G4O.gif)

This means you will need to manage your water supply, and can't just place 2 water source blocks and have infinite water.

![aquaduct_1](https://i.imgur.com/S5R0pPM.gif)

Oceans, Rivers, and Swamps however, will act as infinite water sources, but there is a limit to how fast they will refill,
and they can still be drained if fast enough *(controlled in settings)*. 

These biomes will also absorb any non-full water that flows back into it at sea level, also adjustable via settings.

![ocean](https://i.imgur.com/H1GpmCs.gif)

This fork expands the ocean and infinite-biome side so large water bodies behave more deliberately:

- Infinite-water biomes can refill, surface-drain, and absorb sea-level overflow through separate config paths.
- Deep ocean and broad connected water masses can keep ambient access even when simple skylight checks would fail.
- Thin sea-level water can be drained more aggressively than deeper stable water, which helps river mouths and ocean edges settle.
- Overflow above sea level can evaporate or be cleaned up without turning every ocean border into a permanent expensive flow front.
- `ocean_refill_suppressor` blocks can locally disable the special refill behavior, so pumps, drains, and construction projects can intentionally empty an area.
- Custom infinite-biome entries and sea-level overrides let modded dimensions and biome packs participate without hard-coding every biome name.

Fluids will also be displaced by blocks wherever possible to avoid losing any fluid.

![fill_and_displace](https://i.imgur.com/kD6eeE7.gif)

## **Piston Pumps**:

Fluids can be moved by pistons to pump them upwards automatically.

![piston_1](https://i.imgur.com/BMVSyJG.gif)

![piston_2](https://i.imgur.com/86PC5cV.gif)

## **Forge blocks added by this fork**:

This fork adds new Forge-side blocks for players who want finite water to become a build system, not just a rule change.

### Hydraulic and canal blocks

- **Waterway Liner** - a channel-bed block that reduces drag and helps shaped canals keep water moving farther and faster. It works best in intentional narrow waterways rather than broad still pools.
- **Pressure Nozzle** - a six-direction water pump. It moves water from its back face to its front face, can lift water upward, draw water downward, and preserves volume by moving only water that actually exists.
- **Water Level Sensor** - reads the water level on its front face and outputs redstone from the back. This makes finite reservoirs, pump cutoffs, flood alarms, and tank logic much easier to automate.

### Rain, draining, and ocean-control machines

- **Rain Collector** - catches open-sky rain into an internal store. It can also use RF to absorb nearby generated rain before that rain becomes loose world water.
- **Water Absorber** - drains nearby water into its internal tank. It can be toggled, radius-cycled, and consumes RF per water level removed.
- **Ocean Refill Suppressor** - disables infinite-biome refill in a local radius while redstone-active, letting ocean and river drain builds actually win against ambient refill.

### Flow anchors and survey tools

- **Droplet Flow Anchor** - keeps nearby fluid updates active in an 8-block radius.
- **Brook Flow Anchor** - keeps nearby fluid updates active in a 16-block radius.
- **Channel Flow Anchor** - keeps nearby fluid updates active in a 24-block radius.
- **Wellspring Flow Anchor** - keeps nearby fluid updates active in a 32-block radius.
- **Lakeheart Flow Anchor** - keeps nearby fluid updates active in a 48-block radius.
- **Flow Anchor Surveyor** - a utility item that reveals loaded flow-anchor and refill-suppressor ranges without changing their behavior.

These anchors are useful when `fluid_processing_distance` is enabled but you still want a specific canal, pump room, drain, or factory water loop to keep running away from the player.

### Spring and vent blocks

World generation can also place water and lava spring blocks:

- **Wall, Floor, and Ceiling Springs** for water.
- **Wall, Floor, and Ceiling Lava Springs** for lava.
- Each spring family has slight, normal, large, and heavy variants.

These are used by the Forge spring worldgen features for cave springs, pond-floor springs, surface vents, deep underground lava springs, and ceiling springs. They give terrain a visible source point instead of making every new water pocket feel like a mystery block update.

## **Gameplay Elements**:

There are a few new gameplay elements that will drain or use water, to encourage water management, most are configurable.

Such as farmlands having a chance to drain a water level whenever it hydrates.

![farmland](https://i.imgur.com/NoTbe63.gif)

Animals having a chance to require and consume 1 level of nearby water *(within 8 blocks)* when breeding.

Bottles will consume 2-3 water levels when filled.

![finite_2](https://i.imgur.com/DYaWKoX.gif)

Buckets are able to pick up 1 - 8 levels of fluid, and can be used to place down precise amounts.

![buckets](https://i.imgur.com/2fVOcKW.png)

With fluids being displaced by block placement, you will need to find other ways to drain it away, such as using sponges, creating drains, 
or tweaking the evaporation settings mentioned below.

![displacement](https://i.imgur.com/aLXbkdG.gif)

There are also various ways in which water specifically naturally evaporates or refills *(Rates customizable via settings)*

- Small *(level 1)* water puddles will evaporate slowly in the over world.
- Any water will evaporate quickly in the nether.
- Any water blocks that are not full *(levels 1-7)* will refill over time when exposed to rain.
- Any water blocks that are not full *(levels 1-7)* will refill over time when in Ocean, River, or Swamp biomes, below sea level, and exposed to skylight.

![commands](https://i.imgur.com/8INEzZB.png)

## **Rain, runoff, snowmelt, and floods**:

Rain is much more than a passive refill chance in this fork.

- Rain can refill existing partial water, spawn shallow puddles, and optionally stack water upward.
- Rain intensity has separate drizzle, steady, heavy, and thunderstorm multipliers.
- Biome filtering keeps deserts, cold snow biomes, wet biomes, and modded precipitation rules from all behaving the same.
- Catchment and upstream terrain checks can make water collect more naturally in low spots and drainage paths.
- Placement is queued, merged, and bounded so storms do not try to update every wet block at once.
- Fresh rain-born water receives a short BFS/equalizer cooldown so a storm does not immediately become a huge search burst.
- Rain collectors can intercept rain as a player-built machine, turning weather into a finite supply line.
- Snowmelt and river drought settings let weather and heat push water volume in both directions.
- Flood events can be tied to rain and thunderstorms for worlds that want occasional dramatic water pulses.

Useful commands include `/flowing_fluids settings rain status`, `/flowing_fluids settings rain runtime_status`, `/flowing_fluids settings rain inspect_here`, and `/flowing_fluids settings rain preset gentle|realistic|downpour|reset`.

## **Mod and Vanilla backwards compatibility**:

Any mod added fluids should work with the mod, though if the modded fluid has special properties, they may not be fully supported.

You can also blacklist modded fluids that you don't want to have physics. Via the `/flowing_fluids settings ignored_fluids add <fluid_name>` command.

This mod can also be disabled (`/flowing_fluids settings enable_mod on|off`) or removed at any time without breaking the world, as any fluid that is 'full' *(8 levels)* will be saved as a vanilla source block. 
And all 'non-full' fluids *(1-7 levels)* will be saved as a vanilla flowing blocks which will just disappear once updated in vanilla.

## **The Create Mod - Forge 1.20.1**

Flowing Fluids has special compatibility settings for use with the Create mod, to modify the behaviour of water wheels and pipes.

Please note some of these interactions only work properly with "full" liquid blocks *(source blocks)*, so factor that in to your design planning.

![create](https://i.imgur.com/fR4fpji.gif)

Create Mod Pipes can move fluids around finitely, as can Hose Pulleys, and can optionally be set to treat all fluids as infinite sources via the `/flowing_fluids create_mod_compat pipes infinite_pipe_fluid_source on|off` command.

This fork expands the Create side further:

- Open-ended pipes consume a connected total of 8 fluid levels when finite mode is enabled, so two half-filled water blocks can supply one bucket instead of being ignored.
- Hose pulleys can drain and place partial levels instead of only working cleanly with vanilla source blocks.
- Large external pipe fills can spill into the world through the hose pulley instead of getting trapped by a one-bucket internal tank limit.
- Large external pipe drains can combine the hose pulley's internal tank and the world below it, so high-throughput pipe networks see the real available amount more consistently.
- Hose pulley water placement protects the root water block from immediately becoming a full source, which helps finite waterworks avoid accidental infinite-looking source behavior right under the pulley.
- Create item emptying reports partial bucket amounts correctly, so Create can understand partially filled Flowing Fluids buckets.
- Create basin recipes can read compatible external finite water held in the basin block space.
- Create basin and tank wrappers are patched so external fluid insertion behaves more predictably with finite water and does not split one incoming bulk fill across incompatible variety tanks.
- Water wheels have several modes, including flow-only, river/sea-level assist, any-fluid contact, full-fluid-only, always-on, and opposite-spin variants.

![pipes](https://i.imgur.com/hM95cFe.gif)

Create Mod Water Wheels will work normally given a constant flow of fluid.

![wheels](https://i.imgur.com/f7G3L2f.gif)

By default, Water Wheels will also work without flow in river biomes at sea level.

![wheels](https://i.imgur.com/l2TV14C.gif)

![wheels](https://i.imgur.com/E00rDkE.gif)

This behaviour can be modified via the `/flowing_fluids create_mod_compat water_wheel_requirements ...` command, with the following options:
- `flow` - Requires a constant flow of fluid
- `flow_or_river` - Requires a constant flow of fluid, or to be touching water in a river biome near sea level ***(Default)***
- `fluid` - Requires contact with any fluid blocks, regardless of flow
- `full_fluid` - Requires contact with full *(8 levels)* fluid blocks only, regardless of flow
- `always` - All water wheels will always be spinning


For the options that don't require a constant flow of fluid, the wheels will always spin in the same direction, 
the following options exists to swap this arbitrary direction:
- `flow_or_river_opposite_spin`
- `fluid_opposite_spin`
- `full_fluid_opposite_spin`
- `always_opposite_spin`

## **Mekanism - Forge 1.20.1**

This fork also adds Mekanism-side finite fluid handling.

- Electric Pumps can draw partial Flowing Fluids water blocks. A level 4 water cell becomes about half a bucket instead of being ignored until it is a vanilla source block.
- Mekanism fluid tanks in bucket mode can pick up connected finite water levels and store the matching millibucket amount.
- Shift-use with a Mekanism tank can place partial stored water back into the world, including into compatible virtual-waterlogged blocks when extended waterlogging is enabled.
- Dispensers using compatible Mekanism tanks can also pick up or place partial finite fluid amounts.

The practical result: Mekanism pumps and tanks can participate in finite water logistics instead of forming a separate vanilla-source-only layer. The downside is that pump setups now consume real water volume, so old "free water forever" layouts may need rivers, reservoirs, rain capture, springs, or the infinite-biome refill rules to keep up.

This makes Mekanism feel less like a bypass and more like a proper finite-water machine chain: pumps pull from real levels, tanks carry real amounts, and dispensers can automate the same partial pickup/place loop.

## **Visuals**:

The mod renders fluids completely normally, using the regular vanilla fluid rendering, this allows it to be compatible with shaders.

![shaders](https://i.imgur.com/BOT5Qvw.png)

The mod allows hiding the flowing texture that normally appears on fluids when there is a height difference. *(enabled by default)*

`/flowing_fluids settings appearance flowing_texture`

![flow](https://i.imgur.com/tzK8HeZ.png)

![still](https://i.imgur.com/tj5ovML.png)

The mod also allows changing the heights at which 'full' fluids render and affect entities.

`/flowing_fluids settings behaviour fluid_height`

![regular](https://i.imgur.com/7vhITCK.png)

![full](https://i.imgur.com/dVohNTb.png)

You can also prevent minimum level fluids from flowing over edges, if you prefer that visual.

`/flowing_fluids settings behaviour flow_over_edges`

![edge](https://i.imgur.com/ItAgKff.png)

## **Performance**:

The mod is designed to be as performant as possible. However, massive amounts of flow affecting hundreds or thousands of blocks,
such as quickly draining an ocean, is going to cause some unavoidable server side lag. As much as any other thing affecting this many blocks would.

![lag](https://i.imgur.com/H1GpmCs.gif)

The mod has various settings to tweak the performance have a look around and see what works for you.

This fork adds more processing-side tools for heavy Forge worlds:

- `fluid_processing_distance` limits active fluid simulation around players while keeping a visual grace range for nearby water.
- `AdaptiveTickScheduler` tracks active fronts, stable pools, flow momentum, rain-born cooldowns, and area type so calm pools can sleep harder than moving water.
- `ChunkLocalSlopeCache` and `AsyncSlopeSearchPlanner` reduce repeated slope searches while keeping world writes on the server thread.
- `FluidComponentGraph` can assist the equalizer with focused snapshots for stable water-body interiors.
- `SharedFluidWorkerPool` lets the existing async and parallel systems share worker capacity instead of each growing its own pool.
- `FluidPerformanceMonitor` exposes runtime counters for tick time, cache behavior, BFS work, and other tuning signals.
- Chunk and dimension unload cleanup clears scheduler, graph, siphon, waterlog, tick-buffer, and async state so stale work does not come back later.

The mod also makes a pass over every chunk after generation and plugs up all exposed fluids so you don't end up with 
hundreds of fluid ticks happening right after the world loads as entire oceans drain into vast cave systems.
This used to be a problem with the early mod but is now quite stable, can be disabled, but I wouldn't recommend it.

**Server lagging behind?**

If the server is lagging behind consistently, try increasing the ```waterTickDelay``` (Default = 4), to reduce the amount of water updates. 
Values between 4-8 still provide a smooth water experience, with more performance focus.

# Changelog
- find the changelog [here](CHANGLELOG.MD)


# FAQ
- version for 1.19 or earlier? 
  - *I have no plans to backport this mod earlier than 1.20.1*
- does "mod_name" work with this? 
  - *probably, depends on what they do, Flowing Fluids does have an [API](common/src/main/java/traben/flowing_fluids/api/FlowingFluidsAPI.java) for other mods to interact with it if needed*
- can I use this in my mod-pack? 
  - *go for it*
- it breaks with the Create mod! 
  - *you have to have the specifically supported Forge 1.20.1 Create version for things to work; this fork is no longer maintained as a Fabric or NeoForge build*


<span align="center">

![Banner](https://i.imgur.com/j86ZaXm.png)

</span>
