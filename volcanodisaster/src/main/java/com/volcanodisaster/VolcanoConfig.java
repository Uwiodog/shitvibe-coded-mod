package com.volcanodisaster;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Settings file: config/volcanodisaster-common.toml (created the first time the game starts).
 * Size settings are copied into a volcano when it is spawned; rates are read live.
 */
public final class VolcanoConfig {
    public static final ForgeConfigSpec SPEC;

    // Caldera
    public static final ForgeConfigSpec.IntValue CALDERA_RADIUS;
    public static final ForgeConfigSpec.IntValue CALDERA_DEPTH;
    public static final ForgeConfigSpec.IntValue CLEAR_HEIGHT;
    public static final ForgeConfigSpec.IntValue FORMING_SECONDS;

    // Eruption
    public static final ForgeConfigSpec.DoubleValue ERUPTION_DAYS;
    public static final ForgeConfigSpec.IntValue DEFAULT_INTENSITY;
    public static final ForgeConfigSpec.BooleanValue INTENSITY_VARIES;
    public static final ForgeConfigSpec.IntValue BARRAGE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue BARRAGE_BOMBS_PER_SECOND;
    public static final ForgeConfigSpec.DoubleValue BARRAGE_METEORS_PER_SECOND;
    public static final ForgeConfigSpec.IntValue BOMBARDMENT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue BOMBARDMENT_HITS_PER_MINUTE;
    public static final ForgeConfigSpec.DoubleValue FIRE_BOMB_BLAST_RADIUS;
    public static final ForgeConfigSpec.DoubleValue METEOR_BLAST_RADIUS;
    public static final ForgeConfigSpec.DoubleValue OBSIDIAN_CHANCE;
    public static final ForgeConfigSpec.DoubleValue FIRE_CHANCE;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_PROJECTILES;
    public static final ForgeConfigSpec.BooleanValue VOLCANIC_LIGHTNING;

    // Scorched zone + ashfall
    public static final ForgeConfigSpec.IntValue SCORCH_RADIUS;
    public static final ForgeConfigSpec.IntValue SCORCH_COLUMNS_PER_TICK;
    public static final ForgeConfigSpec.IntValue ASHFALL_COLUMNS_PER_TICK;

    // Pyroclastic flows
    public static final ForgeConfigSpec.DoubleValue FLOWS_PER_DAY;
    public static final ForgeConfigSpec.IntValue FLOW_RADIUS;
    public static final ForgeConfigSpec.DoubleValue FLOW_SPEED;
    public static final ForgeConfigSpec.IntValue FLOW_HEIGHT;
    public static final ForgeConfigSpec.IntValue FLOW_BUILDUP_SECONDS;
    public static final ForgeConfigSpec.BooleanValue FLOW_BLAST;
    public static final ForgeConfigSpec.DoubleValue FLOW_DAMAGE;

    // Volcanic cluster
    public static final ForgeConfigSpec.IntValue CLUSTER_WIDTH;
    public static final ForgeConfigSpec.IntValue CLUSTER_VENTS;
    public static final ForgeConfigSpec.DoubleValue CLUSTER_DAYS;
    public static final ForgeConfigSpec.IntValue CLUSTER_INTENSITY;
    public static final ForgeConfigSpec.IntValue CLUSTER_ACTIVITY;
    public static final ForgeConfigSpec.DoubleValue CLUSTER_METEORS_PER_MINUTE;
    public static final ForgeConfigSpec.DoubleValue SHOWERS_PER_DAY;
    public static final ForgeConfigSpec.DoubleValue SHOWER_METEORS_PER_SECOND;
    public static final ForgeConfigSpec.DoubleValue VENT_MIN_DAYS;
    public static final ForgeConfigSpec.DoubleValue VENT_MAX_DAYS;
    public static final ForgeConfigSpec.DoubleValue VENT_RATE;
    public static final ForgeConfigSpec.DoubleValue DISTANT_VENT_RATE;
    public static final ForgeConfigSpec.IntValue VENT_BOMBARDMENT_RADIUS;
    public static final ForgeConfigSpec.IntValue VENT_FLOW_RADIUS;
    public static final ForgeConfigSpec.IntValue PLANE_TOLERANCE;
    public static final ForgeConfigSpec.BooleanValue GEYSERS;
    public static final ForgeConfigSpec.DoubleValue GREAT_FLOWS_PER_DAY;
    public static final ForgeConfigSpec.DoubleValue GREAT_FLOW_ACTIVE_FRACTION;
    public static final ForgeConfigSpec.IntValue GREAT_FLOW_MIN_REACH;
    public static final ForgeConfigSpec.IntValue GREAT_FLOW_MAX_REACH;
    public static final ForgeConfigSpec.IntValue GREAT_FLOW_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue GREAT_FLOW_DAMAGE;

