package ua.zentix.incoming.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Паника мобов (DESIGN-nuke §4): кто видел вспышку или кого накрыла волна, бежит прочь от эпицентра, а потом
 * успокаивается. Каждому {@link PathfinderMob} при входе в мир — своя цель {@link Flee} (самая важная, движение):
 * пока срок паники не вышел, она раз в 2–3 секунды ищет место в 16 блоках дальше от эпицентра (как ванильная паника
 * от удара — {@link DefaultRandomPos#getPosAway}, только рядом с мобом: чанки там готовы). Мобы с мозгом (жители,
 * пиглины, козы) ходят по памяти {@link MemoryModuleType#WALK_TARGET} — цель кладёт туда место, а идёт мозг; своё
 * занятие «паника» у жителя не включается (оно зовёт големов). Скорость у всех похожая — ~0,38 блока за тик,
 * как у коровы в панике; боссы не бегут.
 * <p>
 * Будят цель вспышка ({@link NuclearWarhead#expose}: кто видит шар) и волна ({@code BlastFront}: от 0,3 psi), обе —
 * в своих единицах работы под бюджетом тика. Старт и новые пути — вразнобой по номеру сущности.
 */
public final class MobPanic {
    /** Наибольшая цель: вперёд любой ходьбы. */
    static final int PRIORITY = -1;
    /** Паника после вспышки и после волны, тиков: от и сколько ещё вразнобой. */
    public static final int FLASH = 200, FLASH_SPREAD = 200, WAVE = 400, WAVE_SPREAD = 400;
    /** Скорость бега, блоков за тик. */
    static final double SPEED = 0.38;
    /** Как далеко искать место (по горизонтали и вертикали). */
    private static final int REACH = 16, Y_REACH = 7;

    private MobPanic() {}

    /** Цель бегства — всем, кто ходит по путям (сервер). */
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof PathfinderMob mob) || mob.getType().is(Tags.EntityTypes.BOSSES)) return;
        if (flee(mob) == null) mob.goalSelector.addGoal(PRIORITY, new Flee(mob));
    }

    /** Моб видел вспышку. */
    static void flash(Detonation d, LivingEntity e, long now) {
        if (e instanceof Mob mob) scare(mob, d.burst(), now, FLASH + spread(mob, FLASH_SPREAD));
    }

    /** Моба накрыла волна. */
    public static void wave(Detonation d, LivingEntity e, long now) {
        if (e instanceof Mob mob) scare(mob, d.burst(), now, WAVE + spread(mob, WAVE_SPREAD));
    }

    /** Паниковать от {@code from} ещё {@code ticks} тиков (продлевает, но не укорачивает). */
    static void scare(Mob mob, Vec3 from, long now, int ticks) {
        Flee f = flee(mob);
        if (f == null || !mob.isAlive()) return;
        if (f.until <= now) f.startAt = now + Math.floorMod(mob.getId() * 7, 20);
        f.from = from;
        f.until = Math.max(f.until, now + ticks);
    }

    /** Паникует ли моб сейчас (для проверок). */
    public static boolean panicking(Mob mob) {
        Flee f = flee(mob);
        return f != null && f.from != null && f.until > mob.level().getGameTime();
    }

    /** Бежит ли моб сейчас: цель бегства запущена (для проверок). */
    public static boolean fleeing(Mob mob) {
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) if (w.isRunning() && w.getGoal() instanceof Flee) return true;
        return false;
    }

    private static int spread(Mob mob, int spread) {
        return Math.floorMod(mob.getId() * 31 + 17, spread);
    }

    @Nullable
    private static Flee flee(Mob mob) {
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) if (w.getGoal() instanceof Flee f) return f;
        return null;
    }

    /** Бег прочь от эпицентра. */
    static final class Flee extends Goal {
        private final PathfinderMob mob;
        @Nullable
        Vec3 from;
        long until, startAt;
        private int repath;
        @Nullable
        private WalkTarget walking;

        Flee(PathfinderMob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        private long now() {
            return mob.level().getGameTime();
        }

        @Override
        public boolean canUse() {
            long now = now();
            return from != null && now >= startAt && now < until && mob.isAlive() && !mob.isPassenger() && !mob.isLeashed();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            repath = 0;
        }

        @Override
        public void tick() {
            // цель тикает через тик: счёт — по два
            repath -= 2;
            if (repath <= 0 || !brain() && mob.getNavigation().isDone()) path();
        }

        @Override
        public void stop() {
            if (brain()) {
                Brain<?> b = mob.getBrain();
                if (walking != null && b.getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == walking) b.eraseMemory(MemoryModuleType.WALK_TARGET);
            } else {
                mob.getNavigation().stop();
            }
            walking = null;
            from = null;
        }

        /** Ходит ли моб по мозгу (место — в память, идёт мозг). */
        private boolean brain() {
            return mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED);
        }

        private void path() {
            repath = 40 + Math.floorMod(mob.getId(), 20);
            Vec3 away = from;
            if (away == null) return;
            Vec3 to = DefaultRandomPos.getPosAway(mob, REACH, Y_REACH, away);
            if (to == null) to = LandRandomPos.getPosAway(mob, REACH, Y_REACH, away);
            if (to == null) return;
            double attr = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
            float speed = (float) Math.max(0.6, Math.min(2.0, attr > 0 ? SPEED / attr : 1));
            if (brain()) {
                walking = new WalkTarget(BlockPos.containing(to), speed, 1);
                mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walking);
            } else {
                mob.getNavigation().moveTo(to.x, to.y, to.z, speed);
            }
        }
    }
}
