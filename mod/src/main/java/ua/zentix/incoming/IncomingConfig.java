package ua.zentix.incoming;

import net.neoforged.neoforge.common.ModConfigSpec;
import ua.zentix.incoming.strike.Loadout;

/**
 * Настройки. SERVER хранится в мире (serverconfig/incoming-server.toml), CLIENT — у каждого игрока.
 * Экран настроек — штатный NeoForge (Моды → Incoming → Настроить).
 */
public final class IncomingConfig {
    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        var server = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();
        var client = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    private IncomingConfig() {}

    public static final class Server {
        public final ModConfigSpec.IntValue dronePower;
        public final ModConfigSpec.IntValue missilePower;
        public final ModConfigSpec.IntValue rocketPower;
        public final ModConfigSpec.IntValue loiterPower;
        public final ModConfigSpec.IntValue bunkerPower;
        public final ModConfigSpec.IntValue bunkerEnergy;
        public final ModConfigSpec.BooleanValue blockDamage;
        public final ModConfigSpec.BooleanValue shatterGlass;
        public final ModConfigSpec.BooleanValue fire;
        public final ModConfigSpec.BooleanValue debrisStay;
        public final ModConfigSpec.BooleanValue collapse;
        public final ModConfigSpec.BooleanValue siren;
        public final ModConfigSpec.IntValue maxSalvo;
        public final ModConfigSpec.IntValue maxSpread;
        public final ModConfigSpec.IntValue maxActivePerPlayer;
        public final ModConfigSpec.IntValue aimRange;
        public final ModConfigSpec.IntValue mapRange;
        public final ModConfigSpec.BooleanValue designatorForEveryone;
        public final ModConfigSpec.BooleanValue mapPlayers;
        public final ModConfigSpec.BooleanValue sightRules;
        public final ModConfigSpec.IntValue sightMemory;
        public final ModConfigSpec.BooleanValue launchNearPlayer;
        public final ModConfigSpec.IntValue droneFlightTime;
        public final ModConfigSpec.IntValue missileFlightTime;
        public final ModConfigSpec.IntValue bomberFlightTime;
        public final ModConfigSpec.IntValue loiterTime;
        public final ModConfigSpec.BooleanValue carrierNukes;

        public final ModConfigSpec.BooleanValue nukeEnabled;
        public final ModConfigSpec.BooleanValue nukeOpsOnly;
        public final ModConfigSpec.IntValue nukeDefaultYield;
        public final ModConfigSpec.IntValue nukeMaxYield;
        public final ModConfigSpec.DoubleValue nukeEffectsScale;
        public final ModConfigSpec.DoubleValue nukeWaveSpeed;
        public final ModConfigSpec.BooleanValue nukeBlockDamage;
        public final ModConfigSpec.BooleanValue nukeFires;
        public final ModConfigSpec.BooleanValue nukeTreeFall;
        public final ModConfigSpec.BooleanValue nukeCrater;
        public final ModConfigSpec.BooleanValue nukeFallout;
        public final ModConfigSpec.BooleanValue nukeRadiation;
        public final ModConfigSpec.BooleanValue nukeMobRadiation;
        public final ModConfigSpec.BooleanValue nukeBlackRain;
        public final ModConfigSpec.IntValue nukeFlightTime;
        public final ModConfigSpec.IntValue nukeTimeBudgetMs;
        public final ModConfigSpec.IntValue nukePrepMsPerTick;
        public final ModConfigSpec.BooleanValue nukeFarZone;
        public final ModConfigSpec.IntValue nukeRuinThreads;
        public final ModConfigSpec.IntValue nukeMaxFires;
        public final ModConfigSpec.IntValue nukeWarningRadius;
        public final ModConfigSpec.IntValue nukeSecondKeyDistance;
        public final ModConfigSpec.IntValue nukeSecondKeyWindow;

