package ua.zentix.airstrike;

import net.neoforged.neoforge.common.ModConfigSpec;
import ua.zentix.airstrike.strike.Loadout;

/**
 * Настройки. SERVER хранится в мире (serverconfig/airstrike-server.toml), CLIENT — у каждого игрока.
 * Экран настроек — штатный NeoForge (Моды → Airstrike → Настроить).
 */
public final class AirstrikeConfig {
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

    private AirstrikeConfig() {}

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
        public final ModConfigSpec.IntValue aimRange;
        public final ModConfigSpec.IntValue mapRange;
        public final ModConfigSpec.BooleanValue designatorForEveryone;
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
        public final ModConfigSpec.IntValue nukeMaxFires;
        public final ModConfigSpec.IntValue nukeWarningRadius;

        Server(ModConfigSpec.Builder b) {
            b.translation("airstrike.config.warheads").push("warheads");
            dronePower = b.comment("Сила взрыва шахеда (TNT = 4). Больше 60 вешает сервер.")
                    .translation("airstrike.config.drone_power").defineInRange("drone_power", 12, 1, 60);
            missilePower = b.comment("Сила взрыва крылатой ракеты.")
                    .translation("airstrike.config.missile_power").defineInRange("missile_power", 20, 1, 60);
            rocketPower = b.comment("Сила взрыва реактивного снаряда РСЗО (122 мм, как у «Града»): каждого из залпа.")
                    .translation("airstrike.config.rocket_power").defineInRange("rocket_power", 7, 1, 60);
            loiterPower = b.comment("Сила взрыва барражирующего боеприпаса.")
                    .translation("airstrike.config.loiter_power").defineInRange("loiter_power", 8, 1, 60);
            bunkerPower = b.comment("Сила подземного взрыва бетонобойной бомбы.")
                    .translation("airstrike.config.bunker_power").defineInRange("bunker_power", 20, 1, 60);
            bunkerEnergy = b.comment("Пробивная способность бомбы: 1000 ≈ 30 блоков камня.")
                    .translation("airstrike.config.bunker_energy").defineInRange("bunker_energy", 1000, 50, 10000);
            b.pop();

            b.translation("airstrike.config.world").push("world");
            blockDamage = b.comment("Взрывы разрушают блоки.")
                    .translation("airstrike.config.block_damage").define("block_damage", true);
            shatterGlass = b.comment("Ударная волна выбивает стёкла.")
                    .translation("airstrike.config.shatter_glass").define("shatter_glass", true);
            fire = b.comment("Взрывы поджигают.")
                    .translation("airstrike.config.fire").define("fire", true);
            debrisStay = b.comment("Обломки остаются лежать блоками.")
                    .translation("airstrike.config.debris_stay").define("debris_stay", true);
            collapse = b.comment("Подземный взрыв обрушивает свод над полостью.")
                    .translation("airstrike.config.collapse").define("collapse", true);
            siren = b.comment("Сирена воздушной тревоги у тех, кто рядом с целью.")
                    .translation("airstrike.config.siren").define("siren", true);
            b.pop();

            b.translation("airstrike.config.launch").push("launch");
            maxSalvo = b.comment("Сколько снарядов может быть в одном залпе.")
                    .translation("airstrike.config.max_salvo").defineInRange("max_salvo", 30, 1, Loadout.MAX_COUNT);
            maxSpread = b.comment("Наибольший разброс залпа, блоков.")
                    .translation("airstrike.config.max_spread").defineInRange("max_spread", 150, 0, Loadout.MAX_SPREAD);
            aimRange = b.comment("Дальность прицела пульта, блоков.")
                    .translation("airstrike.config.aim_range").defineInRange("aim_range", 400, 32, 1024);
            mapRange = b.comment("Дальность удара по месту, выбранному на карте пульта, блоков (по горизонтали от игрока).")
                    .translation("airstrike.config.map_range").defineInRange("map_range", 10_000, 256, 1_000_000);
            designatorForEveryone = b.comment("Пульт работает у всех игроков, а не только у операторов.")
                    .translation("airstrike.config.designator_for_everyone").define("designator_for_everyone", true);
            launchNearPlayer = b.comment("Шахеды и ракеты стартуют с мобильной пусковой рядом с тем, кто пустил (иначе заходят издалека).")
                    .translation("airstrike.config.launch_near_player").define("launch_near_player", true);
            droneFlightTime = b.comment("Полёт шахеда от пуска до цели, секунд: маршрут в обход и заход из-за спины (не меньше прямого пути).")
                    .translation("airstrike.config.drone_flight_time").defineInRange("drone_flight_time", 50, 5, 600);
            missileFlightTime = b.comment("Полёт крылатой ракеты от пуска до цели, секунд.")
                    .translation("airstrike.config.missile_flight_time").defineInRange("missile_flight_time", 30, 5, 600);
            bomberFlightTime = b.comment("Подлёт B-2 до сброса, секунд.")
                    .translation("airstrike.config.bomber_flight_time").defineInRange("bomber_flight_time", 40, 5, 600);
            loiterTime = b.comment("Сколько барражирующий боеприпас кружит над целью, прежде чем пикировать, секунд (у каждого в залпе ±20%).")
                    .translation("airstrike.config.loiter_time").defineInRange("loiter_time", 25, 0, 600);
            b.pop();

            b.translation("airstrike.config.nuclear").push("nuclear");
            nukeEnabled = b.comment("Ядерное оружие доступно.")
                    .translation("airstrike.config.nuke_enabled").define("enabled", true);
            nukeOpsOnly = b.comment("Ядерный удар могут наносить только операторы.")
                    .translation("airstrike.config.nuke_ops_only").define("ops_only", true);
            nukeDefaultYield = b.comment("Мощность для /airstrike nuke без числа, кт (15 — Хиросима). В пульте мощность выбирается на экране.")
                    .translation("airstrike.config.nuke_default_yield").defineInRange("default_yield", 15, 1, Loadout.Nuke.MAX_YIELD);
            nukeMaxYield = b.comment("Наибольшая мощность, кт.")
                    .translation("airstrike.config.nuke_max_yield").defineInRange("max_yield", 1000, 1, Loadout.Nuke.MAX_YIELD);
            nukeEffectsScale = b.comment("Масштаб всех радиусов: 1.0 — как в жизни (1 блок = 1 м), меньше — для маленьких миров.")
                    .translation("airstrike.config.nuke_effects_scale").defineInRange("effects_scale", 1.0, 0.005, 1.0);
            nukeBlockDamage = b.comment("Ударная волна разрушает постройки и деревья.")
                    .translation("airstrike.config.nuke_block_damage").define("block_damage", true);
            nukeFires = b.comment("Световой импульс поджигает.")
                    .translation("airstrike.config.nuke_fires").define("fires", true);
            nukeTreeFall = b.comment("Деревья валятся стволами от эпицентра.")
                    .translation("airstrike.config.nuke_tree_fall").define("tree_fall", true);
            nukeCrater = b.comment("Наземный подрыв роет воронку.")
                    .translation("airstrike.config.nuke_crater").define("crater", true);
            nukeFallout = b.comment("Радиоактивные осадки после наземного подрыва.")
                    .translation("airstrike.config.nuke_fallout").define("fallout", true);
            nukeRadiation = b.comment("Облучение и лучевая болезнь у игроков.")
                    .translation("airstrike.config.nuke_radiation").define("radiation", true);
            nukeMobRadiation = b.comment("Облучение и лучевая болезнь у мобов (кроме нежити): доза при подрыве и в следе осадков,",
                            "болезнь по тем же стадиям. Выключено — мобы гибнут от проникающей радиации сразу (от 1000 бэр) или теряют",
                            "половину здоровья (от 400), а осадки их не трогают.")
                    .translation("airstrike.config.nuke_mob_radiation").define("mob_radiation", true);
            nukeBlackRain = b.comment("Чёрный дождь в следе осадков (заражает, пока не смыть водой).")
                    .translation("airstrike.config.nuke_black_rain").define("black_rain", true);
            nukeFlightTime = b.comment("Полёт МБР от пуска до подрыва, тиков (в жизни — 30 минут).")
                    .translation("airstrike.config.nuke_flight_time").defineInRange("flight_time", 1800, 200, 72_000);
            nukeTimeBudgetMs = b.comment("Сколько миллисекунд каждого тика сервер тратит на разрушения (1–45). По умолчанию 30:",
                            "разрушения идут вслед за фронтом, как в жизни, ценой части TPS, пока идёт волна.")
                    .translation("airstrike.config.nuke_time_budget").defineInRange("destruction_ms_per_tick", 30, 1, 45);
            nukeMaxFires = b.comment("Наибольшее число пожаров от одного подрыва.")
                    .translation("airstrike.config.nuke_max_fires").defineInRange("fires_per_detonation", 20_000, 0, 100_000);
            nukeWarningRadius = b.comment("Кто слышит ядерную тревогу, блоков от цели.")
                    .translation("airstrike.config.nuke_warning_radius").defineInRange("warning_radius", 20_000, 100, 1_000_000);
            carrierNukes = b.comment("Ядерная боевая часть и на крылатой ракете и B-2 (кроме МБР).")
                    .translation("airstrike.config.carrier_nukes").define("carrier_nukes", true);
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

        public enum CloudQuality {
            LOW(300), MEDIUM(600), HIGH(1200);

            public final int puffs;

            CloudQuality(int puffs) {
                this.puffs = puffs;
            }
        }

        Client(ModConfigSpec.Builder b) {
            cameraShake = b.comment("Сила тряски камеры (0 — выключить). Прицел не сбивается: трясётся только камера.")
                    .translation("airstrike.config.camera_shake").defineInRange("camera_shake", 1.0, 0.0, 2.0);
            flash = b.comment("Яркость вспышки взрыва на экране (0 — выключить).")
                    .translation("airstrike.config.flash").defineInRange("flash", 1.0, 0.0, 1.0);
            hud = b.comment("Показывать снаряды в полёте и время подлёта.")
                    .translation("airstrike.config.hud").define("hud", true);
            autoCamera = b.comment("Камера снаряда включается сама при каждом пуске: пуск сбоку, полёт с борта, попадание (Shift — выход).")
                    .translation("airstrike.config.auto_camera").define("auto_camera", false);
            zoom = b.comment("Кратность бинокля пульта.")
                    .translation("airstrike.config.zoom").defineInRange("zoom", 4.0, 1.5, 10.0);
            nukeCloudQuality = b.comment("Подробность ядерного гриба: LOW / MEDIUM / HIGH — 300 / 600 / 1200 клубов.")
                    .translation("airstrike.config.nuke_cloud_quality").defineEnum("nuke_cloud_quality", CloudQuality.MEDIUM);
            nukeTinnitus = b.comment("Звон в ушах и глухота после близкой ударной волны.")
                    .translation("airstrike.config.nuke_tinnitus").define("nuke_tinnitus", true);
            soundMuffling = b.comment("Звук ударов глуше вдали (воздух съедает верха) и за холмом или стеной. С Sound Physics Remastered это делает он.")
                    .translation("airstrike.config.sound_muffling").define("sound_muffling", true);
        }
    }
}
