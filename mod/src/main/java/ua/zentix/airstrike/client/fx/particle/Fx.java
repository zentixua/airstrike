package ua.zentix.airstrike.client.fx.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.mojang.blaze3d.systems.RenderSystem;
import ua.zentix.airstrike.registry.ModParticles;

/**
 * Частицы эффектов: {@code Fx.smoke().at(…).vel(…).size(2, 9).life(200).color(…).spawn(level, x, y, z)}.
 * Настройка — {@link Spec} (его можно копировать и спаунить много раз); частица создаётся прямо в движке частиц,
 * без пакетов и без ванильного ограничения «не дальше 32 блоков».
 */
public final class Fx {
    /** Ветер (блоки/тик² на единицу парусности): дым и пыль сносит, столбы дыма наклоняются. */
    static final double WIND_X = 0.0022, WIND_Z = 0.0011;

    private static SpriteSet smoke, fire, spark, flash, ring;

    private Fx() {}

    public enum Kind {
        SMOKE(true, false, FxBudget.CLOUD), FIRE(false, false, FxBudget.CLOUD), SPARK(false, true, FxBudget.SPARK),
        FLASH(false, true, FxBudget.FLASH), RING(true, false, FxBudget.CLOUD);

        /** Освещается миром (дым, пыль) или светится сам (огонь, искры). */
        final boolean lit;
        /** Свет складывается (искры, вспышка). */
        final boolean additive;
        /** Группа по умолчанию. */
        final FxBudget budget;

        Kind(boolean lit, boolean additive, FxBudget budget) {
            this.lit = lit;
            this.additive = additive;
            this.budget = budget;
        }
    }

    public static Spec smoke() {
        return new Spec(Kind.SMOKE);
    }

    public static Spec fire() {
        return new Spec(Kind.FIRE).color(0xFFF0C0, 0x801808).alpha(0.9f).fadeFrom(0.3f);
    }

    public static Spec spark() {
        return new Spec(Kind.SPARK).color(0xFFF4D0, 0xB02800).size(0.12f, 0.08f).fadeFrom(0.5f).streak(2);
    }

    public static Spec flash() {
        return new Spec(Kind.FLASH).color(0xFFF6E0, 0xFF9030).fadeFrom(0.1f).alpha(1);
    }

    public static Spec ring() {
        return new Spec(Kind.RING).color(0xE0DCD6, 0xC8C0B6).fadeFrom(0.35f).alpha(0.55f).drag(1);
    }

    /**
     * Доля частиц по настройке «Частицы», расстоянию до камеры и месту в группе облаков ({@link FxBudget#headroom()}):
     * вдали мелочь не видна, а в большом залпе каждый новый взрыв получает поровну оставшегося места.
     */
    public static float density(Vec3 at) {
        return density(at, FxBudget.CLOUD);
    }

