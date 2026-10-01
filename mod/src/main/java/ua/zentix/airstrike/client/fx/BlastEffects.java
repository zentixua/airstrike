package ua.zentix.airstrike.client.fx;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.far.FarBlasts;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.client.sound.BlastSounds;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.util.Particles;
import ua.zentix.airstrike.warhead.BunkerCover;
import ua.zentix.airstrike.warhead.GroundMaterial;
import ua.zentix.airstrike.warhead.Warheads;

/**
 * Что видят и слышат игроки при взрывах — таймлайны fx/mfx/bfx датапака, перенесённые на клиент:
 * вспышка, огненный шар и «гриб», фонтан грунта цвета местности, кольцо пыли и сфера конденсации,
 * догорание; звук и тряска приходят вместе с фронтом ударной волны (17 блоков за тик).
 */
public final class BlastEffects {
    private BlastEffects() {}

    /**
     * Пакет взрыва: ближе {@link FarBlasts#NEAR} — частицы и звук поясами здесь; картинку дальше прорисовки и звук
     * дальше {@link FarBlasts#NEAR} ведёт {@link FarBlasts} (пакеты приходят до {@code far_range}).
     */
    public static void blast(S2C.Blast p) {
        if (FarBlasts.near(p.pos())) {
            GroundMaterial mat = GroundMaterial.byId(p.material());
            switch (p.kind()) {
                case S2C.Blast.MISSILE -> Effects.add(new Missile(p.pos(), mat, p.seed()));
                case S2C.Blast.BUNKER -> Effects.add(new Bunker(p.pos(), mat, p.surfaceY(), p.seed()));
                case S2C.Blast.ROCKET -> Effects.add(new Drone(p.pos(), mat, p.seed(), true));
                default -> Effects.add(new Drone(p.pos(), mat, p.seed(), false));
            }
        }
        FarBlasts.add(p);
    }

    // ---------------------------------------------------------------- общее

    /** Фронт звука и ударной волны: срабатывает у слушателя один раз, когда до него дошёл. */
    abstract static class Timeline implements Effects.Effect {
        final Vec3 pos;
        final GroundMaterial mat;
        final RandomSource random;
        private boolean arrived;

        Timeline(Vec3 pos, GroundMaterial mat, long seed) {
            this.pos = pos;
            this.mat = mat;
            this.random = RandomSource.create(seed);
        }

        @Override
        public final boolean tick(ClientLevel level, int t) {
            if (!arrived) {
                Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                double d = ear.distanceTo(pos);
                if (d <= Warheads.FRONT_SPEED * t || t > 60) {
                    arrived = true;
                    if (t <= 60) arrive(level, (int) (d / Warheads.FRONT_SPEED) + 1);
                }
            }
            return run(level, t);
        }

        abstract boolean run(ClientLevel level, int t);

        /** @param band пояс фронта: 1 — ближе 17 блоков, 2 — 17..34 … */
        abstract void arrive(ClientLevel level, int band);

        /**
         * Вспышка на экране: свет мгновенный, сила — {@link FlashFalloff} по расстоянию, взгляду и прямой видимости
         * от камеры (с борта снаряда и в камере наблюдения — оттуда, куда смотрит игрок, а не от его тела).
         *
         * @param near ближе этого — полная сила, блоки
         */
        void flash(ClientLevel level, double near, double range, float decay) {
            flash(level, pos, near, range, decay);
        }

