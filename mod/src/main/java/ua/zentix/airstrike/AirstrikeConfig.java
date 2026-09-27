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
        }
    }

    public static final class Client {
        public final ModConfigSpec.DoubleValue cameraShake;
        public final ModConfigSpec.DoubleValue flash;
        public final ModConfigSpec.BooleanValue hud;
        public final ModConfigSpec.DoubleValue zoom;

        Client(ModConfigSpec.Builder b) {
            cameraShake = b.comment("Сила тряски камеры (0 — выключить). Прицел не сбивается: трясётся только камера.")
                    .translation("airstrike.config.camera_shake").defineInRange("camera_shake", 1.0, 0.0, 2.0);
            flash = b.comment("Яркость вспышки взрыва на экране (0 — выключить).")
                    .translation("airstrike.config.flash").defineInRange("flash", 1.0, 0.0, 1.0);
            hud = b.comment("Показывать снаряды в полёте и время подлёта.")
                    .translation("airstrike.config.hud").define("hud", true);
            zoom = b.comment("Кратность бинокля пульта.")
                    .translation("airstrike.config.zoom").defineInRange("zoom", 4.0, 1.5, 10.0);
        }
    }
}