    /** То же для частиц группы {@code budget}. */
    public static float density(Vec3 at, FxBudget budget) {
        Minecraft mc = Minecraft.getInstance();
        float k = mc.options.particles().get() == ParticleStatus.ALL ? 1 : mc.options.particles().get() == ParticleStatus.DECREASED ? 0.6f : 0.3f;
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at);
        if (d > 160) k *= (float) Math.max(0.35, 160 / d);
        return k * budget.headroom();
    }

    /** Сколько частиц из {@code n} рождать в точке (не меньше одной, если n > 0). */
    public static int count(int n, Vec3 at) {
        return n <= 0 ? 0 : Math.max(1, Math.round(n * density(at)));
    }

    // ---------------------------------------------------------------- регистрация

    public static void registerProviders(RegisterParticleProvidersEvent e) {
        e.registerSpriteSet(ModParticles.SMOKE.get(), s -> provider(smoke = s, Kind.SMOKE));
        e.registerSpriteSet(ModParticles.FIRE.get(), s -> provider(fire = s, Kind.FIRE));
        e.registerSpriteSet(ModParticles.SPARK.get(), s -> provider(spark = s, Kind.SPARK));
        e.registerSpriteSet(ModParticles.FLASH.get(), s -> provider(flash = s, Kind.FLASH));
        e.registerSpriteSet(ModParticles.RING.get(), s -> provider(ring = s, Kind.RING));
    }

    /** Для команды /particle: частица с настройками по умолчанию. */
    private static net.minecraft.client.particle.ParticleProvider<SimpleParticleType> provider(SpriteSet sprites, Kind kind) {
        return (type, level, x, y, z, dx, dy, dz) -> {
            Spec s = switch (kind) {
                case SMOKE -> smoke().size(1, 4).life(120).rise(0.004f);
                case FIRE -> fire().size(0.8f, 1.6f).life(20).rise(0.01f);
                case SPARK -> spark().life(30).gravity(0.04f);
                case FLASH -> flash().size(4, 6).life(4);
                case RING -> ring().size(1, 20).life(20);
            };
            return s.vel(dx, dy, dz).create(level, x, y, z, sprites);
        };
    }

    /** Добавочное смешивание слоя света не должно утечь дальше частиц. */
    public static void afterParticles(RenderLevelStageEvent e) {
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) RenderSystem.defaultBlendFunc();
    }

    private static SpriteSet sprites(Kind kind) {
        return switch (kind) {
            case SMOKE -> smoke;
            case FIRE -> fire;
            case SPARK -> spark;
            case FLASH -> flash;
            case RING -> ring;
        };
    }

    // ---------------------------------------------------------------- настройка частицы

    /** Настройка частицы. Методы меняют этот же объект; {@link #copy()} — для вариаций одного шаблона. */
    public static final class Spec implements Cloneable {
        final Kind kind;
        double vx, vy, vz;
        float size0 = 1, size1 = 1;
        boolean growFast;
        int life = 40;
        float r0 = 1, g0 = 1, b0 = 1, r1 = 1, g1 = 1, b1 = 1;
        float colorCurve = 1;
        float alpha = 0.8f;
        int fadeIn = 3;
        float fadeFrom = 0.55f;
        float glow, glowTicks = 10;
        float drag = 0.96f;
        float rise, gravity, wind = 1, spin = 0.03f;
        boolean collide;
        float streak;
        Spec trail;
        float trailStep = 0.8f, trailUntil = 0.7f;
        FxBudget budget;

        Spec(Kind kind) {
            this.kind = kind;
            this.budget = kind.budget;
        }

        public Spec copy() {
            try {
                return (Spec) clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        public Spec vel(double x, double y, double z) {
            vx = x;
            vy = y;
            vz = z;
            return this;
        }

        public Spec vel(Vec3 v) {
            return vel(v.x, v.y, v.z);
        }

        /** Полуразмер в блоках: в начале и в конце жизни. */
        public Spec size(float from, float to) {
            size0 = from;
            size1 = to;
            return this;
        }

        /** Вырастает почти сразу (клуб взрыва), а не плавно (дым из трубы). */
        public Spec growFast() {
            growFast = true;
            return this;
        }

        public Spec life(int ticks) {
            life = ticks;
            return this;
        }

        public Spec color(int from, int to) {
            r0 = (from >> 16 & 0xFF) / 255f;
            g0 = (from >> 8 & 0xFF) / 255f;
            b0 = (from & 0xFF) / 255f;
            r1 = (to >> 16 & 0xFF) / 255f;
            g1 = (to >> 8 & 0xFF) / 255f;
            b1 = (to & 0xFF) / 255f;
            return this;
        }

        public Spec color(float r, float g, float b) {
            r0 = r1 = r;
            g0 = g1 = g;
            b0 = b1 = b;
            return this;
        }

        /** Цвет меняется по {@code f^curve} от доли жизни: > 1 — дольше держится начальный. */
        public Spec colorCurve(float curve) {
            colorCurve = curve;
            return this;
        }

        public Spec alpha(float a) {
            alpha = a;
            return this;
        }

        public Spec fadeIn(int ticks) {
            fadeIn = ticks;
            return this;
        }

        /** С какой доли жизни начинает таять. */
        public Spec fadeFrom(float f) {
            fadeFrom = Math.min(f, 0.99f);
            return this;
        }

        /** Накал: свечение огня изнутри (0..1), гаснет за {@code ticks}. */
        public Spec glow(float g, float ticks) {
            glow = g;
            glowTicks = ticks;
            return this;
        }

        /** Доля скорости, что остаётся за тик (сопротивление воздуха). */
        public Spec drag(float d) {
            drag = d;
            return this;
        }

        /** Всплытие горячего (блоки/тик²), постепенно слабеет. */
        public Spec rise(float r) {
            rise = r;
            return this;
        }

        public Spec gravity(float g) {
            gravity = g;
            return this;
        }

        public Spec wind(float w) {
            wind = w;
            return this;
        }

        public Spec spin(float s) {
            spin = s;
            return this;
        }

        /** Сталкивается с блоками (стелется по земле, не проходит сквозь стены). */
        public Spec collide() {
            collide = true;
            return this;
        }

        /** Чьё место занимает частица ({@link FxBudget}); группа должна быть из слоя этого вида частиц. */
        public Spec budget(FxBudget b) {
            if (b.additive != kind.additive) throw new IllegalArgumentException(b + " — не слой " + kind);
            budget = b;
            return this;
        }

        /** Искра: длина штриха — путь за столько тиков. */
        public Spec streak(float ticks) {
            streak = ticks;
            return this;
        }

        /** Искра оставляет дымный хвост из таких клубов через {@code step} блоков, пока не прожила {@code until} жизни. */
        public Spec trail(Spec puff, float step, float until) {
            trail = puff;
            trailStep = step;
            trailUntil = until;
            return this;
        }

        Particle create(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            return switch (kind) {
                case SPARK -> new FxSpark(level, x, y, z, this, sprites);
                case RING -> new FxRing(level, x, y, z, this, sprites);
                default -> new FxParticle(level, x, y, z, this, sprites);
            };
        }

        public void spawn(Level level, double x, double y, double z) {
            SpriteSet sprites = sprites(kind);
            if (sprites == null || !(level instanceof ClientLevel cl)) return;
            // снимок настройки: шаблон можно менять и спаунить дальше, частица этого не заметит
            Minecraft.getInstance().particleEngine.add(copy().create(cl, x, y, z, sprites));
        }

        public void spawn(Level level, Vec3 p) {
            spawn(level, p.x, p.y, p.z);
        }
    }
}
