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
import ua.zentix.airstrike.registry.ModParticles;
import ua.zentix.airstrike.registry.ModTags;
import ua.zentix.airstrike.strike.ImpactCost;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.Timeline;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.work.UnitQueue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

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
        long t0 = System.nanoTime();
        StrikeWorld world = StrikeWorld.get(level);
        world.add(new SurfaceBlast(level, weapon, pos, projectile, owner));
        world.impactCost().step(System.nanoTime() - t0);
    }

    /** Бетонобойная бомба вошла в грунт: небольшой кратер, кинетический удар, звуковой удар и тупой удар о землю. */
    public static void bunkerEntry(ServerLevel level, Vec3 point, @Nullable Entity bomb, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        Entity ownerEntity = owner == null ? null : level.getPlayerByUUID(owner);
        GroundMaterial mat = GroundMaterial.sample(level, BlockPos.containing(point.add(0, 1, 0)));
        explode(level, null, List.of(), point.add(0, 1, 0), 4, false, bomb, ownerEntity, null);
        // кинетический удар: рядом с точкой попадания — смертельно
        hurtAround(level, point, 14, bomb, ownerEntity, ModDamageTypes.KINETIC, d -> d <= 4.5 ? 60 : d <= 9 ? 22 : 7);
        PacketDistributor.sendToPlayersNear(level, null, point.x, point.y, point.z, 320, new S2C.BunkerImpact(point, mat.ordinal()));
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(point) <= 60 * 60) PacketDistributor.sendToPlayer(p, new S2C.Quake(20, false));
        }
        StrikeWorld.get(level).impactCost().step(System.nanoTime() - t0);
    }

    /** Подрыв бетонобойной бомбы под землёй. */
    public static void bunker(ServerLevel level, Vec3 pos, Vec3 entry, @Nullable Entity bomb, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        StrikeWorld world = StrikeWorld.get(level);
        world.add(new BunkerBlast(level, pos, entry, bomb, owner));
        world.impactCost().step(System.nanoTime() - t0);
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
     * Ванильный взрыв без его звука и частиц (звук с задержкой и картинку взрыва даёт клиент): разрушения по правилам TNT,
     * урон с нашим типом («жертва авиаудара»), приваты и Sable работают как обычно. Делается единицами в очереди
     * попаданий ({@link StagedExplosion}) под общим бюджетом тика, только по готовым чанкам.
     *
     * @param area  район таймлайна, который накрывает и этот взрыв, или null — взрыв возьмёт свой
     * @param after взрывы, которые должны кончиться раньше (главный взрыв удара)
     */
    static StagedExplosion explode(ServerLevel level, @Nullable BlastArea area, List<StagedExplosion> after, Vec3 at, float power,
                                   boolean fire, @Nullable Entity direct, @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        boolean blocks = AirstrikeConfig.SERVER.blockDamage.get();
        boolean burns = fire && AirstrikeConfig.SERVER.fire.get();
        BlastArea held = area != null ? area.retain() : BlastArea.hold(level, at, reach(power));
        StagedExplosion job = new StagedExplosion(held, after, at, power, burns, blocks, direct, owner, calculator);
        StrikeWorld.get(level).impacts().add(level, job);
        return job;
    }

    /** Кончились ли все взрывы {@code after}. */
    static boolean pending(List<StagedExplosion> after) {
        for (StagedExplosion e : after) if (!e.done()) return true;
        return false;
    }

    /**
     * Работа попадания одной единицей в очереди попаданий: после взрывов {@code after} (главного взрыва удара), когда
     * район {@code area} готов.
     *
     * @param work возвращает, сколько сделано (блоков, обломков) — для замера
     */
    static void unit(ServerLevel level, BlastArea area, List<StagedExplosion> after, ImpactCost.Kind kind, String what,
                     ToIntFunction<ServerLevel> work) {
        StrikeWorld.get(level).impacts().add(level, new Unit(area.retain(), after, kind, what, work));
    }

    private record Unit(BlastArea area, List<StagedExplosion> after, ImpactCost.Kind kind, String what,
                        ToIntFunction<ServerLevel> work) implements UnitQueue.Job {
        @Override
        public boolean ready(ServerLevel level) {
            return area.ready(level);
        }

        @Override
        public boolean blocked() {
            return pending(after);
        }

        @Override
        public int unitKind() {
            return kind.ordinal();
        }

        @Override
        public boolean step(ServerLevel level) {
            long t0 = System.nanoTime();
            int n = work.applyAsInt(level);
            area.record(level, kind, System.nanoTime() - t0, n);
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        @Override
        public String describe() {
            Vec3 c = area.centre();
            return what + " у " + Mth.floor(c.x) + " " + Mth.floor(c.y) + " " + Mth.floor(c.z);
        }
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

    /** Сила взрыва боевой части оружия (паспорт: настройка мира). */
    static float power(WeaponType w) {
        return w.spec().blastPower();
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
        Shatter job = new Shatter(null, List.of(), center, radius, below, above, tag, (l, n) -> {});
        int broken = 0;
        while (job.hasNext()) broken += job.nextSection(level);
        return broken;
    }

    /**
     * Выбить стёкла (или листву) единицами в очереди попаданий: секция 16³, где они есть, — одна единица.
     *
     * @param first звук и частицы — с первой секцией, где что-то выбито (сколько выбито в ней)
     */
    static void shatterUnits(ServerLevel level, BlastArea area, List<StagedExplosion> after, Vec3 center, int radius, int below, int above, TagKey<Block> tag,
                             BiConsumer<ServerLevel, Integer> first) {
        StrikeWorld.get(level).impacts().add(level, new Shatter(area.retain(), after, center, radius, below, above, tag, first));
    }

    /** {@link #shatter} по секциям: секции без таких блоков пропускаются в той же единице, работа — одна секция. */
    private static final class Shatter implements UnitQueue.Job {
        @Nullable
        private final BlastArea area;
        private final List<StagedExplosion> after;
        private final Vec3 center;
        private final TagKey<Block> tag;
        private final BiConsumer<ServerLevel, Integer> first;
        private final BlockPos min, max;
        private final int sx0, sy0, sz0, nx, ny, nz;
        private int index;
        private boolean broke;

        Shatter(@Nullable BlastArea area, List<StagedExplosion> after, Vec3 center, int radius, int below, int above, TagKey<Block> tag,
                BiConsumer<ServerLevel, Integer> first) {
            this.area = area;
            this.after = after;
            this.center = center;
            this.tag = tag;
            this.first = first;
            BlockPos c = BlockPos.containing(center);
            min = c.offset(-radius, -below, -radius);
            max = c.offset(radius, above, radius);
            sx0 = SectionPos.blockToSectionCoord(min.getX());
            sy0 = SectionPos.blockToSectionCoord(min.getY());
            sz0 = SectionPos.blockToSectionCoord(min.getZ());
            nx = SectionPos.blockToSectionCoord(max.getX()) - sx0 + 1;
            ny = SectionPos.blockToSectionCoord(max.getY()) - sy0 + 1;
            nz = SectionPos.blockToSectionCoord(max.getZ()) - sz0 + 1;
        }

        boolean hasNext() {
            return index < nx * ny * nz;
        }

        /** Следующая секция по порядку; сколько блоков в ней выбито (0 — нечего или чанк не готов). */
        int nextSection(ServerLevel level) {
            int i = index++;
            int sx = sx0 + i / (ny * nz), sz = sz0 + i / ny % nz, sy = sy0 + i % ny;
            LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
            if (chunk == null) return 0;
            int idx = chunk.getSectionIndexFromSectionY(sy);
            if (idx < 0 || idx >= chunk.getSectionsCount()) return 0;
            LevelChunkSection section = chunk.getSection(idx);
            if (section.hasOnlyAir() || !section.maybeHas(st -> st.is(tag))) return 0;
            // соседи чанка готовы — любой его блок меняется как обычно; нет — без обновлений соседей и не у края
            boolean edgesReady = Terrain.neighbourhoodReady(level, sx, sz);
            int x0 = Math.max(min.getX(), SectionPos.sectionToBlockCoord(sx)), x1 = Math.min(max.getX(), SectionPos.sectionToBlockCoord(sx) + 15);
            int y0 = Math.max(min.getY(), SectionPos.sectionToBlockCoord(sy)), y1 = Math.min(max.getY(), SectionPos.sectionToBlockCoord(sy) + 15);
            int z0 = Math.max(min.getZ(), SectionPos.sectionToBlockCoord(sz)), z1 = Math.min(max.getZ(), SectionPos.sectionToBlockCoord(sz) + 15);
            int broken = 0;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
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
            return broken;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return area == null || area.ready(level);
        }

        @Override
        public boolean blocked() {
            return pending(after);
        }

        @Override
        public int unitKind() {
            return ImpactCost.Kind.GLASS.ordinal();
        }

        @Override
        public boolean step(ServerLevel level) {
            long t0 = System.nanoTime();
            int broken = 0;
            // секции без таких блоков — в той же единице: работа — одна секция, где они есть
            while (hasNext() && broken == 0) broken += nextSection(level);
            if (broken > 0 && !broke) {
                broke = true;
                first.accept(level, broken);
            }
            long took = System.nanoTime() - t0;
            if (area != null) area.record(level, ImpactCost.Kind.GLASS, took, broken);
            else StrikeWorld.get(level).impactCost().add(ImpactCost.Kind.GLASS, took, broken);
            return hasNext();
        }

        @Override
        public void end(ServerLevel level) {
            if (area != null) area.release(level);
        }

        @Override
        public String describe() {
            return "Стёкла у " + Mth.floor(center.x) + " " + Mth.floor(center.y) + " " + Mth.floor(center.z);
        }
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

    /** Вторичный подрыв: тик после удара, сдвиг от точки удара, сила. */
    public record Secondary(int tick, double dx, double dy, double dz, float power) {}

    /**
     * Вторичные подрывы крылатой ракеты (топливо, обломки корпуса): по ним и разрушения на сервере, и картинка у клиента
     * ({@code BlastEffects.Missile}) — своих частиц у взрывов нет ({@link ModParticles#NONE}).
     */
    public static final List<Secondary> MISSILE_SECONDARIES = List.of(
            new Secondary(3, 8, 0, -5, 5), new Secondary(3, -6, 1, 7, 5),
            new Secondary(5, -8, 0, -7, 4), new Secondary(5, 3, 1, 9, 4),
            new Secondary(9, 10, 0, 2, 3));

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
        /** Главный взрыв: остальная работа удара ждёт, пока он снесёт своё. */
        private final List<StagedExplosion> main;
        private int t;

        SurfaceBlast(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity direct, @Nullable UUID ownerId) {
            this.weapon = weapon;
            this.pos = pos;
            // вторичные подрывы — до 11 блоков от точки удара, силой до 5
            this.area = BlastArea.hold(level, pos, Math.max(11 + reach(5), reach(power(weapon))));
            this.direct = direct;
            this.owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
            this.mat = GroundMaterial.sample(level, BlockPos.containing(pos));
            int kind = switch (weapon.spec().blast()) {
                case MISSILE -> S2C.Blast.MISSILE;
                case ROCKET -> S2C.Blast.ROCKET;
                case DRONE, NONE -> S2C.Blast.DRONE;
            };
            // высоту поверхности клиент берёт только у бомбы (BunkerBlast, BlastEffects): здесь — точка удара, без чтения
            // высоты, которое у неготового чанка грузило бы его или ждало загрузки
            float surface = (float) pos.y;
            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, FX_RANGE,
                    new S2C.Blast(kind, pos, mat.ordinal(), surface, level.random.nextLong()));
            main = List.of(explode(level, area, List.of(), pos, power(weapon), false, direct, owner, null));
        }

        @Override
        public boolean tick(ServerLevel level) {
            t++;
            boolean missile = weapon.spec().blast() == WeaponSpec.Blast.MISSILE;
            if (t == 1) {
                // огненный шар — второй, зажигательный подрыв; у ракеты ещё кольцо горящих обломков
                explode(level, area, main, pos, missile ? 3 : 2, true, direct, owner, null);
                if (missile) {
                    unit(level, area, main, ImpactCost.Kind.GROUND, "Кольцо огня", l -> {
                        for (int i = 0; i < 8; i++) {
                            double a = i * Math.PI / 4;
                            igniteGround(l, pos.x + Math.cos(a) * 7, pos.z + Math.sin(a) * 7);
                        }
                        return 8;
                    });
                }
                unit(level, area, main, ImpactCost.Kind.DEBRIS, "Обломки",
                        l -> missile ? DebrisSpawner.missile(l, pos, mat) : DebrisSpawner.drone(l, pos, mat));
                if (AirstrikeConfig.SERVER.shatterGlass.get()) shatterGlass(level, missile);
            }
            if (missile) {
                for (Secondary s : MISSILE_SECONDARIES) {
                    if (s.tick() == t) explode(level, area, main, pos.add(s.dx(), s.dy(), s.dz()), s.power(), false, direct, owner, null);
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
                        explode(level, area, main, pos.add(4, 0, -3), 3, false, direct, owner, null);
                        explode(level, area, main, pos.add(-3, 1, 4), 3, false, direct, owner, null);
                    }
                    case 6 -> {
                        explode(level, area, main, pos.add(-4, 0, -4), 4, false, direct, owner, null);
                        explode(level, area, main, pos.add(2, 2, 5), 2, false, direct, owner, null);
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

        /** Стёкла (и листва у ракеты) — единицами по секциям; звук и частицы — с первой секцией, где что-то выбито. */
        private void shatterGlass(ServerLevel level, boolean missile) {
            shatterUnits(level, area, main, pos, missile ? 26 : 16, missile ? 8 : 6, missile ? 22 : 12, ModTags.SHATTERS, (l, n) -> {
                float vol = missile ? 6 : 4;
                l.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, missile ? 0.7f : 0.8f);
                l.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, missile ? 1.0f : 1.2f);
                if (missile) l.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, vol, 1.3f);
                forced(l, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GLASS.defaultBlockState()), pos.add(0, missile ? 4 : 3, 0),
                        missile ? 500 : 250, missile ? 16 : 10, missile ? 6 : 5, missile ? 16 : 10, 0, 256);
            });
            if (missile) {
                shatterUnits(level, area, main, pos, 11, 3, 16, BlockTags.LEAVES,
                        (l, n) -> l.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 4, 0.6f));
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
        /** Верх колонки заряда (по нему — обрушение свода, как было). */
        private final int surfaceY;
        /** Глубина взрыва под поверхностью, блоков. */
        private final int depth;
        /** Ослабленная зона: взрыв выгрызает её как пустоту, уцелевшее потом становится щебнем. */
        private final Set<BlockPos> weakened = new HashSet<>();
        private final BlastArea area;
        /** Подрывы заряда: полость, огонь и щебень — после них. */
        private final List<StagedExplosion> main;
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
            // клиенту — верх над зарядом без скважины: по нему прорыв наружу, вспучивание грунта и курящийся провал
            int cover = surfaceAbove(level, c);

            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, FX_RANGE,
                    new S2C.Blast(S2C.Blast.BUNKER, pos, mat.ordinal(), cover, level.random.nextLong()));

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
            List<StagedExplosion> blasts = new ArrayList<>(3);
            for (int i = 0; i < 2; i++) {
                Vec3 o = pos.add(level.random.nextIntBetweenInclusive(-3, 3), level.random.nextIntBetweenInclusive(-2, 2), level.random.nextIntBetweenInclusive(-3, 3));
                blasts.add(explode(level, area, List.of(), o, 12, false, direct, owner, weak));
            }
            blasts.add(explode(level, area, List.of(), pos, power(WeaponType.BUNKER), false, direct, owner, weak));
            main = List.copyOf(blasts);

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
                // после подрывов, которые поставлены раньше: огонь на дне и щебень — в уже выгрызенной полости
                case 1 -> unit(level, area, main, ImpactCost.Kind.GROUND, "Огонь в полости", l -> {
                    igniteCavity(l);
                    return 6;
                });
                case 2 -> unit(level, area, main, ImpactCost.Kind.GROUND, "Щебень", this::rubble);
                case 3 -> {
                    explode(level, area, main, pos.add(4, -2, -3), 4, false, direct, owner, null);
                    explode(level, area, main, pos.add(-4, 1, 3), 4, false, direct, owner, null);
                    PacketDistributor.sendToPlayersNear(level, null, entry.x, entry.y, entry.z, 200, new S2C.Vent(entry, ventMat.ordinal()));
                    unit(level, area, main, ImpactCost.Kind.DEBRIS, "Выброс", l -> DebrisSpawner.vent(l, entry, ventMat));
                }
                case 22 -> {
                    if (AirstrikeConfig.SERVER.collapse.get() && depth >= 4 && depth <= 48) {
                        unit(level, area, main, ImpactCost.Kind.GROUND, "Обрушение свода", l -> {
                            collapse(l);
                            return 1;
                        });
                    }
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

        /**
         * Верх над зарядом без его же скважины ({@link BunkerCover}); листва не в счёт (кроны — не укрытие). Только сервер:
         * карта {@code …_NO_LEAVES} есть лишь на нём.
         */
        private static int surfaceAbove(ServerLevel level, BlockPos c) {
            int[] h = new int[BunkerCover.RING.length];
            for (int i = 0; i < h.length; i++) {
                h[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX() + BunkerCover.RING[i][0], c.getZ() + BunkerCover.RING[i][1]);
            }
            return BunkerCover.surface(h);
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

        /** Что уцелело от ослабленной зоны — дроблёная порода; щебень под сводом осыпается в полость. Сколько блоков смотрели. */
        private int rubble(ServerLevel level) {
            int cy = BlockPos.containing(pos).getY();
            for (BlockPos p : weakened) {
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.is(ModTags.DRILLABLE)) continue;
                level.setBlock(p, p.getY() > cy ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
            Set<BlockPos> magma = new HashSet<>();
            for (int i = 0; i < 3; i++) blob(BlockPos.containing(pos), 5, 1, 5, -7, 1, 2, level.random, magma::add);
            fill(level, magma, Blocks.MAGMA_BLOCK.defaultBlockState(), ModTags.BB_ROCK);
            return weakened.size() + magma.size();
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
