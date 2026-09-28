package ua.zentix.airstrike.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.sound.ClientSounds;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.util.Particles;
import ua.zentix.airstrike.warhead.GroundMaterial;
import ua.zentix.airstrike.warhead.Warheads;

/**
 * Что видят и слышат игроки при взрывах — таймлайны fx/mfx/bfx датапака, перенесённые на клиент:
 * вспышка, огненный шар и «гриб», фонтан грунта цвета местности, кольцо пыли и сфера конденсации,
 * догорание; звук и тряска приходят вместе с фронтом ударной волны (17 блоков за тик).
 */
public final class BlastEffects {
    private BlastEffects() {}

    public static void blast(S2C.Blast p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        switch (p.kind()) {
            case S2C.Blast.MISSILE -> Effects.add(new Missile(p.pos(), mat, p.seed()));
            case S2C.Blast.BUNKER -> Effects.add(new Bunker(p.pos(), mat, p.surfaceY(), p.seed()));
            default -> Effects.add(new Drone(p.pos(), mat, p.seed()));
        }
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

        /** Вспышка на экране: свет мгновенный, сила — по расстоянию и прямой видимости. */
        void flash(ClientLevel level, double range, float decay) {
            Player player = Minecraft.getInstance().player;
            if (player == null) return;
            Vec3 eye = player.getEyePosition();
            double d = eye.distanceTo(pos);
            if (d > range) return;
            boolean visible = level.clip(new ClipContext(eye, pos.add(0, 1.5, 0), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS;
            float s = (float) (1 - d / range) * (visible ? 0.85f : 0.3f);
            Flash.trigger(s, decay, 0xFFF1C8);
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

        /** Звук, который слышат рядом (≤ radius блоков): обломки оседают и т.п. */
        void nearby(double radius, net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
            Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            if (ear.distanceTo(pos) <= radius) ClientSounds.at(event, pos, volume, pitch);
        }

        void nearbyOptional(double radius, String path, float volume, float pitch) {
            Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            if (ear.distanceTo(pos) <= radius) ClientSounds.atEarOptional("snassets", path, pos, volume, pitch);
        }
    }

    // ================================================================ шахед (fx)

    static final class Drone extends Timeline {
        Drone(Vec3 pos, GroundMaterial mat, long seed) {
            super(pos, mat, seed);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                flash(level, 240, 0.72f);
                Particles.burst(level, ParticleTypes.FLASH, pos.add(0, 1.5, 0), 0.2, 0.2, 0.2, 0, 10);
                Particles.burst(level, ParticleTypes.FLASH, pos.add(0, 5, 0), 5, 4, 5, 0, 60);
                Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 1, 0), 2, 1.5, 2, 0, 5);
                Particles.burst(level, ParticleTypes.EXPLOSION, pos.add(0, 2, 0), 5, 3, 5, 0, 60);
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 1.5, 0), 0.3, 0.3, 0.3, 0.7, 600);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 2, 1, 2, 0, 80);
                Particles.burst(level, ParticleTypes.END_ROD, pos.add(0, 1.5, 0), 0.1, 0.1, 0.1, 1.1, 160);
                Particles.burst(level, ParticleTypes.GUST_EMITTER_LARGE, pos.add(0, 1, 0), 0, 0, 0, 0, 1);
                Particles.burst(level, ParticleTypes.SONIC_BOOM, pos.add(0, 2, 0), 1.5, 1, 1.5, 0, 6);
                FxParticles.optional(level, "supplementaries:bomb_explosion_emitter", pos.add(0, 1, 0), 9, 0, 0, 1, 0);
                FxParticles.optional(level, "supplementaries:bomb_explosion_emitter", pos.add(0, 5, 0), 6, 0, 0, 1, 0);
                FxParticles.optional(level, "supplementaries:ember_spark", pos.add(0, 1.5, 0), 2, 1.5, 2, 0.6, 150);
                FxParticles.optional(level, "supplementaries:bomb_smoke", pos.add(0, 3, 0), 4, 3, 4, 0.08, 60);
                FxParticles.optional(level, "supplementaries:ash", pos.add(0, 8, 0), 10, 5, 10, 0, 200);
                return true;
            }
            if (t <= 20) ring(level, t * 3.2, 10, 1, 0.3, 0.1, 4, 30, 1.0);
            if (t == 1) {
                spray(level, 1);
                Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 2, 0), 4, 2, 4, 0, 4);
            }
            if (t == 3) Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 3, 0), 3, 2, 3, 0, 2);
            if (t == 6) Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 4, 0), 2, 2, 2, 0, 2);
            if (t >= 1 && t <= 14) fireballRise(level, t);
            if (t % 2 == 0 && t >= 2 && t <= 220) smoke(level, t);
            if (t == 8) ClientSounds.atEarOptional("snassets", "crashfire", pos, 0.9f, 1);
            if (t == 140) ClientSounds.atEarOptional("snassets", "crashfire", pos, 0.9f, 0.9f);
            if (t == 16) nearbyOptional(60, "debris/debrissettle_dirtsmall0", 0.9f, 1);
            if (t == 26) nearbyOptional(60, "debris/debrissettle_stonesmall1", 0.8f, 1);
            return t < 220;
        }

        private void fireballRise(ClientLevel level, int t) {
            if (t <= 4) {
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 3, 0), 2, 1.5, 2, 0.05, 80);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 4, 0), 2.5, 2, 2.5, 0.05, 30);
            } else if (t <= 9) {
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 7, 0), 2.5, 1.5, 2.5, 0.04, 60);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 8, 0), 3, 2, 3, 0.04, 40);
            } else {
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 11, 0), 3, 1.5, 3, 0.03, 30);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 12, 0), 3.5, 2, 3.5, 0.03, 50);
            }
        }

        private void smoke(ClientLevel level, int t) {
            Particles.burst(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, pos.add(0, 1, 0), 2, 0.5, 2, 0.01, 5);
            if (t >= 14 && t <= 80) Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 15, 0), 5, 2, 5, 0.02, 20);
            if (t <= 60) Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 0.8, 0), 3, 0.4, 3, 0.02, 12);
            if (t <= 40) Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 2.5, 0.4, 2.5, 0, 2);
            Particles.burst(level, ParticleTypes.SMOKE, pos.add(0, 1, 0), 3, 0.5, 3, 0.02, 10);
            if (t <= 90) Particles.burst(level, FxParticles.dust(mat, 4), pos.add(0, 1, 0), 9, 1, 9, 0.01, 25);
        }

        @Override
        void arrive(ClientLevel level, int band) {
            if (band <= 6) ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 1, 1);
            if (band >= 4) ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, band >= 10 ? 0.55f : 1, 1);
            if (band <= 3) {
                ClientSounds.atEar(ModSounds.BLAST_NEAR.get(), pos, 1, 1);
                ClientSounds.atEar(SoundEvents.GENERIC_EXPLODE.value(), pos, 1, 0.55f);
                ClientSounds.atEar(SoundEvents.LIGHTNING_BOLT_THUNDER, pos, 1, 0.65f);
                ClientSounds.atEarOptional("snassets", "explosions/explosion_heavy", pos, 1, 1);
                ClientSounds.atEarOptional("snassets", "debris/debris_vehicle0", pos, 0.8f, 1);
            } else if (band <= 9) {
                ClientSounds.atEar(SoundEvents.LIGHTNING_BOLT_THUNDER, pos, 0.8f, 0.55f);
                ClientSounds.atEarOptional("snassets", "explosions/explosion_distant_heavy", pos, 1, 1);
            } else {
                ClientSounds.atEarOptional("snassets", "explosions/explosion_distant_heavy2", pos, 0.55f, 1);
            }
            int shake = band == 1 ? 26 : band == 2 ? 22 : band <= 4 ? 16 : band <= 8 ? 10 : 0;
            if (shake > 0) CameraShake.blast(shake);
        }

        /** Кольцо пыли по земле (fx/ring): облака каждые step градусов и порывы ветра между ними. */
        void ring(ClientLevel level, double r, double step, double spread, double spreadY, double speed, int count, double gustStep, double y) {
            for (double a = 0; a < 360; a += step) {
                Particles.burst(level, ParticleTypes.CLOUD, FxParticles.ground(level, pos, a, r).add(0, y * 0.5, 0), spread, spreadY, spread, speed, count);
            }
            for (double a = 5; a < 360; a += gustStep) {
                Particles.burst(level, ParticleTypes.GUST, FxParticles.ground(level, pos, a, r).add(0, y, 0), 0, 0, 0, 0, 1);
            }
        }
    }

    // ================================================================ крылатая ракета (mfx)

    static final class Missile extends Timeline {
        Missile(Vec3 pos, GroundMaterial mat, long seed) {
            super(pos, mat, seed);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                flash(level, 400, 0.8f);
                Particles.burst(level, ParticleTypes.FLASH, pos.add(0, 2, 0), 0.3, 0.3, 0.3, 0, 20);
                Particles.burst(level, ParticleTypes.FLASH, pos.add(0, 6, 0), 7, 5, 7, 0, 120);
                Particles.burst(level, ParticleTypes.FLASH, pos.add(0, 14, 0), 10, 6, 10, 0, 60);
                Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 2, 0), 4, 3, 4, 0, 10);
                Particles.burst(level, ParticleTypes.EXPLOSION, pos.add(0, 3, 0), 8, 5, 8, 0, 120);
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 2, 0), 0.5, 0.5, 0.5, 1.1, 1200);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 4, 2, 4, 0, 200);
                Particles.burst(level, ParticleTypes.END_ROD, pos.add(0, 2, 0), 0.2, 0.2, 0.2, 1.6, 300);
                Particles.burst(level, ParticleTypes.GUST_EMITTER_LARGE, pos.add(0, 1, 0), 2, 1, 2, 0, 4);
                Particles.burst(level, ParticleTypes.SONIC_BOOM, pos.add(0, 3, 0), 3, 2, 3, 0, 12);
                Particles.burst(level, ParticleTypes.FLASH, pos.add(0, 14, 0), 0, 0, 0, 0, 1);
                Particles.burst(level, ParticleTypes.FLASH, pos.add(0, 6, 0), 3, 3, 3, 0, 4);
                FxParticles.optional(level, "supplementaries:bomb_explosion_emitter", pos.add(0, 1, 0), 14, 0, 0, 1, 0);
                FxParticles.optional(level, "supplementaries:bomb_explosion_emitter", pos.add(0, 6, 0), 10, 0, 0, 1, 0);
                FxParticles.optional(level, "supplementaries:bomb_explosion_emitter", pos.add(0, 12, 0), 7, 0, 0, 1, 0);
                FxParticles.optional(level, "supplementaries:ember_spark", pos.add(0, 2, 0), 3, 2, 3, 0.9, 300);
                FxParticles.optional(level, "supplementaries:bomb_smoke", pos.add(0, 4, 0), 6, 4, 6, 0.1, 150);
                FxParticles.optional(level, "supplementaries:ash", pos.add(0, 12, 0), 16, 8, 16, 0, 400);
                return true;
            }
            if (t <= 30) ring(level, t * 3.5);
            if (t <= 10) sphere(level, t * 4.5);
            switch (t) {
                case 1 -> {
                    spray(level, 2);
                    Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 3, 0), 7, 3, 7, 0, 8);
                }
                case 3 -> {
                    Particles.burst(level, ParticleTypes.FLASH, pos.add(4, 18, -3), 0, 0, 0, 0, 1);
                    Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 4, 0), 5, 3, 5, 0, 4);
                }
                case 5 -> Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 5, 0), 4, 3, 4, 0, 3);
                case 9 -> Particles.burst(level, ParticleTypes.FLASH, pos.add(-3, 20, 4), 0, 0, 0, 0, 1);
                case 10 -> ClientSounds.atEarOptional("snassets", "crashfire", pos, 1, 0.9f);
                case 150 -> ClientSounds.atEarOptional("snassets", "totaledfire", pos, 1, 0.9f);
                case 18 -> nearbyOptional(90, "debris/debrissettle_dirtsmall1", 1, 0.9f);
                case 26 -> nearbyOptional(90, "debris/debrissettle_woodlarge1", 0.9f, 0.9f);
                case 34 -> nearbyOptional(90, "debris/debrissettle_stonesmall0", 0.9f, 0.9f);
                case 60, 97, 143 -> cookoff(level);
                default -> {}
            }
            if (t >= 1 && t <= 140) mushroom(level, t);
            if (t % 2 == 0 && t >= 2 && t <= 320) smoke(level, t);
            return t < 320;
        }

        /** Огненный шар поднимается и превращается в грибовидную шапку (mfx/fireball_rise). */
        private void mushroom(ClientLevel level, int t) {
            if (t <= 4) {
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 4, 0), 3, 2, 3, 0.06, 150);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 5, 0), 3.5, 2.5, 3.5, 0.05, 60);
            } else if (t <= 9) {
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 9, 0), 3.5, 2, 3.5, 0.05, 120);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 10, 0), 4, 2.5, 4, 0.04, 70);
            } else if (t <= 14) {
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 14, 0), 4, 2, 4, 0.04, 90);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 15, 0), 5, 2.5, 5, 0.03, 80);
            } else if (t <= 22) {
                Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 19, 0), 5, 2, 5, 0.03, 60);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 20, 0), 6, 2.5, 6, 0.03, 90);
            }
            if (t >= 18) {
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 26, 0), 9, 2, 9, 0.02, 25);
                Particles.burst(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, pos.add(0, 24, 0), 8, 1.5, 8, 0.01, 4);
            }
            if (t >= 18 && t <= 45) Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 22, 0), 5, 1, 5, 0.01, 12);
        }

        private void smoke(ClientLevel level, int t) {
            Particles.burst(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, pos.add(0, 1, 0), 3.5, 0.6, 3.5, 0.01, 8);
            Particles.burst(level, ParticleTypes.SMOKE, pos.add(0, 1, 0), 5, 0.6, 5, 0.02, 16);
            if (t <= 100) Particles.burst(level, ParticleTypes.FLAME, pos.add(0, 0.8, 0), 5, 0.5, 5, 0.02, 20);
            if (t <= 60) Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 4, 0.5, 4, 0, 4);
            if (t <= 200) Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 10, 0), 3, 6, 3, 0.03, 10);
            if (t <= 90) Particles.burst(level, FxParticles.dust(mat, 4), pos.add(0, 1, 0), 9, 1, 9, 0.01, 25);
        }

        /** Догорание: вторичные подрывы в воронке. */
        private void cookoff(ClientLevel level) {
            Vec3 p = pos.add(random.nextIntBetweenInclusive(-9, 9), 1, random.nextIntBetweenInclusive(-9, 9));
            Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, p, 0, 0, 0, 0, 1);
            Particles.burst(level, ParticleTypes.LAVA, p, 1, 0.5, 1, 0, 25);
            Particles.burst(level, ParticleTypes.FLAME, p, 0.5, 0.5, 0.5, 0.3, 80);
            ClientSounds.at(SoundEvents.GENERIC_EXPLODE.value(), p, 4, 0.8f);
            nearbyOptional(200, "explosions/bomblet_distant0", 0.6f, 1);
        }

        /** Пылевая стена по земле до 105 блоков (mfx/ring). */
        private void ring(ClientLevel level, double r) {
            for (double a = 0; a < 360; a += 7.5) {
                Particles.burst(level, ParticleTypes.CLOUD, FxParticles.ground(level, pos, a, r).add(0, 0.6, 0), 2, 0.5, 2, 0.12, 6);
            }
            for (double a = 3; a < 360; a += 24) {
                Particles.burst(level, ParticleTypes.GUST, FxParticles.ground(level, pos, a, r).add(0, 1.2, 0), 0, 0, 0, 0, 1);
            }
        }

        /** Сфера ударной волны — облако конденсации до 45 блоков (mfx/sphere). */
        private void sphere(ClientLevel level, double r) {
            int[][] rings = {{-12, 30}, {-32, 24}, {-52, 16}, {-72, 8}};
            for (int[] ring : rings) {
                double step = 360.0 / ring[1];
                for (double a = 0; a < 360; a += step) {
                    Particles.burst(level, ParticleTypes.WHITE_SMOKE, FxParticles.sphere(pos, a, ring[0], r), 0.8, 0.8, 0.8, 0.02, 3);
                }
            }
            Particles.burst(level, ParticleTypes.WHITE_SMOKE, FxParticles.sphere(pos, 0, -90, r), 1, 1, 1, 0.02, 6);
        }

        @Override
        void arrive(ClientLevel level, int band) {
            if (band <= 8) ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 1, 0.85f);
            if (band >= 4) ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, band >= 11 ? 0.8f : 1, 0.85f);
            if (band <= 3) {
                ClientSounds.atEar(ModSounds.BLAST_NEAR.get(), pos, 1, 0.85f);
                ClientSounds.atEar(SoundEvents.GENERIC_EXPLODE.value(), pos, 1, 0.5f);
                ClientSounds.atEar(SoundEvents.LIGHTNING_BOLT_THUNDER, pos, 1, 0.5f);
                ClientSounds.atEarOptional("snassets", "explosions/explosion_heavy", pos, 1, 0.9f);
                ClientSounds.atEarOptional("snassets", "explosions/explosion_heavy1", pos, 1, 0.8f);
                ClientSounds.atEarOptional("snassets", "explosions/rocketboom0", pos, 1, 0.7f);
                ClientSounds.atEarOptional("snassets", "debris/debris_vehicle1", pos, 1, 0.9f);
            } else if (band <= 10) {
                ClientSounds.atEar(SoundEvents.LIGHTNING_BOLT_THUNDER, pos, 0.9f, 0.45f);
                ClientSounds.atEarOptional("snassets", "explosions/explosion_distant_heavy", pos, 1, 0.9f);
                ClientSounds.atEarOptional("snassets", "explosions/explosion_distant_heavy1", pos, 0.8f, 0.85f);
            } else {
                ClientSounds.atEarOptional("snassets", "explosions/explosion_distant_heavy2", pos, 0.8f, 0.85f);
                ClientSounds.atEarOptional("snassets", "explosions/plane_totaled_distant_large", pos, 0.5f, 0.7f);
            }
            int shake = band == 1 ? 34 : band == 2 ? 30 : band == 3 ? 26 : band <= 6 ? 22 : band <= 10 ? 16 : band <= 16 ? 10 : 0;
            if (shake > 0) CameraShake.blast(shake);
        }
    }

    // ================================================================ бетонобойная бомба (bfx)

    static final class Bunker extends Timeline {
        private final Vec3 surface;
        private final int depth;

        Bunker(Vec3 pos, GroundMaterial mat, float surfaceY, long seed) {
            super(pos, mat, seed);
            this.surface = new Vec3(pos.x, surfaceY, pos.z);
            this.depth = (int) Math.floor(surfaceY - pos.y);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                // под землёй вспышку видно только в самой полости и рядом
                flash(level, 70, 0.8f);
                return true;
            }
            if (t <= 8) {
                Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos, 3, 2, 3, 0, 3);
                Particles.burst(level, ParticleTypes.FLAME, pos, 1, 1, 1, 0.8, 300);
                Particles.burst(level, ParticleTypes.LAVA, pos, 4, 2, 4, 0, 40);
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos, 5, 3, 5, 0.1, 60);
                if (t == 1) {
                    FxParticles.optional(level, "supplementaries:bomb_explosion_emitter", pos, 8, 0, 0, 1, 0);
                    Particles.burst(level, ParticleTypes.FLASH, pos, 2, 2, 2, 0, 30);
                }
            }
            if (t % 2 == 0 && t >= 2 && t <= 220) {
                // дым, пыль и каменная крошка с потолка полости
                Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos, 6, 4, 6, 0.02, 16);
                Particles.burst(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.add(0, -6, 0), 5, 1, 5, 0.01, 5);
                Particles.burst(level, FxParticles.dust(mat, 3), pos, 7, 4, 7, 0.01, 20);
                Particles.burst(level, FxParticles.fallingDust(mat), pos.add(0, 7, 0), 7, 1, 7, 0, 14);
            }
            // поверхность: земля «подпрыгивает» расходящимся кольцом, потом курится провал
            if (t <= 16 && depth <= 60) heave(level, t * 2);
            if (t % 2 == 0 && t >= 24 && t <= 200 && depth <= 48) {
                Particles.burst(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, surface.add(0, 0.5, 0), 3, 0.4, 3, 0.01, 3);
                Particles.burst(level, FxParticles.dust(mat, 3), surface.add(0, 0.8, 0), 4, 0.5, 4, 0.01, 6);
            }
            if (t == 12) nearbyOptional(60, "debris/debrissettle_stonesmall0", 1, 0.8f);
            if (t == 30) nearbyOptional(60, "debris/debrissettle_stonesmall2", 1, 0.7f);
            return t < 300;
        }

        private void heave(ClientLevel level, double r) {
            for (double a = 0; a < 360; a += 15) {
                Particles.burst(level, FxParticles.block(mat), FxParticles.ground(level, surface, a, r).add(0, 0.2, 0), 0.7, 0.1, 0.7, 0.3, 10);
            }
            for (double a = 7; a < 360; a += 30) {
                Particles.burst(level, FxParticles.dust(mat, 3), FxParticles.ground(level, surface, a, r).add(0, 0.6, 0), 1, 0.3, 1, 0.02, 6);
            }
        }

        @Override
        void arrive(ClientLevel level, int band) {
            Player player = Minecraft.getInstance().player;
            boolean sky = player != null && level.canSeeSky(BlockPos.containing(player.getEyePosition()));
            if (!sky && band <= 4) {
                // под землёй рядом — взрыв в замкнутом пространстве: жёстко и с долгим эхом
                ClientSounds.atEar(ModSounds.BOMB_CAVE.get(), pos, 1, 1);
                ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 1, 0.8f);
                ClientSounds.atEar(SoundEvents.GENERIC_EXPLODE.value(), pos, 1, 0.5f);
                ClientSounds.atEarOptional("snassets", "debris/debris_vehicle0", pos, 1, 0.7f);
                CameraShake.blast(band == 1 ? 34 : band == 2 ? 30 : 26);
            } else {
                // на поверхности — глухой удар из-под земли
                ClientSounds.atEar(ModSounds.BOMB_DEEP.get(), pos, 1, 1);
                if (band <= 6) ClientSounds.atEar(SoundEvents.LIGHTNING_BOLT_THUNDER, pos, 0.6f, 0.4f);
                if (band <= 8) CameraShake.blast(16);
            }
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
                    Particles.burst(level, ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 1, 0), 0, 0, 0, 0, 1);
                    Particles.burst(level, ParticleTypes.EXPLOSION, pos.add(0, 1, 0), 1, 1, 1, 0, 12);
                    spray(level, 1);
                }
                return t < 30;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                if (band <= 18) {
                    float v = Math.max(0.3f, 1 - band / 18f);
                    ClientSounds.atEar(ModSounds.BOMB_CRACK.get(), pos, v, 1);
                    ClientSounds.atEar(ModSounds.BOMB_IMPACT.get(), pos, v, 1);
                }
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
                if (t < 10) {
                    for (double[] o : new double[][]{{0, 1.3}, {0.3, 1.1}, {-0.3, 1.2}, {0, 1.0}, {0.2, 1.4}}) {
                        Particles.burst(level, ParticleTypes.FLAME, pos.add(o[0], 0.5, o[0] * 0.7), 0, 1, 0, o[1], 0);
                    }
                    for (double[] o : new double[][]{{0, 1.0}, {0.3, 0.8}, {-0.2, 0.9}}) {
                        Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(o[0], 0.8, -o[0] * 0.7), 0, 1, 0, o[1], 0);
                    }
                    Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 0.5, 0), 0.3, 0.3, 0.3, 0, 6);
                    Particles.burst(level, ParticleTypes.EXPLOSION, pos.add(0, 1.5, 0), 0.3, 1, 0.3, 0, 1);
                }
                if (t < 148) {
                    Particles.burst(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, pos.add(0, 0.5, 0), 0.3, 0.3, 0.3, 0.02, 4);
                    if (t <= 78) Particles.burst(level, ParticleTypes.LARGE_SMOKE, pos.add(0, 2, 0), 0.4, 1, 0.4, 0.08, 6);
                    if (t <= 88) {
                        Particles.burst(level, FxParticles.dust(mat, 3.5f), pos.add(0, 1, 0), 0, 1, 0, 0.4, 0);
                        Particles.burst(level, FxParticles.dust(mat, 3.5f), pos.add(0.3, 2, -0.2), 0, 1, 0, 0.3, 0);
                    }
                }
                return t < 168;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                if (band <= 10) ClientSounds.atEar(ModSounds.BOMB_VENT.get(), pos, Math.max(0.3f, 1 - band / 10f), 1);
            }
        });
    }

    /** Свод над полостью обрушился: гул и пыль над провалом. */
    public static void collapse(S2C.Collapse p) {
        Vec3 pos = p.pos();
        Effects.add(new Timeline(pos, GroundMaterial.byId(p.material()), 0) {
            @Override
            boolean run(ClientLevel level, int t) {
                if (t == 0) Particles.burst(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.add(0, 1, 0), 4, 1, 4, 0.02, 60);
                return t < 20;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                if (band <= 7) ClientSounds.atEar(ModSounds.BOMB_QUAKE.get(), pos, Math.max(0.4f, 1 - band / 8f), 0.8f);
            }
        });
    }
}