        public final ModConfigSpec.BooleanValue gridEnabled;
        public final ModConfigSpec.IntValue gridNodeRadius;
        public final ModConfigSpec.IntValue gridMaxRadius;
        public final ModConfigSpec.DoubleValue gridCascadeSpeed;
        public final ModConfigSpec.IntValue gridRestoreMinutes;
        public final ModConfigSpec.IntValue gridRestoreSpread;
        public final ModConfigSpec.BooleanValue gridNuke;
        public final ModConfigSpec.IntValue gridTimeBudgetMs;
        public final ModConfigSpec.IntValue workBudgetMs;

        public final ModConfigSpec.IntValue farRange;

        public final ModConfigSpec.IntValue samRadarRange;
        public final ModConfigSpec.IntValue samEngageRange;
        public final ModConfigSpec.DoubleValue samKillProbability;
        public final ModConfigSpec.IntValue samRails;
        public final ModConfigSpec.IntValue samReloadSeconds;

        Server(ModConfigSpec.Builder b) {
            b.translation("incoming.config.warheads").push("warheads");
            dronePower = b.comment("Shahed blast power (TNT = 4). Above 60 freezes the server.")
                    .translation("incoming.config.drone_power").defineInRange("drone_power", 12, 1, 60);
            missilePower = b.comment("Cruise missile blast power (TNT = 4).")
                    .translation("incoming.config.missile_power").defineInRange("missile_power", 20, 1, 60);
            rocketPower = b.comment("Blast power of each Grad rocket in a salvo (122 mm; TNT = 4).")
                    .translation("incoming.config.rocket_power").defineInRange("rocket_power", 7, 1, 60);
            loiterPower = b.comment("Lancet blast power (TNT = 4).")
                    .translation("incoming.config.loiter_power").defineInRange("loiter_power", 8, 1, 60);
            bunkerPower = b.comment("Underground blast power of the bunker buster (TNT = 4).")
                    .translation("incoming.config.bunker_power").defineInRange("bunker_power", 20, 1, 60);
            bunkerEnergy = b.comment("Bunker buster penetration: 1000 ≈ 30 blocks of stone.")
                    .translation("incoming.config.bunker_energy").defineInRange("bunker_energy", 1000, 50, 10000);
            b.pop();

            b.translation("incoming.config.world").push("world");
            blockDamage = b.comment("Blasts destroy blocks. Off: they still hurt players and mobs. Nuclear strikes use nuclear.block_damage.")
                    .translation("incoming.config.block_damage").define("block_damage", true);
            shatterGlass = b.comment("The blast wave breaks glass beyond the crater wherever outside air reaches it.")
                    .translation("incoming.config.shatter_glass").define("shatter_glass", true);
            fire = b.comment("Conventional blasts and burning debris start fires.")
                    .translation("incoming.config.fire").define("fire", true);
            debrisStay = b.comment("Debris stays as blocks where it lands. Off: it disappears.")
                    .translation("incoming.config.debris_stay").define("debris_stay", true);
            collapse = b.comment("An underground blast brings down the roof of the cavity above it.")
                    .translation("incoming.config.collapse").define("collapse", true);
            siren = b.comment("Air raid siren and on-screen warning for players within 350 blocks of the target.")
                    .translation("incoming.config.siren").define("siren", true);
            b.pop();

            b.translation("incoming.config.launch").push("launch");
            maxSalvo = b.comment("Largest number of rounds in one salvo.")
                    .translation("incoming.config.max_salvo").defineInRange("max_salvo", 30, 1, Loadout.MAX_COUNT);
            maxSpread = b.comment("Largest salvo spread, blocks.")
                    .translation("incoming.config.max_spread").defineInRange("max_spread", 150, 0, Loadout.MAX_SPREAD);
            maxActivePerPlayer = b.comment("How many rounds one player may have in progress (in flight and not yet fired in salvos) for a new order",
                            "to be accepted; operators are not limited. 0: no limit.")
                    .translation("incoming.config.max_active_per_player").defineInRange("max_active_per_player", 0, 0, 100_000);
            aimRange = b.comment("Designator binoculars range, blocks.")
                    .translation("incoming.config.aim_range").defineInRange("aim_range", 400, 32, 1024);
            mapRange = b.comment("Range of strikes at a place picked on the targeting map, blocks (horizontal distance from the player).",
                            "The server loads the target area and generates it where nobody has been: the larger the range, the more new terrain.")
                    .translation("incoming.config.map_range").defineInRange("map_range", 10_000, 256, 1_000_000);
            designatorForEveryone = b.comment("The designator works for every player, not only operators.")
                    .translation("incoming.config.designator_for_everyone").define("designator_for_everyone", true);
            mapPlayers = b.comment("The targeting map shows players in the same dimension (within map_range, except invisible players and spectators):",
                            "teammates (/team) always, others where they were last seen (with sight_rules) or all of them (without).",
                            "Off: the map does not draw them; the player target list on the console stays.")
                    .translation("incoming.config.map_players").define("map_players", true);
            sightRules = b.comment("Spotting: the designator strikes another player or aircraft only if the shooter or their /team sees it now or saw it",
                            "within sight_memory, directly or through their projectile camera; one seen earlier is struck at its last known place.",
                            "The map shows others the same way. /incoming from operators and the console ignores these rules.")
                    .translation("incoming.config.sight_rules").define("sight_rules", true);
            sightMemory = b.comment("How many seconds a player and their team remember a spotted player or aircraft.")
                    .translation("incoming.config.sight_memory").defineInRange("sight_memory", 30, 0, 600);
            launchNearPlayer = b.comment("Shaheds and missiles take off from a mobile launcher near the player who launched them (off: they come in from afar).")
                    .translation("incoming.config.launch_near_player").define("launch_near_player", true);
            droneFlightTime = b.comment("Shahed flight from launch to target, seconds: a detour and an attack run from behind (never shorter than the direct path).")
                    .translation("incoming.config.drone_flight_time").defineInRange("drone_flight_time", 50, 5, 600);
            missileFlightTime = b.comment("Cruise missile flight from launch to target, seconds (never shorter than the direct path).")
                    .translation("incoming.config.missile_flight_time").defineInRange("missile_flight_time", 30, 5, 600);
            bomberFlightTime = b.comment("B-2 approach until bomb release, seconds.")
                    .translation("incoming.config.bomber_flight_time").defineInRange("bomber_flight_time", 40, 5, 600);
            loiterTime = b.comment("How long a Lancet circles over the target before diving, seconds (±20% for each one in a salvo).")
                    .translation("incoming.config.loiter_time").defineInRange("loiter_time", 25, 0, 600);
            b.pop();

            b.translation("incoming.config.nuclear").push("nuclear");
            nukeEnabled = b.comment("Nuclear weapons are available: the ICBM, nuclear warheads and /incoming nuke.")
                    .translation("incoming.config.nuke_enabled").define("enabled", true);
            nukeOpsOnly = b.comment("Only operators can launch nuclear strikes. Off: other players need a second key from a player nearby.")
                    .translation("incoming.config.nuke_ops_only").define("ops_only", true);
            nukeDefaultYield = b.comment("Yield of /incoming nuke without a number, kt (15 is Hiroshima). On the console the yield is chosen with a button.")
                    .translation("incoming.config.nuke_default_yield").defineInRange("default_yield", 15, 1, Loadout.Nuke.MAX_YIELD);
            nukeMaxYield = b.comment("Largest yield, kt.")
                    .translation("incoming.config.nuke_max_yield").defineInRange("max_yield", Loadout.Nuke.MAX_YIELD, 1, Loadout.Nuke.MAX_YIELD);
            nukeEffectsScale = b.comment("Scale of all radii: 1.0 is true to life (1 block = 1 m); lower for small worlds.")
                    .translation("incoming.config.nuke_effects_scale").defineInRange("effects_scale", 1.0, 0.005, 1.0);
            nukeWaveSpeed = b.comment("Speed of the blast wave and its dust wall away from the fireball, as a share of the speed of sound",
                            "(1.0 is true to life). Lower gives the server more time to build ruins ahead of the wave and lets players",
                            "watch the wall roll in. Near the fireball the wave is always true to life; beyond the dust wall only the sound",
                            "travels on, at the speed of sound.")
                    .translation("incoming.config.nuke_wave_speed").defineInRange("wave_speed", 0.5, 0.15, 1.0);
            nukeBlockDamage = b.comment("The blast wave destroys buildings and trees. Off: no ruins and no crater.")
                    .translation("incoming.config.nuke_block_damage").define("block_damage", true);
            nukeFires = b.comment("The thermal pulse starts fires.")
                    .translation("incoming.config.nuke_fires").define("fires", true);
            nukeTreeFall = b.comment("Tree trunks fall whole, away from ground zero.")
                    .translation("incoming.config.nuke_tree_fall").define("tree_fall", true);
            nukeCrater = b.comment("A ground burst digs a crater.")
                    .translation("incoming.config.nuke_crater").define("crater", true);
            nukeFallout = b.comment("Radioactive fallout after a ground burst.")
                    .translation("incoming.config.nuke_fallout").define("fallout", true);
            nukeRadiation = b.comment("Radiation dose and radiation sickness for players.")
                    .translation("incoming.config.nuke_radiation").define("radiation", true);
            nukeMobRadiation = b.comment("Radiation dose and sickness for mobs (except the undead): a dose at the burst and in the fallout trail,",
                            "sickness in the same stages as players. Off: prompt radiation kills mobs at once (from 1000 rem) or takes",
                            "half their health (from 400 rem), and fallout does not affect them.")
                    .translation("incoming.config.nuke_mob_radiation").define("mob_radiation", true);
            nukeBlackRain = b.comment("Black rain in the fallout trail (contaminates until washed off with water).")
                    .translation("incoming.config.nuke_black_rain").define("black_rain", true);
            nukeFlightTime = b.comment("ICBM flight from launch to detonation, ticks (about 30 minutes in reality). A non-operator strike takes at least 1800 (90 s of alarm).")
                    .translation("incoming.config.nuke_flight_time").defineInRange("flight_time", 1800, 200, 72_000);
            nukeTimeBudgetMs = b.comment("Limit for nuclear destruction within the total per-tick budget (performance.work_ms_per_tick), ms (1–45).",
                            "The default 30 keeps destruction right behind the blast front at the cost of some TPS while the wave runs;",
                            "while the blackout has work, it keeps its grid.ms_per_tick.")
                    .translation("incoming.config.nuke_time_budget").defineInRange("destruction_ms_per_tick", 30, 1, 45);
            nukePrepMsPerTick = b.comment("Milliseconds of each tick the server spends on building ruins in advance while the ICBM flies (0–20).",
                            "Ruins of the near zone are built during the flight and placed as the blast front arrives.")
                    .translation("incoming.config.nuke_prep_budget").defineInRange("prep_ms_per_tick", 8, 0, 20);
            nukeFarZone = b.comment("When players use Distant Horizons: the heavy-damage zone that is not fully generated on disk (the edge of the explored",
                            "world) is generated in the background behind the wave (at most two 5×5 squares at a time), so the ruins are",
                            "visible from afar at once. Off: ruins appear there only when a player gets there.")
                    .translation("incoming.config.nuke_far_zone").define("far_zone", true);
            nukeRuinThreads = b.comment("How many background threads build ruin plans (breaks, collapse and fill from chunk snapshots, parsing chunks",
                            "from disk). 0: automatic, all cores but three (server thread, rendering, world I/O and generation;", "on a dedicated server all but two), at least one. The mod adjusts how many of them work",
                            "to the server tick. The server thread only snapshots chunks and places finished ruins.")
                    .translation("incoming.config.nuke_ruin_threads").defineInRange("ruin_threads", 0, 0, 256);
            nukeMaxFires = b.comment("Largest number of fires from one detonation.")
                    .translation("incoming.config.nuke_max_fires").defineInRange("fires_per_detonation", 20_000, 0, 100_000);
            nukeWarningRadius = b.comment("Players within this many blocks of the target hear the nuclear alarm.")
                    .translation("incoming.config.nuke_warning_radius").defineInRange("warning_radius", 20_000, 100, 1_000_000);
            nukeSecondKeyDistance = b.comment("A nuclear strike by a non-operator (ops_only = false) needs a second key: another player within this many blocks",
                            "of the launching player presses their Second key. Alone online: no key needed.")
                    .translation("incoming.config.nuke_second_key_distance").defineInRange("second_key_distance", 16, 2, 128);
            nukeSecondKeyWindow = b.comment("How many seconds to wait for the second key; without it there is no launch and the munitions are returned.")
                    .translation("incoming.config.nuke_second_key_window").defineInRange("second_key_window", 30, 5, 300);
            carrierNukes = b.comment("A nuclear warhead on the cruise missile and the B-2 bomb as well as the ICBM.")
                    .translation("incoming.config.carrier_nukes").define("carrier_nukes", true);
            b.pop();

            b.translation("incoming.config.grid").push("grid");
            gridEnabled = b.comment("Blackout: a strike on a substation cuts power to its area, and electric lights go out district by district.",
                            "Torches, candles, campfires and furnaces keep burning: they are not on the grid.")
                    .translation("incoming.config.grid_enabled").define("enabled", true);
            gridNodeRadius = b.comment("Radius of the area a substation powers, blocks.")
                    .translation("incoming.config.grid_node_radius").defineInRange("node_radius", 512, 16, 4096);
            gridMaxRadius = b.comment("Largest radius of one outage, blocks (a nuclear strike goes no further).")
                    .translation("incoming.config.grid_max_radius").defineInRange("max_radius", 4096, 64, 16_384);
            gridCascadeSpeed = b.comment("How fast the outage spreads from the substation across districts, blocks per second.")
                    .translation("incoming.config.grid_cascade_speed").defineInRange("cascade_speed", 60.0, 1.0, 10_000.0);
            gridRestoreMinutes = b.comment("Minutes until power starts coming back (0: only with /incoming grid restore).")
                    .translation("incoming.config.grid_restore_minutes").defineInRange("restore_minutes", 15, 0, 10_080);
            gridRestoreSpread = b.comment("Seconds over which power returns to all districts, in random order.")
                    .translation("incoming.config.grid_restore_spread").defineInRange("restore_spread_seconds", 90, 0, 3600);
            gridNuke = b.comment("A nuclear strike cuts power within its whole damage radius.")
                    .translation("incoming.config.grid_nuke").define("nuke_blackout", true);
            gridTimeBudgetMs = b.comment("Limit for blackout lights within the total per-tick budget (performance.work_ms_per_tick), ms (1–20).")
                    .translation("incoming.config.grid_time_budget").defineInRange("ms_per_tick", 4, 1, 20);
            b.pop();

            b.translation("incoming.config.performance").push("performance");
            workBudgetMs = b.comment("At most how many milliseconds of each tick the server spends on the mod's heavy work in total (5–45):",
                            "strike destruction, then the nuke (up to destruction_ms_per_tick), then blackout lights (up to",
                            "their ms_per_tick). The full budget comes only from time left free in the tick: a server behind schedule",
                            "gets 2 ms for each of the three. More: salvo damage and nuclear ruins appear sooner;",
                            "less: a steadier tick, craters and ruins take longer to finish.")
                    .translation("incoming.config.work_budget").defineInRange("work_ms_per_tick", 30, 5, 45);
            b.pop();

            b.translation("incoming.config.visibility").push("visibility");
            farRange = b.comment("How far players see and hear projectiles in flight and conventional blasts, blocks. Beyond render distance they show",
                            "as a model (a dot when smaller than a couple of pixels), flame, trail, fireball and smoke column; sound arrives with",
                            "the speed-of-sound delay. Haze hides them anyway further out; a lower value sends fewer packets to players.")
                    .translation("incoming.config.far_range").defineInRange("far_range", 8000, 640, 32_000);
            b.pop();

            b.translation("incoming.config.air_defense").push("air_defense");
            samRadarRange = b.comment("SAM radar range, blocks. Stealthy targets (B-2, low-flying cruise missile, Lancet) are detected closer.",
                            "The owner's side gets an alert for every new hostile target on the radar.")
                    .translation("incoming.config.sam_radar_range").defineInRange("radar_range", 3000, 64, 16_000);
            samEngageRange = b.comment("SAM engagement range, blocks: within it the SAM fires at hostile Shaheds, cruise missiles, Lancets and B-2s",
                            "(it does not intercept ICBMs or Grad rockets).")
                    .translation("incoming.config.sam_engage_range").defineInRange("engage_range", 1500, 32, 8000);
            samKillProbability = b.comment("Chance that an interceptor bursting near its target brings it down (0–1). On a miss the target flies on.")
                    .translation("incoming.config.sam_kill_probability").defineInRange("kill_probability", 0.8, 0.0, 1.0);
            samRails = b.comment("How many missiles stand on the SAM rails ready to fire.")
                    .translation("incoming.config.sam_rails").defineInRange("rails", 4, 1, 16);
            samReloadSeconds = b.comment("Seconds to put the next missile from the SAM stock on a free rail.")
                    .translation("incoming.config.sam_reload").defineInRange("reload_seconds", 8, 0, 600);
            b.pop();
        }
    }