    // Ash biome + client effects
    public static final ForgeConfigSpec.IntValue ASH_BIOME_RADIUS;
    public static final ForgeConfigSpec.BooleanValue ASH_FOG;
    public static final ForgeConfigSpec.IntValue ASH_FOG_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue SCREEN_SHAKE;

    // Lava spill
    public static final ForgeConfigSpec.BooleanValue LAVA_SPILL;
    public static final ForgeConfigSpec.IntValue SPILL_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_SPILL_DISTANCE;
    public static final ForgeConfigSpec.IntValue SPILL_SPREAD_SECONDS;

    // Cooling
    public static final ForgeConfigSpec.IntValue COOLING_SECONDS;
    public static final ForgeConfigSpec.BooleanValue COOL_TO_OBSIDIAN;

    // Unloaded chunks
    public static final ForgeConfigSpec.BooleanValue IMPACT_UNLOADED_CHUNKS;
    public static final ForgeConfigSpec.IntValue REMOTE_BURN_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_REMOTE_CHUNKS;
    public static final ForgeConfigSpec.BooleanValue KEEP_VOLCANO_LOADED;
    public static final ForgeConfigSpec.DoubleValue CATCH_UP_CHUNKS_PER_SECOND;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("The lava crater. Its lava surface sits exactly at the Y level the volcano is spawned at.").push("caldera");
        CALDERA_RADIUS = b.comment("Radius of the lava caldera in blocks.")
                .defineInRange("radius", 14, 4, 64);
        CALDERA_DEPTH = b.comment("How deep the lava is in the middle of the caldera.")
                .defineInRange("depth", 7, 2, 40);
        CLEAR_HEIGHT = b.comment("Everything this many blocks above the lava inside the caldera is wiped out when it forms.")
                .defineInRange("clearHeight", 48, 0, 384);
        FORMING_SECONDS = b.comment("How long the caldera takes to open up before the eruption starts.")
                .defineInRange("formingSeconds", 10, 0, 600);
        b.pop();

        b.comment("The eruption. Rates are given for intensity 10 and scale with the volcano's current intensity (20 = double, 5 = half, 1 = a tenth).").push("eruption");
        ERUPTION_DAYS = b.comment("How many in-game days the eruption lasts (1 day = 20 real minutes; 0 = until you run /volcano stop). /volcano spawn can override this.")
                .defineInRange("eruptionDays", 3.0, 0.0, 100.0);
        DEFAULT_INTENSITY = b.comment("Strength of a new volcano on a 1-20 scale when /volcano spawn doesn't give one.")
                .defineInRange("intensity", 8, 1, 20);
        INTENSITY_VARIES = b.comment("Like a real volcano, the intensity rises and falls over the eruption: quiet lulls, steady stretches, surges and the occasional paroxysm far above its usual strength. Off = it stays at its strength.")
                .define("intensityVaries", true);
        BARRAGE_RADIUS = b.comment("The heavy fire-bomb barrage lands close to the caldera, thinning out gradually towards this distance (no hard edge).")
                .defineInRange("barrageRadius", 100, 20, 1000);
        BARRAGE_BOMBS_PER_SECOND = b.comment("Fire bombs thrown into the barrage zone per second at intensity 10.")
                .defineInRange("barrageBombsPerSecond", 4.0, 0.0, 200.0);
        BARRAGE_METEORS_PER_SECOND = b.comment("Small meteors falling into the barrage zone per second at intensity 10.")
                .defineInRange("barrageMeteorsPerSecond", 0.25, 0.0, 50.0);
        BOMBARDMENT_RADIUS = b.comment("Beyond the barrage, the rest of the city gets a slow bombardment of meteors - small to huge - out to this distance, getting rarer the further out.")
                .defineInRange("bombardmentRadius", 500, 50, 3000);
        BOMBARDMENT_HITS_PER_MINUTE = b.comment("Meteors per minute in the slow bombardment at intensity 10.")
                .defineInRange("bombardmentHitsPerMinute", 6.0, 0.0, 600.0);
        FIRE_BOMB_BLAST_RADIUS = b.comment("Average crater radius of a fire bomb hit (each one varies).")
                .defineInRange("fireBombBlastRadius", 2.5, 0.0, 10.0);
        METEOR_BLAST_RADIUS = b.comment("Crater radius of a medium meteor. Small ones are about half this, big ones about twice.")
                .defineInRange("meteorBlastRadius", 5.5, 0.0, 16.0);
        OBSIDIAN_CHANCE = b.comment("Chance for each block of a meteor crater's surface to turn into obsidian/magma (fire bombs use a quarter of this).")
                .defineInRange("obsidianChance", 0.45, 0.0, 1.0);
        FIRE_CHANCE = b.comment("Chance for each block around a hit to be set on fire (fire bombs use more).")
                .defineInRange("fireChance", 0.35, 0.0, 1.0);
        MAX_ACTIVE_PROJECTILES = b.comment("Max flying fire bombs + meteors per volcano at once (keeps the game from lagging out).")
                .defineInRange("maxActiveProjectiles", 600, 10, 5000);
        VOLCANIC_LIGHTNING = b.comment("Lightning flashes in the eruption column at high intensity and before pyroclastic flows (visual only, doesn't start fires).")
                .define("volcanicLightning", true);
        b.pop();