        /** То же с источником света в {@code at}. */
        void flash(ClientLevel level, Vec3 at, double near, double range, float decay) {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 eye = camera.getPosition();
            Vec3 light = at.add(0, 1.5, 0);
            Vec3 to = light.subtract(eye);
            double d = to.length();
            if (d >= range) return;
            boolean visible = level.clip(new ClipContext(eye, light, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS;
            double cos = d < 1e-6 ? 1 : new Vec3(camera.getLookVector()).dot(to) / d;
            float s = FlashFalloff.strength(d, near, range, cos, visible);
            if (s > 0) Flash.trigger(s, decay, 0xFFF1C8);
        }

        /** Фонтан грунта из воронки и пылевое облако цвета местности (fx/spray). */
        void spray(ClientLevel level, int scale) {
            Particles.burst(level, FxParticles.block(mat), pos.add(0, 1, 0), 1.5, 1.5, 1.5, scale == 1 ? 0.9 : 1.3, scale == 1 ? 350 : 900);
            Particles.burst(level, FxParticles.block(mat), pos.add(0, 3, 0), 4, 3, 4, 0.4, scale == 1 ? 150 : 400);
            Particles.burst(level, FxParticles.dust(mat, 4), pos.add(0, 1.5, 0), 5, 1.2, 5, 0.02, scale == 1 ? 150 : 400);
            if (mat.isWater()) {
                Particles.burst(level, ParticleTypes.SPLASH, pos.add(0, 1, 0), 3, 3, 3, 0.8, scale == 1 ? 700 : 1800);
                Particles.burst(level, ParticleTypes.BUBBLE_POP, pos.add(0, 1, 0), 4, 2, 4, 0.3, scale == 1 ? 150 : 400);
            }
        }
    }

    // ================================================================ шахед (fx)

    static final class Drone extends Timeline {
        /** Радиус огненного шара, блоки (≈ 50 кг ВВ). */
        static final float R = 5f;
        /** Вспышка: ближе — полная сила, дальше второго — её нет, блоки. */
        static final double FLASH_NEAR = 3 * R, FLASH_RANGE = 240;
        /** Сколько тиков тянется столб дыма. */
        static final int COLUMN_TICKS = 50;

        /** Снаряд РСЗО: картинка та же, звук свой — сухой разрыв; пожара в воронке нет (заряд меньше). */
        private final boolean rocket;

        Drone(Vec3 pos, GroundMaterial mat, long seed, boolean rocket) {
            super(pos, mat, seed);
            this.rocket = rocket;
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                flash(level, FLASH_NEAR, FLASH_RANGE, 0.72f);
                Explosions.burst(level, pos, R, mat, random);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 2, 1, 2, 0, 30);
                return true;
            }
            if (t == 1) spray(level, 1);
            Explosions.column(level, pos, R, t, COLUMN_TICKS, 220, random);
            if (t == 8 && !rocket) BlastSounds.fire(pos, 1.1f);
            if (t == 20) BlastSounds.debris(pos, rocket ? 40 : 60, rocket ? 1.25f : 1.1f);
            return t < 240;
        }

        @Override
        void arrive(ClientLevel level, int band) {
            if (rocket) BlastSounds.rocket(pos, band);
            else BlastSounds.surface(pos, band, false);
            int shake = band == 1 ? 26 : band == 2 ? 22 : band <= 4 ? 16 : band <= 8 ? 10 : 0;
            if (shake > 0) CameraShake.blast(shake);
        }
    }

    // ================================================================ крылатая ракета (mfx)

    static final class Missile extends Timeline {
        /** Радиус огненного шара, блоки (≈ 450 кг ВВ). */
        static final float R = 8.5f;
        /** Вспышка: ближе — полная сила, дальше второго — её нет, блоки. */
        static final double FLASH_NEAR = 3 * R, FLASH_RANGE = 400;
        /** Сколько тиков тянется столб дыма. */
        static final int COLUMN_TICKS = 80;

