package ua.zentix.incoming.client.fx;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.sound.ClientSounds;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BunkerCover;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Пыль с потолка: камера под сводом (метро, бункер, подвал, тоннель), удар рядом сверху или вровень — когда до неё
 * дошёл фронт ({@link BlastEffects.Timeline}, как звук и тряска), со свода над камерой и вокруг сыплются пыль
 * и каменная крошка: несколько густых струй из трещин и крупинки по всему своду; чем дальше удар и меньше заряд, тем
 * реже, дальше дальности заряда — ничего.
 * <p>
 * Сила — по скорости частиц грунта у свода (TM 5-855-1, армия США, 1986: v = 160·f·(R/W^⅓)^−n фут/с, R в футах,
 * W в фунтах ТНТ): W — заряд из паспорта ({@link ua.zentix.incoming.strike.WeaponSpec#shockCharge}; оружие — по пути
 * снаряда из пакета, без пути — по виду взрыва), f — доля энергии в грунте: 0,14 у наземного подрыва, у заглублённого
 * растёт до 1 к 0,4 м/кг^⅓ (бомба; между — по прямой, оценка), n = 2,5 (плотный грунт). Пыль сыплется с 2 мм/с (толчок
 * ясно чувствуется), в полную силу — с 50 мм/с (2 дюйма в секунду: с этого у домов трескается штукатурка, USBM RI 8507);
 * между — по логарифму скорости. Так крылатая ракета (450 кг) стряхивает пыль до ~79 блоков, в полную силу — до ~22,
 * шахед — до 38 и 10, «Град» — до 19 и 5, «Ланцет» — до 15 и 4, бомба в грунте (2,4 т) — до 300 и 83.
 * <p>
 * Свод ищется один раз на удар и только по блокам клиента (чанки не грузятся): колонка над глазом — есть ли свод не выше
 * {@link #REACH}, потом лучи от глаза к своду по кругу {@link #AREA} — где его низ виден из глаза. Пыль — ванильная
 * {@code FALLING_DUST} блока свода (цвет — его, как под песком на весу), крошка — кусочки этого блока ({@link Fragments}),
 * облачка пыли — клубы слоя эффектов в своей группе ({@link FxBudget#DUST}); звук осыпи ({@code debris.fall}, тот же, что
 * у бомбы под землёй) — только у сильного удара. Крупинок на удар — не больше {@link #GRAINS}, живых у всех ударов —
 * не больше {@link #GRAIN_CAP}: залп над станцией не забивает движок частиц.
 */
public final class CeilingDust {
    /** Скорость грунта по TM 5-855-1 в СИ: v = V0·f·(K·R/W^⅓)^−n м/с — 160 фут/с и футы на фунт^⅓ в метрах на кг^⅓. */
    static final double V0 = 160 * 0.3048, K = 3.2808 / Math.cbrt(2.2046);
    /** Затухание с расстоянием n: плотный грунт. */
    static final double N = 2.5;
    /** Доля энергии в грунте: у наземного подрыва; полностью в грунте — с заглубления BURIED м/кг^⅓. */
    static final double SURFACE = 0.14, BURIED = 0.4;
    /** Скорость грунта у свода, м/с: с неё сыплется пыль, с неё — в полную силу. */
    static final double TRICKLE = 0.002, POUR = 0.05;
    /** Ниже глаза больше чем на столько блоков удар — не сверху и не вровень: пыли нет. */
    static final double LEVEL = 4;
    /** Свод — не выше стольких блоков над глазом; лучи к нему — по кругу такого радиуса, блоков. */
    static final int REACH = 12;
    static final double AREA = 10;
    /** Лучей к своду на удар. */
    static final int RAYS = 48;
    /** На удар в полную силу: крупинок, струй из трещин, кусочков крошки, облачков у струи и по своду. */
    static final int GRAINS = 900, STREAMS = 5, BITS = 40, STREAM_PUFFS = 8, HAZE_PUFFS = 20;
    /** Доля крупинок в струях, когда они есть (остальные — по всему своду). */
    static final double STREAM_SHARE = 0.55;
    /** Сколько тиков после фронта сыплется; спад струи и крупинок по своду — по экспоненте за столько тиков. */
    static final int POUR_TICKS = 140;
    static final double STREAM_TAU = 22, HAZE_TAU = 30;
    /** Трещина открывается через 1..столько тиков после фронта; кусочки и облачки — первые столько тиков струи. */
    static final int CRACK_DELAY = 8, BITS_TICKS = 16, PUFF_TICKS = 24;
    /** Самая долгая жизнь крупинки {@code FALLING_DUST}, тиков (32 / 0,2 · 0,9), и сколько их живёт сразу у всех ударов. */
    static final int GRAIN_LIFE = 145, GRAIN_CAP = 2400;
    /** С этой силы слышно, как сыплется свод. */
    static final double SOUND_FROM = 0.5;

    /** Живые крупинки всех ударов (по тикам рождения). */
    private static final Births GRAINS_LIVE = new Births(GRAIN_LIFE, GRAIN_CAP);
    /** Последняя пыль — для строк сценария. */
    private static Pour last;

    private CeilingDust() {}

    // ---------------------------------------------------------------- сила и дальность (без Minecraft)

    /**
     * Доля энергии заряда {@code charge} кг ТНТ, ушедшая в грунт, при подрыве на глубине {@code depth} блоков: 0,14
     * у поверхности, 1 — с заглубления {@link #BURIED} м/кг^⅓ (у бомбы в 2,4 т — с 5,4 блока).
     */
    static double coupling(double depth, double charge) {
        if (charge <= 0) return 0;
        double scaled = Math.max(0, depth) / Math.cbrt(charge);
        return SURFACE + (1 - SURFACE) * Math.min(1, scaled / BURIED);
    }

    /** Скорость частиц грунта, м/с, в {@code r} блоках от заряда {@code charge} кг ТНТ с долей в грунте {@code coupling}. */
    static double velocity(double charge, double coupling, double r) {
        if (charge <= 0 || coupling <= 0) return 0;
        return V0 * coupling * Math.pow(K * Math.max(r, 1) / Math.cbrt(charge), -N);
    }

    /** Сила пыли по скорости грунта у свода: 0 — не сыплется (до {@link #TRICKLE}), 1 — в полную силу (с {@link #POUR}). */
    static double intensity(double v) {
        if (v <= TRICKLE) return 0;
        return Math.min(1, Math.log(v / TRICKLE) / Math.log(POUR / TRICKLE));
    }

    /** Дальше стольких блоков заряд пыли не стряхивает ({@link #velocity} = {@link #TRICKLE}). */
    static double range(double charge, double coupling) {
        if (charge <= 0 || coupling <= 0) return 0;
        return Math.cbrt(charge) / K * Math.pow(V0 * coupling / TRICKLE, 1 / N);
    }

    /** Удар сверху или вровень с глазом (не ниже его больше чем на {@link #LEVEL} блоков). */
    static boolean overhead(double blastY, double eyeY) {
        return blastY >= eyeY - LEVEL;
    }

    /**
     * Свод над глазом по колонке клеток вверх ({@code solid}: 0 — клетка глаза, 1 — над ней …): номер первой клетки свода
     * не выше {@code reach}; −1 — свода нет (небо, высокий зал, только стекло и листва) или глаз в самом блоке.
     */
    static int roof(IntPredicate solid, int reach) {
        if (solid.test(0)) return -1;
        for (int i = 1; i <= reach; i++) {
            if (solid.test(i)) return i;
        }
        return -1;
    }

    /** Крупинок на удар силой s: от горсти у слабого до {@link #GRAINS}. */
    static int grains(double s) {
        return s <= 0 ? 0 : (int) Math.round(GRAINS * Math.pow(Math.min(1, s), 1.5));
    }

    /** Струй из трещин: с силы ~0,46, у слабого удара их нет — только редкие крупинки. */
    static int streams(double s) {
        return s <= 0 ? 0 : (int) Math.round(STREAMS * Math.pow(Math.min(1, s), 3));
    }

    /** Кусочков крошки на удар. */
    static int bits(double s) {
        return s <= 0 ? 0 : (int) Math.round(BITS * Math.min(1, s) * Math.min(1, s));
    }

    /** Облачков пыли по своду: с силы 0,3, к полной — {@link #HAZE_PUFFS}. */
    static int hazePuffs(double s) {
        return s <= 0.3 ? 0 : (int) Math.round(HAZE_PUFFS * (Math.min(1, s) - 0.3) / 0.7);
    }

    /** Доля всего, что сыплется, в тик k: спад по экспоненте за tau тиков, за {@link #POUR_TICKS} — всё. */
    static double share(int k, double tau) {
        if (k < 0 || k >= POUR_TICKS) return 0;
        return (Math.exp(-k / tau) - Math.exp(-(k + 1) / tau)) / (1 - Math.exp(-POUR_TICKS / tau));
    }

    /**
     * Сколько родилось в каждый из последних {@code life} тиков (по кругу): живых не больше суммы, и больше {@code cap}
     * не рождается. Свой счёт, а не по смерти частицы: движок выбрасывает частицы и без неё (как у {@link Fragments}).
     */
    static final class Births {
        private final int[] born;
        private final int cap;
        private int sum, tick;

        Births(int life, int cap) {
            this.born = new int[life];
            this.cap = cap;
        }

        /** Родить n: сколько из них можно (остальным мест нет), записаны в этот тик. */
        int take(int n) {
            int k = Math.max(0, Math.min(n, cap - sum));
            born[tick] += k;
            sum += k;
            return k;
        }

        /** Следующий тик: рождённые {@code life} тиков назад — из счёта. */
        void tick() {
            tick = (tick + 1) % born.length;
            sum -= born[tick];
            born[tick] = 0;
        }

        void reset() {
            Arrays.fill(born, 0);
            sum = 0;
        }

        int live() {
            return sum;
        }
    }

    // ---------------------------------------------------------------- в игре

    /** Тик клиента (не на паузе и не в заморозке, как движок частиц): старые крупинки — из счёта. */
    public static void tick() {
        GRAINS_LIVE.tick();
    }

    /** Выход из мира: движок сам убирает частицы. */
    public static void reset() {
        GRAINS_LIVE.reset();
        last = null;
    }

    /** Пакет взрыва: удар может стряхнуть пыль со свода у камеры — ждать фронта ({@link Pour}). */
    static void blast(S2C.Blast p) {
        WeaponType weapon = p.projectile().map(FlightTracks::get).map(t -> t.weapon).orElse(byKind(p.kind()));
        double charge = weapon.spec().shockCharge();
        // глубина — только у бомбы в грунте; наземный взрыв — у поверхности
        double depth = p.kind() == S2C.Blast.BUNKER ? BunkerCover.depth((int) p.surfaceY(), p.pos().y) : 0;
        double coupling = coupling(depth, charge);
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (!overhead(p.pos().y, eye.y) || eye.distanceTo(p.pos()) - REACH - AREA > range(charge, coupling)) return;
        // у бомбы под землёй своя осыпь в звуке (BlastSounds.bunker)
        Effects.add(new Pour(p.pos(), GroundMaterial.byId(p.material()), p.seed(), weapon, charge, coupling, p.kind() != S2C.Blast.BUNKER));
    }

    /** Оружие по виду взрыва, когда пути снаряда у клиента нет: «Ланцет» тогда — как шахед (вид у них один). */
    private static WeaponType byKind(int kind) {
        return switch (kind) {
            case S2C.Blast.MISSILE -> WeaponType.MISSILE;
            case S2C.Blast.ROCKET -> WeaponType.ROCKET;
            case S2C.Blast.BUNKER -> WeaponType.BUNKER;
            default -> WeaponType.DRONE;
        };
    }

    /** Последняя пыль с потолка (для строк {@code SCENARIO dust}): оружие, сила, свод, места, сколько родилось. */
    public static String describe() {
        Pour p = last;
        return p == null ? "" : p.describe();
    }

    /** Со свода из этого блока сыплется пыль: непрозрачный блок с моделью (камень, бетон, доски, плиты), не листва и не жидкость. */
    private static boolean holds(BlockState s) {
        return s.canOcclude() && s.getRenderShape() == RenderShape.MODEL && !s.is(BlockTags.LEAVES) && s.getFluidState().isEmpty();
    }

    /** Место на своде: низ блока, видный из глаза; сила там, пыль блока, цвет облачков. */
    private record Spot(double x, double y, double z, BlockState state, BlockPos pos, BlockParticleOption dust, double s, double view,
                        int color) {}

    /** Свод сыплется: ждёт фронта, ищет свод один раз и сыплет по плану, без поисков в тиках. */
    private static final class Pour extends BlastEffects.Timeline {
        private final WeaponType weapon;
        private final double charge, coupling;
        private final boolean sound;
        private boolean started, done;
        /** Тиков сыплется (не в заморозке). */
        private int tau;
        private double s0, v0;
        private int up;
        private Spot[] spots;
        /** Сумма сил мест по порядку: место крупинки по своду — по силе. */
        private double[] weights;
        private Spot[] cracks;
        private int[] delays;
        private double[] streamCarry;
        private double hazeCarry, bitCarry, puffCarry;
        private int streamGrains, hazeGrains, bitsLeft, hazePuffsLeft;
        private int planned, born, refused, bitsBorn, puffsBorn;

        Pour(Vec3 pos, GroundMaterial mat, long seed, WeaponType weapon, double charge, double coupling, boolean sound) {
            // своё зерно: случайное пыли не повторяет случайное картинки взрыва
            super(pos, mat, seed ^ 0x5DEECE66DL);
            this.weapon = weapon;
            this.charge = charge;
            this.coupling = coupling;
            this.sound = sound;
        }

        @Override
        void arrive(ClientLevel level, int band) {
            done = true;
            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 eye = camera.getPosition();
            if (!overhead(pos.y, eye.y)) return;
            BlockPos e = BlockPos.containing(eye);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            up = roof(i -> holds(level.getBlockState(m.set(e.getX(), e.getY() + i, e.getZ()))), REACH);
            if (up < 0) return;
            double h = Math.max(0.5, e.getY() + up - eye.y);
            v0 = velocity(charge, coupling, pos.distanceTo(eye.add(0, h, 0)));
            s0 = intensity(v0);
            if (s0 <= 0) return;
            List<Spot> found = trace(level, camera, eye, h);
            if (found.isEmpty()) return;
            spots = found.toArray(new Spot[0]);
            weights = new double[spots.length];
            double sum = 0;
            for (int i = 0; i < spots.length; i++) weights[i] = sum += spots[i].s;
            cracks = pickCracks(Math.min(streams(s0), spots.length));
            delays = new int[cracks.length];
            for (int i = 0; i < cracks.length; i++) delays[i] = 1 + random.nextInt(CRACK_DELAY);
            streamCarry = new double[cracks.length];
            planned = grains(s0);
            streamGrains = cracks.length == 0 ? 0 : (int) Math.round(planned * STREAM_SHARE / cracks.length);
            hazeGrains = planned - streamGrains * cracks.length;
            bitsLeft = bits(s0);
            hazePuffsLeft = Fx.count(hazePuffs(s0), eye);
            done = false;
            started = true;
            last = this;
        }

        /**
         * Лучи от глаза к своду по кругу {@link #AREA}: свод там, где луч упёрся в низ блока свода. Первый луч — прямо
         * вверх. Только блоки клиента: чего у него нет, то воздух.
         */
        private List<Spot> trace(ClientLevel level, Camera camera, Vec3 eye, double h) {
            Vec3 look = new Vec3(camera.getLookVector());
            List<Spot> found = new ArrayList<>(RAYS);
            for (int k = 0; k < RAYS; k++) {
                double a = random.nextDouble() * Mth.TWO_PI, r = k == 0 ? 0 : AREA * Math.sqrt(random.nextDouble());
                Vec3 to = eye.add(Math.cos(a) * r * 1.5, h * 1.5 + 0.5, Math.sin(a) * r * 1.5);
                BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
                if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.DOWN) continue;
                BlockPos at = hit.getBlockPos().immutable();
                BlockState state = level.getBlockState(at);
                if (!holds(state)) continue;
                Vec3 p = hit.getLocation();
                double s = intensity(velocity(charge, coupling, pos.distanceTo(p)));
                if (s <= 0) continue;
                Vec3 dir = p.subtract(eye).normalize();
                MapColor map = state.getMapColor(level, at);
                int color = map == null || map.col == 0 ? 0x8A8480 : map.col;
                found.add(new Spot(p.x, p.y - 0.05, p.z, state, at, new BlockParticleOption(ParticleTypes.FALLING_DUST, state), s,
                        0.5 + 0.5 * look.dot(dir), color));
            }
            return found;
        }

        /** Трещины — места посильнее и в поле зрения (струя за спиной камеры пропала бы зря), без повторов. */
        private Spot[] pickCracks(int n) {
            Spot[] out = new Spot[n];
            boolean[] taken = new boolean[spots.length];
            for (int c = 0; c < n; c++) {
                double total = 0;
                for (int i = 0; i < spots.length; i++) if (!taken[i]) total += crackWeight(spots[i]);
                double x = random.nextDouble() * total;
                int pick = -1;
                for (int i = 0; i < spots.length; i++) {
                    if (taken[i]) continue;
                    pick = i;
                    x -= crackWeight(spots[i]);
                    if (x <= 0) break;
                }
                taken[pick] = true;
                out[c] = spots[pick];
            }
            return out;
        }

        private static double crackWeight(Spot s) {
            return s.s * (0.05 + s.view * s.view);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (!started) return !done && t <= 61;
            // в заморозке частицы стоят: сыпать дальше, когда мир снова пойдёт
            if (!level.tickRateManager().runsNormally()) return true;
            int k = tau++;
            hazeCarry += hazeGrains * share(k, HAZE_TAU);
            for (; hazeCarry >= 1; hazeCarry--) grain(spot(), 0.45, 0);
            for (int i = 0; i < cracks.length; i++) {
                int ks = k - delays[i];
                if (ks < 0) continue;
                Spot c = cracks[i];
                streamCarry[i] += streamGrains * share(ks, STREAM_TAU);
                // струя: крупинки сыплются плотно и уже с разгоном
                for (; streamCarry[i] >= 1; streamCarry[i]--) grain(c, 0.07, -0.05 - 0.04 * random.nextDouble());
                if (ks < PUFF_TICKS && ks % (PUFF_TICKS / STREAM_PUFFS) == 0 && random.nextFloat() < Fx.density(new Vec3(c.x, c.y, c.z))) puff(level, c, false);
                if (ks == 0 && i == 0 && sound && s0 >= SOUND_FROM) {
                    float volume = (float) (0.3 + 0.4 * (s0 - SOUND_FROM) / (1 - SOUND_FROM));
                    ClientSounds.at(ModSounds.DEBRIS_FALL.get(), new Vec3(c.x, c.y - 1.2, c.z), volume, 1.25f);
                }
            }
            if (k < BITS_TICKS) {
                bitCarry += (double) bitsLeft / (BITS_TICKS - k);
                for (; bitCarry >= 1 && bitsLeft > 0; bitCarry--, bitsLeft--) bit(level, cracks.length > 0 ? cracks[random.nextInt(cracks.length)] : spot());
            }
            if (k < PUFF_TICKS && hazePuffsLeft > 0) {
                puffCarry += (double) hazePuffsLeft / (PUFF_TICKS - k);
                for (; puffCarry >= 1 && hazePuffsLeft > 0; puffCarry--, hazePuffsLeft--) puff(level, spot(), true);
            }
            return tau < POUR_TICKS;
        }

        /** Место по своду с вероятностью по силе. */
        private Spot spot() {
            double x = random.nextDouble() * weights[weights.length - 1];
            for (int i = 0; i < weights.length; i++) {
                if (x <= weights[i]) return spots[i];
            }
            return spots[spots.length - 1];
        }

        /** Крупинка пыли блока свода: как частица ванили — по настройке «Частицы» ({@code LevelRenderer.calculateParticleLevel}). */
        private void grain(Spot s, double jitter, double vy) {
            ParticleStatus status = Minecraft.getInstance().options.particles().get();
            if (status == ParticleStatus.MINIMAL || status == ParticleStatus.DECREASED && random.nextInt(3) == 0) return;
            if (GRAINS_LIVE.take(1) == 0) {
                refused++;
                return;
            }
            Particle p = Minecraft.getInstance().particleEngine.createParticle(s.dust, s.x + random.nextGaussian() * jitter, s.y,
                    s.z + random.nextGaussian() * jitter, 0, 0, 0);
            // у FALLING_DUST своей скорости нет (она не берёт её из пакета): струя сыплется уже с разгоном
            if (p != null && vy != 0) p.setParticleSpeed(0, vy, 0);
            born++;
        }

        /** Кусочек крошки блока свода падает и ложится на пол. */
        private void bit(ClientLevel level, Spot s) {
            float size = 0.04f + 0.05f * random.nextFloat();
            if (Fragments.launch(level, s.state, s.pos, s.x + random.nextGaussian() * 0.1, s.y - size, s.z + random.nextGaussian() * 0.1,
                    random.nextGaussian() * 0.015, -0.02, random.nextGaussian() * 0.015, size, 0)) bitsBorn++;
        }

        /** Облачко пыли: у струи — узкое и тяжелее, сыплется с ней; по своду — шире, бледнее и висит дольше. */
        private void puff(ClientLevel level, Spot s, boolean haze) {
            int from = Explosions.lighten(s.color, 0.25f), to = Explosions.lighten(s.color, 0.45f);
            Fx.Spec spec = haze
                    ? Fx.smoke().vel(random.nextGaussian() * 0.01, -0.01, random.nextGaussian() * 0.01).size(0.3f, 1.6f + 0.8f * random.nextFloat())
                            .life(150 + random.nextInt(70)).alpha((float) (0.14 + 0.1 * s0)).gravity(0.0015f)
                    : Fx.smoke().vel(random.nextGaussian() * 0.01, -0.05, random.nextGaussian() * 0.01).size(0.12f, 0.7f + 0.4f * random.nextFloat())
                            .life(110 + random.nextInt(60)).alpha((float) (0.28 + 0.14 * s0)).gravity(0.004f);
            spec.color(from, to).drag(0.9f).wind(0).collide().fadeIn(3).fadeFrom(0.35f).spin(0.01f).budget(FxBudget.DUST)
                    .spawn(level, s.x + random.nextGaussian() * (haze ? 0.4 : 0.05), s.y - 0.25, s.z + random.nextGaussian() * (haze ? 0.4 : 0.05));
            puffsBorn++;
        }

        String describe() {
            return String.format(Locale.ROOT, "%s s=%.2f v=%.1fmm/s roof=+%d spots=%d streams=%d grains=%d/%d refused=%d bits=%d puffs=%d live=%d tick=%d",
                    weapon.getSerializedName(), s0, v0 * 1000, up, spots.length, cracks.length, born, planned, refused, bitsBorn, puffsBorn,
                    GRAINS_LIVE.live(), tau);
        }
    }
}