        b.comment("The land around the caldera slowly gets scorched and buried in ash.").push("scorch");
        SCORCH_RADIUS = b.comment("How far from the caldera the ground gets scorched (grass burns to dirt, trees lose leaves, fires, ash). Grows to this over the first in-game day.")
                .defineInRange("radius", 72, 0, 512);
        SCORCH_COLUMNS_PER_TICK = b.comment("How many spots inside the scorch zone get hit per tick.")
                .defineInRange("columnsPerTick", 30, 0, 1000);
        ASHFALL_COLUMNS_PER_TICK = b.comment("How many spots inside the ash biome get a layer of ash per tick at intensity 10 (0 = no ash piling up).")
                .defineInRange("ashfallColumnsPerTick", 6, 0, 500);
        b.pop();

        b.comment("Pyroclastic flows: after a short build-up the eruption column collapses and a scorching ash cloud races outwards along the ground.").push("pyroclasticFlow");
        FLOWS_PER_DAY = b.comment("Average pyroclastic flows per in-game day at intensity 10 (far more likely during surges, rare in lulls; 0 = only when you run /volcano flow). The first one comes 3-6 minutes into the eruption.")
                .defineInRange("flowsPerDay", 2.0, 0.0, 50.0);
        FLOW_RADIUS = b.comment("How far a flow can reach from the volcano (the actual reach varies by direction).")
                .defineInRange("radius", 260, 32, 1500);
        FLOW_SPEED = b.comment("Speed of the flow front in blocks per tick (1.0 = 20 blocks per second). Slower is easier on the game.")
                .defineInRange("speed", 1.0, 0.1, 5.0);
        FLOW_HEIGHT = b.comment("How tall the ash cloud is near the volcano (it thins out further away). Anything inside it gets torched.")
                .defineInRange("height", 24, 4, 128);
        FLOW_BUILDUP_SECONDS = b.comment("Warning time before a flow: the ground around the caldera gets scorched, the volcano roars, then boom.")
                .defineInRange("buildupSeconds", 12, 0, 120);
        FLOW_BLAST = b.comment("Close to the volcano the flow knocks down weak blocks (wood, glass, wool, dirt, sand...). Stone, brick, concrete, terracotta and quartz survive.")
                .define("blast", true);
        FLOW_DAMAGE = b.comment("Damage dealt to mobs and players (not in creative) caught in the flow, on top of burning, blindness and slowness.")
                .defineInRange("damage", 20.0, 0.0, 1000.0);
        b.pop();

