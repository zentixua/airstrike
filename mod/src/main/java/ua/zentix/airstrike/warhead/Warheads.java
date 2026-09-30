package ua.zentix.airstrike.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.registry.ModTags;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.Timeline;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Terrain;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Боевые части. Разрушения и урон — на сервере по таймлайну (как fx/mfx/bfx в датапаке, только без криперов,
 * маркеров и блоков света): основной взрыв — ванильный {@link Explosion} (его уважают приваты, Sable сам толкает им
 * аппараты), вторичные подрывы, стёкла, обломки, ударная волна на скорости звука. Всё, что видно и слышно,
 * клиент строит сам по пакету {@link S2C.Blast}: частицы, вспышку, звук с задержкой по расстоянию, тряску.
 */
public final class Warheads {
    /** Фронт звука и ударной волны: 343 м/с = 17.15 блока за тик. */
    public static final double FRONT_SPEED = BlastModel.SOUND_SPEED / 20;
    /** Кому отправлять события взрыва: звук и дым видны и слышны далеко. */
    public static final double FX_RANGE = 640;

    private Warheads() {}

    // ---------------------------------------------------------------- вход

    public static void detonate(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity projectile, @Nullable UUID owner) {
        StrikeWorld.get(level).add(new SurfaceBlast(level, weapon, pos, projectile, owner));
    }