        Missile(Vec3 pos, GroundMaterial mat, long seed) {
            super(pos, mat, seed);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                flash(level, FLASH_NEAR, FLASH_RANGE, 0.8f);
                Explosions.burst(level, pos, R, mat, random);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 4, 2, 4, 0, 80);
                return true;
            }
            if (t == 1) {
                spray(level, 2);
                Explosions.condensation(level, pos.add(0, 2, 0), 30, random);
            }
            Explosions.column(level, pos, R, t, COLUMN_TICKS, 320, random);
            for (Warheads.Secondary s : Warheads.MISSILE_SECONDARIES) {
                // вторичные подрывы сервера (ванильных клубов у них нет): огонь, дым и искры там же и тогда же
                if (s.tick() == t) Explosions.cookoff(level, pos.add(s.dx(), s.dy(), s.dz()), s.power(), random);
            }
            switch (t) {
                case 10 -> BlastSounds.fire(pos, 0.9f);
                case 150 -> BlastSounds.fire(pos, 0.85f);
                case 20 -> BlastSounds.debris(pos, 90, 0.9f);
                case 34 -> BlastSounds.debris(pos, 90, 0.8f);
                case 60, 97, 143 -> cookoff(level);
                default -> {}
            }
            return t < 340;
        }

        /** Догорание: вторичные подрывы в воронке. */
        private void cookoff(ClientLevel level) {
            Vec3 p = pos.add(random.nextIntBetweenInclusive(-6, 6), 0.5, random.nextIntBetweenInclusive(-6, 6));
            Explosions.cookoff(level, p, 3, random);
            Particles.burst(level, ParticleTypes.LAVA, p, 1, 0.5, 1, 0, 25);
            BlastSounds.cookoff(p);
        }

        @Override
        void arrive(ClientLevel level, int band) {
            BlastSounds.surface(pos, band, true);
            int shake = band == 1 ? 34 : band == 2 ? 30 : band == 3 ? 26 : band <= 6 ? 22 : band <= 10 ? 16 : band <= 16 ? 10 : 0;
            if (shake > 0) CameraShake.blast(shake);
        }
    }

    // ================================================================ бетонобойная бомба (bfx)

    static final class Bunker extends Timeline {
        /** Радиус огненного шара прорыва, блоки: 2,4 т ВВ (шар ∝ W^⅓ — ×1,75 к ракете), часть энергии уходит в грунт. */
        static final float R = 11f;

        private final Vec3 surface;
        private final int depth;
        /** Взрыв прорывается наружу ({@link BunkerCover#BREACH_DEPTH}). */
        private final boolean breach;

        Bunker(Vec3 pos, GroundMaterial mat, float surfaceY, long seed) {
            super(pos, mat, seed);
            this.surface = new Vec3(pos.x, surfaceY, pos.z);
            this.depth = BunkerCover.depth((int) surfaceY, pos.y);
            this.breach = BunkerCover.breaches((int) surfaceY, pos.y);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                if (breach) {
                    // газы и огонь вырываются над зарядом: шар, дым, вал пыли и вспышка — на поверхности, а не в толще грунта
                    flash(level, surface, 3 * R, 400, 0.8f);
                    Explosions.burst(level, surface, R, mat, random);
                } else {
                    // под землёй вспышку видно только в самой полости и рядом
                    flash(level, 8, 70, 0.8f);
                }
                return true;
            }
            if (t <= 6) {
                // полость: огонь бьёт во все стороны и упирается в стены, дым заполняет объём
                for (int i = 0; i < 10; i++) {
                    Fx.fire().vel(Explosions.dir(random, -1).scale(0.3 + random.nextDouble() * 0.4)).size(1.2f, 3).growFast()
                            .life(8 + random.nextInt(8)).drag(0.75f).collide().spawn(level, pos);
                }
                Fx.smoke().vel(Explosions.dir(random, -1).scale(0.3)).size(1.5f, 5).growFast().life(260 + random.nextInt(120))
                        .color(0x2A2622, 0x5E5852).alpha(0.9f).glow(1, 8).drag(0.85f).collide().rise(0.002f).spawn(level, pos);
                Particles.burst(level, ParticleTypes.LAVA, pos, 4, 2, 4, 0, 6);
                if (t == 1) Fx.flash().size(8, 10).life(4).spawn(level, pos);
            }
            if (t % 2 == 0 && t >= 2 && t <= 220) {
                // пыль и каменная крошка с потолка полости
                Fx.smoke().vel(random.nextGaussian() * 0.05, 0, random.nextGaussian() * 0.05).size(1, 4).life(160).color(Explosions.rgb(mat), 0x8A8480)
                        .alpha(0.5f).collide().budget(FxBudget.GROUND).spawn(level, pos.add(random.nextGaussian() * 5, random.nextGaussian() * 3, random.nextGaussian() * 5));
                Particles.burst(level, FxParticles.fallingDust(mat), pos.add(0, 7, 0), 7, 1, 7, 0, 14);
            }
            // поверхность: земля «подпрыгивает» расходящимся кольцом, потом курится провал
            if (t <= 16 && depth <= 60) heave(level, t * 2);
            if (t % 2 == 0 && t >= 24 && t <= 200 && depth <= 48) {
                // провал курится
                Fx.smoke().vel(random.nextGaussian() * 0.03, 0.06 + random.nextDouble() * 0.05, random.nextGaussian() * 0.03).size(0.8f, 4)
                        .life(200).color(Explosions.rgb(mat), 0x9A948E).alpha(0.45f).rise(0.002f).budget(FxBudget.GROUND)
                        .spawn(level, surface.add(random.nextGaussian() * 3, 0.5, random.nextGaussian() * 3));
            }
            if (t == 30) BlastSounds.debris(surface, 60, 0.75f);
            return t < 300;
        }

        private void heave(ClientLevel level, double r) {
            if (r == 0) Fx.ring().size(1, 36).life(16).alpha(0.4f).color(Explosions.rgb(mat), Explosions.rgb(mat)).spawn(level, surface.add(0, 0.3, 0));
            for (double a = 0; a < 360; a += 15) {
                Particles.burst(level, FxParticles.block(mat), FxParticles.ground(level, surface, a, r).add(0, 0.2, 0), 0.7, 0.1, 0.7, 0.3, 10);
            }
            for (double a = 7; a < 360; a += 30) {
                Fx.smoke().vel(0, 0.08 + random.nextDouble() * 0.1, 0).size(0.8f, 3.5f).growFast().life(120 + random.nextInt(60))
                        .color(Explosions.rgb(mat), Explosions.lighten(Explosions.rgb(mat), 0.3f)).alpha(0.6f).fadeFrom(0.3f)
                        .budget(FxBudget.GROUND).spawn(level, FxParticles.ground(level, surface, a, r).add(0, 0.6, 0));
            }
        }

        @Override
        void arrive(ClientLevel level, int band) {
            Player player = Minecraft.getInstance().player;
            boolean sky = player != null && level.canSeeSky(BlockPos.containing(player.getEyePosition()));
            BlastSounds.bunker(pos, band, !sky);
            if (!sky && band <= 4) CameraShake.blast(band == 1 ? 34 : band == 2 ? 30 : 26);
            else if (band <= 8) CameraShake.blast(16);
        }
    }

    // ================================================================ вход бомбы, выброс газов, провал

    /** Бомба вошла в грунт (bfx/impact): звуковой удар и тупой удар о землю приходят со скоростью звука. */
    public static void bunkerImpact(S2C.BunkerImpact p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        Vec3 pos = p.pos();
        Effects.add(new Timeline(pos, mat, 0) {
            @Override
            boolean run(ClientLevel level, int t) {
                if (t == 0) {
                    // удар корпуса без подрыва — огня нет: выброс пыли и комьев грунта из воронки входа
                    int dust = Explosions.rgb(mat);
                    for (int i = 0; i < 10; i++) {
                        Fx.smoke().vel(Explosions.dir(random, 0.3).scale(0.2 + 0.25 * random.nextDouble())).size(1, 3.5f).growFast()
                                .life(90 + random.nextInt(60)).color(dust, Explosions.lighten(dust, 0.3f)).alpha(0.7f).drag(0.88f)
                                .rise(0.003f).fadeFrom(0.3f).budget(FxBudget.GROUND).spawn(level, pos.add(0, 1, 0));
                    }
                    spray(level, 1);
                }
                return t < 30;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.bunkerImpact(pos, band);
            }
        });
    }

    /** Газы взрыва вырываются по скважине: рёв, огненные струи и выброс грунта вверх (bfx/vent_*). */
    public static void vent(S2C.Vent p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        Vec3 pos = p.pos();
        Effects.add(new Timeline(pos, mat, 0) {
            @Override
            boolean run(ClientLevel level, int t) {
                RandomSource rnd = level.random;
                if (t < 12) {
                    // огненные струи из скважины
                    for (int i = 0; i < 5; i++) {
                        Fx.fire().vel(rnd.nextGaussian() * 0.06, 0.8 + rnd.nextDouble() * 0.7, rnd.nextGaussian() * 0.06).size(0.5f, 1.6f)
                                .life(10 + rnd.nextInt(8)).drag(0.9f).spawn(level, pos.add(0, 0.5, 0));
                    }
                    Fx.spark().vel(rnd.nextGaussian() * 0.2, 1 + rnd.nextDouble(), rnd.nextGaussian() * 0.2).life(20 + rnd.nextInt(20)).gravity(0.04f)
                            .spawn(level, pos.add(0, 0.5, 0));
                    Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 0.5, 0), 0.3, 0.3, 0.3, 0, 4);
                }
                if (t < 148) {
                    // чёрный столб дыма и пыль грунта
                    float f = t / 148f;
                    Fx.smoke().vel(rnd.nextGaussian() * 0.04, (0.7 - 0.5 * f) * (0.8 + 0.4 * rnd.nextDouble()), rnd.nextGaussian() * 0.04)
                            .size(0.6f, 4.5f).life(260 + rnd.nextInt(120)).color(0x24201E, 0x686260).alpha(0.85f * (1 - 0.6f * f))
                            .glow(t < 12 ? 0.8f : 0, 6).drag(0.94f).rise(0.006f).fadeIn(2).fadeFrom(0.45f).spawn(level, pos.add(0, 0.8, 0));
                    if (t <= 88) {
                        Fx.smoke().vel(rnd.nextGaussian() * 0.08, 0.35 + rnd.nextDouble() * 0.3, rnd.nextGaussian() * 0.08).size(0.5f, 2.5f).life(80)
                                .color(Explosions.rgb(mat), 0xA09A94).alpha(0.6f).gravity(0.006f).fadeFrom(0.3f).budget(FxBudget.GROUND)
                                .spawn(level, pos.add(0, 1, 0));
                    }
                }
                return t < 168;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.vent(pos, band);
            }
        });
    }

    /** Свод над полостью обрушился: гул и пыль над провалом. */
    public static void collapse(S2C.Collapse p) {
        Vec3 pos = p.pos();
        Effects.add(new Timeline(pos, GroundMaterial.byId(p.material()), 0) {
            @Override
            boolean run(ClientLevel level, int t) {
                if (t < 6) {
                    RandomSource rnd = level.random;
                    for (int i = 0; i < 12; i++) {
                        double a = rnd.nextDouble() * Math.PI * 2;
                        Fx.smoke().vel(Math.cos(a) * 0.3, 0.1 + rnd.nextDouble() * 0.2, Math.sin(a) * 0.3).size(1, 4 + rnd.nextFloat() * 2).growFast()
                                .life(160 + rnd.nextInt(80)).color(Explosions.rgb(mat), 0xA8A29C).alpha(0.7f).drag(0.9f).collide().fadeFrom(0.35f)
                                .budget(FxBudget.GROUND).spawn(level, pos.add(rnd.nextGaussian() * 2, 0.5, rnd.nextGaussian() * 2));
                    }
                }
                return t < 20;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.collapse(pos, band);
            }
        });
    }
}