        b.comment("Volcanic cluster (/volcano spawn cluster): many vents scattered over a wide area. Vents erupt at different times,",
                "cool down, and new ones open up elsewhere. Water sites become geysers.").push("cluster");
        CLUSTER_WIDTH = b.comment("Default width (diameter) of the cluster area in blocks.")
                .defineInRange("width", 1500, 500, 3000);
        CLUSTER_VENTS = b.comment("How many vents the cluster keeps going at once (0 = automatic: about one per 150 blocks of width, between 5 and 20).")
                .defineInRange("vents", 0, 0, 20);
        CLUSTER_DAYS = b.comment("How many in-game days the cluster keeps opening new vents (0 = until you stop it). Running vents finish on their own afterwards.")
                .defineInRange("days", 5.0, 0.0, 100.0);
        CLUSTER_INTENSITY = b.comment("Average strength (1-20) of the cluster's vents; each vent is randomly stronger or weaker.")
                .defineInRange("intensity", 8, 1, 20);
        CLUSTER_ACTIVITY = b.comment("How active a new cluster is, 1-5 (change it in game with /volcano cluster intensity). Scales vent fire rates, vent strength,",
                        "pyroclastic flow odds, meteor showers and how fast new vents open: 1 = x0.35, 2 = x0.65, 3 = normal, 4 = x1.5, 5 = x2.2.")
                .defineInRange("activity", 3, 1, 5);
        CLUSTER_METEORS_PER_MINUTE = b.comment("Meteors per minute falling anywhere in the cluster while any vent erupts (at activity 3).")
                .defineInRange("meteorsPerMinute", 4.0, 0.0, 600.0);
        SHOWERS_PER_DAY = b.comment("Meteor showers per in-game day (at activity 3). A shower lasts 30-90 seconds.")
                .defineInRange("meteorShowersPerDay", 4.0, 0.0, 100.0);
        SHOWER_METEORS_PER_SECOND = b.comment("Meteors per second during a meteor shower (at activity 3), spread over the whole cluster.")
                .defineInRange("showerMeteorsPerSecond", 1.5, 0.0, 100.0);
        VENT_MIN_DAYS = b.comment("Shortest eruption of a single vent, in in-game days.")
                .defineInRange("ventMinDays", 0.25, 0.01, 100.0);
        VENT_MAX_DAYS = b.comment("Longest eruption of a single vent, in in-game days.")
                .defineInRange("ventMaxDays", 1.25, 0.01, 100.0);
        VENT_RATE = b.comment("Fire bombs and meteors of each vent compared to a single volcano (keeps many vents from melting your PC).")
                .defineInRange("ventRate", 0.6, 0.0, 5.0);
        DISTANT_VENT_RATE = b.comment("Extra multiplier for the fire-bomb barrage of vents with no player within 256 blocks. They keep wrecking the area, just slower and cheaper.")
                .defineInRange("distantVentRate", 0.25, 0.0, 1.0);
        VENT_BOMBARDMENT_RADIUS = b.comment("How far around each vent ash settles. (Cluster vents don't throw their own meteors; the cluster's meteor showers cover the whole area.)")
                .defineInRange("ventBombardmentRadius", 250, 50, 3000);
        VENT_FLOW_RADIUS = b.comment("Reach of a single vent's (local) pyroclastic flow.")
                .defineInRange("ventFlowRadius", 200, 32, 1500);
        PLANE_TOLERANCE = b.comment("Vents open near the height you ran the command at: within this many blocks up or down. Sites far above or below (hills, canyons) are skipped,",
                        "and sites a bit off are cut into the slope at the edge of this band. Ground height is measured under the streets, not on rooftops.")
                .defineInRange("planeTolerance", 16, 2, 128);
        GEYSERS = b.comment("Vent sites on water (sea, lakes, rivers) become geysers: boiling jets of water and steam that throw things into the air.")
                .define("geysers", true);
        GREAT_FLOWS_PER_DAY = b.comment("Great pyroclastic flows per in-game day while enough of the cluster is erupting (0 = only with /volcano cluster flow).")
                .defineInRange("greatFlowsPerDay", 1.5, 0.0, 50.0);
        GREAT_FLOW_ACTIVE_FRACTION = b.comment("Fraction of the cluster's vents that must be erupting before a great flow can happen on its own.")
                .defineInRange("greatFlowActiveFraction", 0.5, 0.0, 1.0);
        GREAT_FLOW_MIN_REACH = b.comment("Shortest reach of a great pyroclastic flow.")
                .defineInRange("greatFlowMinReach", 300, 50, 2000);
        GREAT_FLOW_MAX_REACH = b.comment("Longest reach of a great pyroclastic flow.")
                .defineInRange("greatFlowMaxReach", 500, 50, 2000);
        GREAT_FLOW_HEIGHT = b.comment("Height of a great flow's ash cloud near its source.")
                .defineInRange("greatFlowHeight", 40, 4, 160);
        GREAT_FLOW_DAMAGE = b.comment("Damage dealt by a great flow (survival players have 20 health).")
                .defineInRange("greatFlowDamage", 40.0, 0.0, 1000.0);
        b.pop();