    public static final class Client {
        public final ModConfigSpec.DoubleValue cameraShake;
        public final ModConfigSpec.DoubleValue flash;
        public final ModConfigSpec.BooleanValue hud;
        public final ModConfigSpec.BooleanValue autoCamera;
        public final ModConfigSpec.DoubleValue zoom;
        public final ModConfigSpec.EnumValue<CloudQuality> nukeCloudQuality;
        public final ModConfigSpec.BooleanValue nukeTinnitus;
        public final ModConfigSpec.BooleanValue soundMuffling;
        public final ModConfigSpec.BooleanValue mapPrefetch;

        public enum CloudQuality {
            LOW(750), MEDIUM(1500), HIGH(2500);

            public final int puffs;

            CloudQuality(int puffs) {
                this.puffs = puffs;
            }
        }

        Client(ModConfigSpec.Builder b) {
            cameraShake = b.comment("Camera shake strength (0: off). Aim is never disturbed: only the camera shakes.")
                    .translation("incoming.config.camera_shake").defineInRange("camera_shake", 1.0, 0.0, 2.0);
            flash = b.comment("Brightness of the on-screen explosion flash (0: off).")
                    .translation("incoming.config.flash").defineInRange("flash", 1.0, 0.0, 1.0);
            hud = b.comment("Show your projectiles in flight and their time to impact.")
                    .translation("incoming.config.hud").define("hud", true);
            autoCamera = b.comment("The projectile camera turns on by itself at every launch: launch from the side, flight from on board, impact (Shift exits).")
                    .translation("incoming.config.auto_camera").define("auto_camera", false);
            zoom = b.comment("Designator binocular zoom.")
                    .translation("incoming.config.zoom").defineInRange("zoom", 4.0, 1.5, 10.0);
            nukeCloudQuality = b.comment("Mushroom cloud detail: LOW / MEDIUM / HIGH = 750 / 1500 / 2500 puffs (plus 4/5 as many for the dust wall).")
                    .translation("incoming.config.nuke_cloud_quality").defineEnum("nuke_cloud_quality", CloudQuality.MEDIUM);
            nukeTinnitus = b.comment("Ringing ears and muffled hearing after a nearby blast wave.")
                    .translation("incoming.config.nuke_tinnitus").define("nuke_tinnitus", true);
            soundMuffling = b.comment("Strike sounds lose high tones with distance and are muffled behind hills and walls. With Sound Physics Remastered, that mod does it.")
                    .translation("incoming.config.sound_muffling").define("sound_muffling", true);
            mapPrefetch = b.comment("Terrain of the targeting map around the player is prepared in advance (from Distant Horizons) while a designator",
                            "is in the inventory or the map was opened in this world, so the map opens instantly.")
                    .translation("incoming.config.map_prefetch").define("map_prefetch", true);
        }
    }
}