    /** Бетонобойная бомба вошла в грунт: небольшой кратер, кинетический удар, звуковой удар и тупой удар о землю. */
    public static void bunkerEntry(ServerLevel level, Vec3 point, @Nullable Entity bomb, @Nullable UUID owner) {
        Entity ownerEntity = owner == null ? null : level.getPlayerByUUID(owner);
        GroundMaterial mat = GroundMaterial.sample(level, BlockPos.containing(point.add(0, 1, 0)));
        explode(level, point.add(0, 1, 0), 4, false, bomb, ownerEntity, null);
        // кинетический удар: рядом с точкой попадания — смертельно
        hurtAround(level, point, 14, bomb, ownerEntity, ModDamageTypes.KINETIC, d -> d <= 4.5 ? 60 : d <= 9 ? 22 : 7);
        PacketDistributor.sendToPlayersNear(level, null, point.x, point.y, point.z, 320, new S2C.BunkerImpact(point, mat.ordinal()));
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(point) <= 60 * 60) PacketDistributor.sendToPlayer(p, new S2C.Quake(20, false));
        }
    }

    /** Подрыв бетонобойной бомбы под землёй. */
    public static void bunker(ServerLevel level, Vec3 pos, Vec3 entry, @Nullable Entity bomb, @Nullable UUID owner) {
        StrikeWorld.get(level).add(new BunkerBlast(level, pos, entry, bomb, owner));
    }

    // ---------------------------------------------------------------- общие средства

    /**
     * Докуда читает мир ванильный взрыв силы {@code power}: лучи по блокам гаснут не дальше 1.3·power / 0.225 шагов
     * по 0.3 блока (≈ 1.73·power), урон ищет сущности в 2·power и пускает к каждой луч видимости.
     */
    public static double reach(float power) {
        return power * 2 + 1;
    }

    /**
     * Сделать то, что читает мир в {@code reach} блоков от {@code centre} (ванильный взрыв), когда там всё готово:
     * сразу, если готово уже сейчас, иначе — когда тикет {@link BlastArea} догрузит район в фоне. Чтение неготового
     * чанка грузило бы его прямо в тике: вторичный подрыв залпа у края загруженного мира вставал на 1,2 с.
     */
    public static void whenReady(ServerLevel level, Vec3 centre, double reach, Consumer<ServerLevel> action) {
        if (Terrain.readyAround(level, centre, reach)) {
            action.accept(level);
        } else {
            StrikeWorld.get(level).add(new Deferred(BlastArea.hold(level, centre, reach), action));
        }
    }

    /**
     * Ванильный взрыв без его звука (звук с задержкой играет клиент): разрушения по правилам TNT,
     * урон с нашим типом («жертва авиаудара»), приваты и Sable работают как обычно. Только по готовым чанкам
     * ({@link #whenReady}).
     */
    static void explode(ServerLevel level, Vec3 at, float power, boolean fire, @Nullable Entity direct, @Nullable Entity owner,
                        @Nullable ExplosionDamageCalculator calculator) {
        boolean blocks = AirstrikeConfig.SERVER.blockDamage.get();
        boolean burns = fire && AirstrikeConfig.SERVER.fire.get();
        whenReady(level, at, reach(power), l -> l.explode(null, ModDamageTypes.source(l, ModDamageTypes.STRIKE, direct, owner), calculator,
                at.x, at.y, at.z, power, burns,
                blocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE,
                ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, ModSounds.SILENT));
    }

    /**
     * Сколько отложенный взрыв ({@link #whenReady}) ждёт своего района: тикет грузит район за секунды; если за 5 минут
     * не вышло (мир не грузится), взрыв отменяется.
     */
    public static final int DEFERRED_GIVE_UP_TICKS = 6000;

    /** Взрыв, который ждёт готовности своего района ({@link #whenReady}). */
    private static final class Deferred implements Timeline {
        private final BlastArea area;
        private final Consumer<ServerLevel> action;
        private int waited;

        Deferred(BlastArea area, Consumer<ServerLevel> action) {
            this.area = area;
            this.action = action;
        }

        @Override
        public boolean tick(ServerLevel level) {
            if (area.ready(level)) {
                action.accept(level);
                return false;
            }
            if (++waited < DEFERRED_GIVE_UP_TICKS) return true;
            Vec3 c = area.centre();
            Airstrike.LOG.warn("Взрыв у {} {} {} отменён: район не загрузился за {} тиков",
                    Mth.floor(c.x), Mth.floor(c.y), Mth.floor(c.z), DEFERRED_GIVE_UP_TICKS);
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }
    }

    static float power(WeaponType w) {
        return switch (w) {
            case DRONE -> AirstrikeConfig.SERVER.dronePower.get();
            case MISSILE -> AirstrikeConfig.SERVER.missilePower.get();
            case ROCKET -> AirstrikeConfig.SERVER.rocketPower.get();
            case LOITER -> AirstrikeConfig.SERVER.loiterPower.get();
            case BUNKER, NUKE -> AirstrikeConfig.SERVER.bunkerPower.get();
        };
    }

    interface DamageByDistance {
        float at(double distance);
    }

    static void hurtAround(ServerLevel level, Vec3 c, double radius, @Nullable Entity direct, @Nullable Entity owner,
                           ResourceKey<DamageType> type, DamageByDistance damage) {
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(radius))) {
            double d = e.position().distanceTo(c);
            if (d > radius || !e.isAlive()) continue;
            float amount = damage.at(d);
            if (amount > 0) e.hurt(ModDamageTypes.source(level, type, direct, owner), amount);
        }
    }

    /** Частицы, которые видно издалека (как «force» в команде particle). */
    static void forced(ServerLevel level, ParticleOptions options, Vec3 p, int count, double dx, double dy, double dz, double speed, double range) {
        for (ServerPlayer pl : level.players()) {
            if (pl.distanceToSqr(p) <= range * range) {
                level.sendParticles(pl, options, true, p.x, p.y, p.z, count, dx, dy, dz, speed);
            }
        }
    }

    /**
     * Ударная волна выбивает стёкла (и листву — только у ракеты). Сканируем лишь секции чанков, где такие блоки
     * вообще есть, поэтому даже куб 53×31×53 обходится дёшево. Незагруженные чанки пропускаются. У чанка с неготовым
     * соседом обновления соседей расходятся цепочкой дальше любого запаса в блоках (форма панели, потерявшей связь;
     * дверь рядом проверяет сигнал редстоуна и читает соседей проводящего блока; рельсы и провод — ещё дальше) и
     * грузили бы неготовый чанк синхронно. Поэтому там блок убирается без обновлений соседей (флаги 18: клиентам
     * и без форм соседей — у соседней панели остаётся связь), а в {@link #EDGE} блоках от неготового чанка не
     * убирается совсем: Sable читает соседние блоки каждого изменённого.
     *
     * @return сколько блоков выбито
     */
    public static int shatter(ServerLevel level, Vec3 center, int radius, int below, int above, TagKey<Block> tag) {
        BlockPos c = BlockPos.containing(center);
        BlockPos min = c.offset(-radius, -below, -radius), max = c.offset(radius, above, radius);
        int broken = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int sx = SectionPos.blockToSectionCoord(min.getX()); sx <= SectionPos.blockToSectionCoord(max.getX()); sx++) {
            for (int sz = SectionPos.blockToSectionCoord(min.getZ()); sz <= SectionPos.blockToSectionCoord(max.getZ()); sz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
                if (chunk == null) continue;
                // соседи чанка готовы — любой его блок меняется как обычно; нет — без обновлений соседей и не у края
                boolean edgesReady = Terrain.neighbourhoodReady(level, sx, sz);
                for (int sy = SectionPos.blockToSectionCoord(min.getY()); sy <= SectionPos.blockToSectionCoord(max.getY()); sy++) {
                    int idx = chunk.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSectionsCount()) continue;
                    LevelChunkSection section = chunk.getSection(idx);
                    if (section.hasOnlyAir() || !section.maybeHas(s -> s.is(tag))) continue;
                    int x0 = Math.max(min.getX(), SectionPos.sectionToBlockCoord(sx)), x1 = Math.min(max.getX(), SectionPos.sectionToBlockCoord(sx) + 15);
                    int y0 = Math.max(min.getY(), SectionPos.sectionToBlockCoord(sy)), y1 = Math.min(max.getY(), SectionPos.sectionToBlockCoord(sy) + 15);
                    int z0 = Math.max(min.getZ(), SectionPos.sectionToBlockCoord(sz)), z1 = Math.min(max.getZ(), SectionPos.sectionToBlockCoord(sz) + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int y = y0; y <= y1; y++) {
                            for (int z = z0; z <= z1; z++) {
                                // из уже взятой секции: setBlock меняет её же, так что следующие чтения верны
                                if (section.getBlockState(x & 15, y & 15, z & 15).is(tag)) {
                                    m.set(x, y, z);
                                    if (!edgesReady && !Terrain.readyAround(level, Vec3.atCenterOf(m), EDGE)) continue;
                                    level.setBlock(m, Blocks.AIR.defaultBlockState(), edgesReady ? Block.UPDATE_ALL : EDGE_FLAGS);
                                    broken++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return broken;
    }

    /**
     * Ударная волна дошла: отбрасывает от эпицентра (вместо зарядов ветра датапака), контузия у тех, кто рядом.
     * Сидящих в транспорте и на сиденьях не трогаем.
     *
     * @param band номер пояса фронта: 1 — первый тик пути фронта ({@link #FRONT_SPEED} блоков), 2 — второй …
     */
    static void push(ServerLevel level, Vec3 c, int band, double[] strength, Predicate<LivingEntity> filter,
                     BiConsumer<Player, Integer> effects) {
        if (band < 1 || band > strength.length) return;
        double r0 = (band - 1) * FRONT_SPEED, r1 = band * FRONT_SPEED;
        double k = strength[band - 1];
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r1))) {
            double d = e.position().distanceTo(c);
            if (d < r0 || d >= r1 || e.isPassenger() || e.isSpectator() || !filter.test(e)) continue;
            if (e instanceof Player p && p.getAbilities().flying) continue;
            Vec3 dir = new Vec3(e.getX() - c.x, 0, e.getZ() - c.z);
            dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 0) : dir.normalize();
            double resist = 1.0 - e.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE);
            e.push(dir.x * k * resist, 0.3 * k * resist, dir.z * k * resist);
            e.hurtMarked = true;
            if (e instanceof Player p) effects.accept(p, band);
        }
    }

    static void effect(Player p, Holder<MobEffect> effect, int seconds) {
        p.addEffect(new MobEffectInstance(effect, seconds * 20, 0, true, false, true));
    }

    /**
     * Сколько блоков от изменённого блока должно быть в готовых чанках, когда он меняется без обновлений соседей
     * ({@link #EDGE_FLAGS}): сам блок и его соседи, которые читает Sable, — с запасом в блок.
     */
    private static final int EDGE = 2;
    /** Изменение у края загрузки: клиентам, без форм соседей и без их обновлений. */
    private static final int EDGE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Поджечь землю: огонь на верхнем блоке столба (как упавшие огненные шары). Только в готовых чанках: кольцо огня
     * ракеты — в 7 блоках от точки удара, и у края загрузки оно доставало до чанка, который ещё генерируется, — чтение
     * блока из него ({@code igniteAt}) грузило бы его прямо в тике, а у чанка, который уже грузится, высота и блок
     * ждали бы загрузки в {@code managedBlock}.
     */
    public static void igniteGround(ServerLevel level, double x, double z) {
        if (!AirstrikeConfig.SERVER.fire.get() || !Terrain.readyAround(level, new Vec3(x, 0, z), EDGE)) return;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(x, 0, z));
        igniteAt(level, top);
    }

    /**
     * Огонь в блоке, если там воздух и огонь держится. Только когда готовы чанк блока и все его соседи: {@code setBlock}
     * обновляет соседей (и цепочкой — их соседей: дверь рядом проверяет сигнал редстоуна), а Sable читает соседние
     * блоки ({@code SableCommonEvents.handleBlockChange}) — у края загрузки это грузило бы чанк синхронно.
     */
    static void igniteAt(ServerLevel level, BlockPos pos) {
        if (!AirstrikeConfig.SERVER.fire.get() || !Terrain.neighbourhoodReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
        if (level.getBlockState(pos).isAir()) {
            BlockState fire = BaseFireBlock.getState(level, pos);
            if (fire.canSurvive(level, pos)) level.setBlock(pos, fire, 11);
        }
    }

    /**
     * Неровный «ком» (blob_rand датапака): три пересекающихся бруска — полуоси a по одной оси и b = 0.6·a по двум другим;
     * центр со случайным сдвигом ±(rx, ry, rz) и смещением oy по высоте.
     */
    static void blob(BlockPos c, int rx, int ry, int rz, int oy, int rmin, int rmax, RandomSource r, Consumer<BlockPos> out) {
        int bx = c.getX() + r.nextIntBetweenInclusive(-rx, rx);
        int by = c.getY() + r.nextIntBetweenInclusive(-ry, ry) + oy;
        int bz = c.getZ() + r.nextIntBetweenInclusive(-rz, rz);
        int a = r.nextIntBetweenInclusive(rmin, rmax);
        int b = Math.max(1, a * 6 / 10);
        Set<BlockPos> seen = new HashSet<>();
        box(bx - a, by - b, bz - b, bx + a, by + b, bz + b, seen, out);
        box(bx - b, by - b, bz - a, bx + b, by + b, bz + a, seen, out);
        box(bx - b, by - a, bz - b, bx + b, by + a, bz + b, seen, out);
    }

    private static void box(int x0, int y0, int z0, int x1, int y1, int z1, Set<BlockPos> seen, Consumer<BlockPos> out) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            BlockPos p = new BlockPos(x, y, z);
            if (seen.add(p)) out.accept(p);
        }
    }

    static void fill(ServerLevel level, Iterable<BlockPos> positions, BlockState with, TagKey<Block> replace) {
        for (BlockPos p : positions) {
            if (level.getBlockState(p).is(replace)) level.setBlock(p, with, 3);
        }
    }

    // ================================================================ шахед и ракета

    /** Таймлайн наземного взрыва (fx/tick и mfx/tick датапака). */
    static final class SurfaceBlast implements Timeline {
        private final WeaponType weapon;
        private final Vec3 pos;
        @Nullable
        private final Entity direct;
        @Nullable
        private final Entity owner;
        private final GroundMaterial mat;
        private final BlastArea area;
        private int t;

        SurfaceBlast(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity direct, @Nullable UUID ownerId) {
            this.weapon = weapon;
            this.pos = pos;
            // вторичные подрывы — до 11 блоков от точки удара, силой до 5
            this.area = BlastArea.hold(level, pos, Math.max(11 + reach(5), reach(power(weapon))));
            this.direct = direct;
            this.owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
            this.mat = GroundMaterial.sample(level, BlockPos.containing(pos));
            int kind = switch (weapon) {
                case MISSILE -> S2C.Blast.MISSILE;
                case ROCKET -> S2C.Blast.ROCKET;
                default -> S2C.Blast.DRONE;
            };
            // высоту поверхности клиент берёт только у бомбы (BunkerBlast, BlastEffects): здесь — точка удара, без чтения
            // высоты, которое у неготового чанка грузило бы его или ждало загрузки
            float surface = (float) pos.y;
            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, FX_RANGE,
                    new S2C.Blast(kind, pos, mat.ordinal(), surface, level.random.nextLong()));
            explode(level, pos, power(weapon), false, direct, owner, null);
        }

        @Override
        public boolean tick(ServerLevel level) {
            t++;
            boolean missile = weapon == WeaponType.MISSILE;
            if (t == 1) {
                // огненный шар — второй, зажигательный подрыв; у ракеты ещё кольцо горящих обломков
                explode(level, pos, missile ? 3 : 2, true, direct, owner, null);
                if (missile) {
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        igniteGround(level, pos.x + Math.cos(a) * 7, pos.z + Math.sin(a) * 7);
                    }
                    DebrisSpawner.missile(level, pos, mat);
                } else {
                    DebrisSpawner.drone(level, pos, mat);
                }
                if (AirstrikeConfig.SERVER.shatterGlass.get()) shatterGlass(level, missile);
            }
            if (missile) {
                switch (t) {
                    case 3 -> {
                        explode(level, pos.add(8, 0, -5), 5, false, direct, owner, null);
                        explode(level, pos.add(-6, 1, 7), 5, false, direct, owner, null);
                    }
                    case 5 -> {
                        explode(level, pos.add(-8, 0, -7), 4, false, direct, owner, null);
                        explode(level, pos.add(3, 1, 9), 4, false, direct, owner, null);
                    }
                    case 9 -> explode(level, pos.add(10, 0, 2), 3, false, direct, owner, null);
                    default -> {}
                }
                push(level, pos, t, new double[]{1.4, 1.0, 0.6}, e -> true, (p, band) -> {
                    if (band == 1) {
                        effect(p, MobEffects.CONFUSION, 10);
                        effect(p, MobEffects.DARKNESS, 3);
                    } else if (band == 2) {
                        effect(p, MobEffects.CONFUSION, 6);
                    } else {
                        effect(p, MobEffects.CONFUSION, 3);
                    }
                });
            } else {
                switch (t) {
                    case 3 -> {
                        explode(level, pos.add(4, 0, -3), 3, false, direct, owner, null);
                        explode(level, pos.add(-3, 1, 4), 3, false, direct, owner, null);
                    }
                    case 6 -> {
                        explode(level, pos.add(-4, 0, -4), 4, false, direct, owner, null);
                        explode(level, pos.add(2, 2, 5), 2, false, direct, owner, null);
                    }
                    default -> {}
                }
                push(level, pos, t, new double[]{1.1, 0.7}, e -> true, (p, band) -> effect(p, MobEffects.CONFUSION, band == 1 ? 7 : 4));
            }
            return t < 12;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        private void shatterGlass(ServerLevel level, boolean missile) {
            int glass = missile ? shatter(level, pos, 26, 8, 22, ModTags.SHATTERS) : shatter(level, pos, 16, 6, 12, ModTags.SHATTERS);
            if (glass > 0) {
                float vol = missile ? 6 : 4;
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, missile ? 0.7f : 0.8f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, missile ? 1.0f : 1.2f);
                if (missile) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, 1.3f);
                forced(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GLASS.defaultBlockState()), pos.add(0, missile ? 4 : 3, 0),
                        missile ? 500 : 250, missile ? 16 : 10, missile ? 6 : 5, missile ? 16 : 10, 0, 256);
            }
            if (missile && shatter(level, pos, 11, 3, 16, BlockTags.LEAVES) > 0) {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 4, 0.6f);
            }
        }
    }

    // ================================================================ бетонобойная бомба

    /** Подземный взрыв (bfx/* датапака): каверна, щебень, сейсмика, выброс газов, обрушение свода. */
    static final class BunkerBlast implements Timeline {
        private final Vec3 pos;
        private final Vec3 entry;
        @Nullable
        private final Entity direct;
        @Nullable
        private final Entity owner;
        private final GroundMaterial mat;
        private final GroundMaterial ventMat;
        private final int surfaceY;
        /** Глубина взрыва под поверхностью, блоков. */
        private final int depth;
        /** Ослабленная зона: взрыв выгрызает её как пустоту, уцелевшее потом становится щебнем. */
        private final Set<BlockPos> weakened = new HashSet<>();
        private final BlastArea area;
        private int t;

        BunkerBlast(ServerLevel level, Vec3 pos, Vec3 entry, @Nullable Entity direct, @Nullable UUID ownerId) {
            this.pos = pos;
            // подрывы — до 6 блоков от заряда силой до 12, обрушение свода — до 10 блоков вокруг устья
            this.area = BlastArea.hold(level, pos, Math.max(6 + reach(12), reach(power(WeaponType.BUNKER))));
            this.entry = entry;
            this.direct = direct;
            this.owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
            BlockPos c = BlockPos.containing(pos);
            this.mat = GroundMaterial.sample(level, c);
            this.ventMat = GroundMaterial.sample(level, BlockPos.containing(entry));
            this.surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ());
            this.depth = Mth.floor(surfaceY - pos.y);

            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, FX_RANGE,
                    new S2C.Blast(S2C.Blast.BUNKER, pos, mat.ordinal(), surfaceY, level.random.nextLong()));

            // каверна: порода вокруг заряда в неровных комьях «ослаблена» — взрыв выгрызает полость рваной формы
            for (int i = 0; i < 12; i++) {
                blob(c, 6, 5, 6, 0, 3, 5, level.random, p -> {
                    if (level.getBlockState(p).is(ModTags.DRILLABLE)) weakened.add(p.immutable());
                });
            }
            ExplosionDamageCalculator weak = new ExplosionDamageCalculator() {
                @Override
                public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter reader, BlockPos p,
                                                                             BlockState state, FluidState fluid) {
                    return weakened.contains(p) ? Optional.of(0f) : super.getBlockExplosionResistance(explosion, reader, p, state, fluid);
                }
            };
            for (int i = 0; i < 2; i++) {
                Vec3 o = pos.add(level.random.nextIntBetweenInclusive(-3, 3), level.random.nextIntBetweenInclusive(-2, 2), level.random.nextIntBetweenInclusive(-3, 3));
                explode(level, o, 12, false, direct, owner, weak);
            }
            explode(level, pos, power(WeaponType.BUNKER), false, direct, owner, weak);

            // ударная волна в породе достаёт и за камнем, без прямой видимости
            hurtAround(level, pos, 24, direct, owner, ModDamageTypes.SHOCKWAVE, d -> d <= 10 ? 200 : d <= 16 ? 40 : 12);
            for (ServerPlayer p : level.players()) {
                double d = p.position().distanceTo(pos);
                if (d > 24 && d <= 34) p.hurt(ModDamageTypes.source(level, ModDamageTypes.SHOCKWAVE, direct, owner), 4);
                // сейсмическая волна быстрее звука: трясёт сразу, звук придёт позже
                if (d <= 320) PacketDistributor.sendToPlayer(p, new S2C.Quake(d <= 40 ? 80 : d <= 100 ? 60 : 40, true));
            }
        }

        @Override
        public boolean tick(ServerLevel level) {
            t++;
            switch (t) {
                case 1 -> igniteCavity(level);
                case 2 -> rubble(level);
                case 3 -> {
                    explode(level, pos.add(4, -2, -3), 4, false, direct, owner, null);
                    explode(level, pos.add(-4, 1, 3), 4, false, direct, owner, null);
                    PacketDistributor.sendToPlayersNear(level, null, entry.x, entry.y, entry.z, 200, new S2C.Vent(entry, ventMat.ordinal()));
                    DebrisSpawner.vent(level, entry, ventMat);
                }
                case 22 -> {
                    if (AirstrikeConfig.SERVER.collapse.get() && depth >= 4 && depth <= 48) collapse(level);
                }
                default -> {}
            }
            // под землёй рядом — взрыв в замкнутом пространстве отбрасывает и оглушает
            push(level, pos, t, new double[]{1.2, 0.8}, e -> !level.canSeeSky(e.blockPosition().above()), (p, band) -> {
                if (band == 1) effect(p, MobEffects.DARKNESS, 4);
                effect(p, MobEffects.CONFUSION, 8);
            });
            return t < 24;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        /** Огонь на дне полости (огненные шары датапака). */
        private void igniteCavity(ServerLevel level) {
            BlockPos c = BlockPos.containing(pos);
            for (int i = 0; i < 6; i++) {
                BlockPos p = c.offset(level.random.nextIntBetweenInclusive(-5, 5), 3, level.random.nextIntBetweenInclusive(-5, 5));
                for (int k = 0; k < 10 && level.getBlockState(p.below()).isAir(); k++) p = p.below();
                igniteAt(level, p);
            }
        }

        /** Что уцелело от ослабленной зоны — дроблёная порода; щебень под сводом осыпается в полость. */
        private void rubble(ServerLevel level) {
            int cy = BlockPos.containing(pos).getY();
            for (BlockPos p : weakened) {
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.is(ModTags.DRILLABLE)) continue;
                level.setBlock(p, p.getY() > cy ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
            Set<BlockPos> magma = new HashSet<>();
            for (int i = 0; i < 3; i++) blob(BlockPos.containing(pos), 5, 1, 5, -7, 1, 2, level.random, magma::add);
            fill(level, magma, Blocks.MAGMA_BLOCK.defaultBlockState(), ModTags.BB_ROCK);
        }

        /** «Труба» обрушения из щебня от свода полости к поверхности и рваная воронка провала наверху. */
        private void collapse(ServerLevel level) {
            BlockPos s = BlockPos.containing(pos.x, surfaceY, pos.z);
            Set<BlockPos> chimney = new HashSet<>();
            int n = (depth - 8) / 3;
            for (int i = 1; i <= n; i++) blob(s, 1, 1, 1, -3 * i, 1, 2, level.random, chimney::add);
            fill(level, chimney, Blocks.GRAVEL.defaultBlockState(), ModTags.DRILLABLE);
            Set<BlockPos> crater = new HashSet<>();
            for (int i = 0; i < 10; i++) blob(s, 7, 1, 7, -2, 1, 3, level.random, crater::add);
            fill(level, crater, Blocks.GRAVEL.defaultBlockState(), ModTags.BB_SOIL);
            Set<BlockPos> coarse = new HashSet<>();
            for (int i = 0; i < 5; i++) blob(s, 8, 1, 8, -1, 1, 2, level.random, coarse::add);
            fill(level, coarse, Blocks.COARSE_DIRT.defaultBlockState(), ModTags.BB_SOIL);
            Set<BlockPos> rooted = new HashSet<>();
            for (int i = 0; i < 3; i++) blob(s, 6, 1, 6, -1, 1, 2, level.random, rooted::add);
            fill(level, rooted, Blocks.ROOTED_DIRT.defaultBlockState(), ModTags.BB_SOIL);

            level.playSound(null, s, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 8, 0.5f);
            level.playSound(null, s, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 8, 0.7f);
            Vec3 sv = Vec3.atBottomCenterOf(s);
            PacketDistributor.sendToPlayersNear(level, null, sv.x, sv.y, sv.z, 160, new S2C.Collapse(sv, mat.ordinal()));
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(sv) <= 50 * 50) PacketDistributor.sendToPlayer(p, new S2C.Quake(30, false));
            }
        }
    }
}