        b.comment("Ash sky: the land around the volcano becomes the 'Ashfall' biome (dark sky, falling ash, grey grass, thick haze).").push("ashBiome");
        ASH_BIOME_RADIUS = b.comment("Radius of the Ashfall biome around a new volcano (0 = off). The biome change is permanent, even after the volcano is gone; chunks you load later are converted too.")
                .defineInRange("radius", 500, 0, 4000);
        ASH_FOG = b.comment("Thick ash haze while you're inside the Ashfall biome (client side).")
                .define("haze", true);
        ASH_FOG_DISTANCE = b.comment("How far you can see through the haze, in blocks.")
                .defineInRange("hazeDistance", 96, 16, 1024);
        SCREEN_SHAKE = b.comment("Screen shakes from the build-up, pyroclastic flows and nearby meteor hits (client side; also follows the vanilla 'Screen Effects' slider).")
                .define("screenShake", true);
        b.pop();

        b.comment("Lava slowly overflowing from the caldera.").push("lavaSpill");
        LAVA_SPILL = b.comment("Whether the caldera slowly spills lava into its surroundings.")
                .define("enabled", true);
        SPILL_INTERVAL_TICKS = b.comment("Ticks between new lava spill sources at intensity 10 (20 ticks = 1 second; lower = more lava).")
                .defineInRange("intervalTicks", 10, 1, 1200);
        MAX_SPILL_DISTANCE = b.comment("How far beyond the caldera rim the spill can reach.")
                .defineInRange("maxDistance", 50, 0, 256);
        SPILL_SPREAD_SECONDS = b.comment("Seconds of eruption until the spill reaches its max distance.")
                .defineInRange("spreadSeconds", 1800, 1, 864000);
        b.pop();

        b.comment("What happens when the eruption ends.").push("cooling");
        COOL_TO_OBSIDIAN = b.comment("Turn the caldera and spilled lava into obsidian when the eruption ends.")
                .define("coolToObsidian", true);
        COOLING_SECONDS = b.comment("How long the lava takes to harden.")
                .defineInRange("coolingSeconds", 60, 1, 3600);
        b.pop();

        b.comment("Destruction far away from players.").push("unloadedChunks");
        IMPACT_UNLOADED_CHUNKS = b.comment("Fire bombs and meteors also hit chunks no player is near: the chunk is loaded, wrecked and left burning for a while. (Pyroclastic flows always reach unloaded chunks: they are hit as soon as they load.)")
                .define("enabled", true);
        REMOTE_BURN_SECONDS = b.comment("How long a far-away chunk that got hit stays loaded so fires keep spreading there.")
                .defineInRange("burnSeconds", 15, 1, 600);
        MAX_REMOTE_CHUNKS = b.comment("Max far-away hit chunks kept loaded at the same time (higher = more distant destruction, more lag).")
                .defineInRange("maxLoadedChunks", 64, 1, 1024);
        KEEP_VOLCANO_LOADED = b.comment("Keep the volcano area loaded and running even when you fly far away.")
                .define("keepVolcanoLoaded", true);
        CATCH_UP_CHUNKS_PER_SECOND = b.comment("Pyroclastic flows and the Ashfall biome can only change chunks that are loaded at the time. Chunks they skipped are",
                        "loaded quietly in the background this many per second and finished off, so there are no untouched patches left.",
                        "Only chunks that already exist in your save are loaded (nothing new is generated into the void around a map). 0 = off.")
                .defineInRange("catchUpChunksPerSecond", 8.0, 0.0, 200.0);
        b.pop();

        SPEC = b.build();
    }

    private VolcanoConfig() {}
}
