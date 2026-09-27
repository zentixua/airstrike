package ua.zentix.airstrike;

import net.neoforged.neoforge.common.ModConfigSpec;

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
        public final ModConfigSpec.BooleanValue designatorForEveryone;

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
                    .translation("airstrike.config.max_salvo").defineInRange("max_salvo", 30, 1, 100);
            maxSpread = b.comment("Наибольший разброс залпа, блоков.")
                    .translation("airstrike.config.max_spread").defineInRange("max_spread", 150, 0, 500);
            aimRange = b.comment("Дальность прицела пульта, блоков.")
                    .translation("airstrike.config.aim_range").defineInRange("aim_range", 400, 32, 1024);
            designatorForEveryone = b.comment("Пульт работает у всех игроков, а не только у операторов.")
                    .translation("airstrike.config.designator_for_everyone").define("designator_for_everyone", true);
            b.pop();

            b.translation("airstrike.config.nuclear").push("nuclear");
            nukeEnabled = b.comment("Ядерное оружие доступно.")
                    .translation("airstrike.config.nuke_enabled").define("enabled", true);
            nukeOpsOnly = b.comment("Ядерный удар могут наносить только операторы.")
                    .translation("airstrike.config.nuke_ops_only").define("ops_only", true);
            nukeDefaultYield = b.comment("Мощность по умолчанию, кт (15 — Хиросима).")
                    .translation("airstrike.config.nuke_default_yield").defineInRange("default_yield", 15, 1, 50_000);
            nukeMaxYield = b.comment("Наибольшая мощность, кт.")
                    .translation("airstrike.config.nuke_max_yield").defineInRange("max_yield", 1000, 1, 50_000);
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
            nukeBlackRain = b.comment("Чёрный дождь в следе осадков (заражает, пока не смыть водой).")
                    .translation("airstrike.config.nuke_black_rain").define("black_rain", true);
            nukeFlightTime = b.comment("Полёт МБР от пуска до подрыва, тиков (в жизни — 30 минут).")
                    .translation("airstrike.config.nuke_flight_time").defineInRange("flight_time", 1800, 200, 72_000);
            nukeTimeBudgetMs = b.comment("Сколько миллисекунд за тик сервер тратит на разрушения (1–20).")
                    .translation("airstrike.config.nuke_time_budget").defineInRange("time_budget_ms", 4, 1, 20);
            nukeMaxFires = b.comment("Наибольшее число пожаров от одного подрыва.")
                    .translation("airstrike.config.nuke_max_fires").defineInRange("max_fires", 4000, 0, 50_000);
            nukeWarningRadius = b.comment("Кто слышит ядерную тревогу, блоков от цели.")
                    .translation("airstrike.config.nuke_warning_radius").defineInRange("warning_radius", 20_000, 100, 1_000_000);
            b.pop();
        }
    }

    public static final class Client {
        public final ModConfigSpec.DoubleValue cameraShake;
        public final ModConfigSpec.DoubleValue flash;
        public final ModConfigSpec.BooleanValue hud;
        public final ModConfigSpec.DoubleValue zoom;
        public final ModConfigSpec.EnumValue<CloudQuality> nukeCloudQuality;
        public final ModConfigSpec.BooleanValue nukeTinnitus;

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
            zoom = b.comment("Кратность бинокля пульта.")
                    .translation("airstrike.config.zoom").defineInRange("zoom", 4.0, 1.5, 10.0);
            nukeCloudQuality = b.comment("Подробность ядерного гриба: LOW / MEDIUM / HIGH — 300 / 600 / 1200 клубов.")
                    .translation("airstrike.config.nuke_cloud_quality").defineEnum("nuke_cloud_quality", CloudQuality.MEDIUM);
            nukeTinnitus = b.comment("Звон в ушах и глухота после близкой ударной волны.")
                    .translation("airstrike.config.nuke_tinnitus").define("nuke_tinnitus", true);
        }
    }
}
