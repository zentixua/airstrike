package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Всё ядерное, что знает клиент: подрывы этого измерения (из них по модели выводятся вспышка, шар, гриб,
 * волна, звук, дождь) и летящие МБР. Сервер присылает по одному пакету на событие — дальше клиент сам.
 */
public final class ClientNuclear {
    /**
     * Подрыв на клиенте. «Вживую» (не при входе в мир и не из перемотки повтора Flashback) — со вспышкой, звуком
     * и тряской. Время картинки считают свои тики клиента от прихода пакета, а не игровые часы: те прыгают назад, когда
     * сервер догоняет отставание, а шар и двойная вспышка длятся доли секунды — их нельзя ни проскочить, ни повторить.
     */
    public static final class Active {
        public final Detonation d;
        public final boolean live;
        public final CloudPuffs puffs;
        /**
         * Пелена после волны ({@link NukePall}): «до утра» — от времени суток в миг подрыва и по часам мира после него
         * ({@link PallField#daylight}).
         */
        public final PallField pall;
        /** Сколько тиков прошло до прихода пакета (у пришедших при входе в мир — с самого подрыва). */
        private final long before;
        private long age;
        final NukeSounds.Schedule sounds;

        Active(Detonation d, boolean live, long clientNow) {
            this(d, live, clientNow, new CloudPuffs(d), null);
        }

        /** Тот же подрыв после перемотки повтора: прошлое, без вспышки и звука, возраст — по часам этого места записи. */
        Active(Active a, long clientNow) {
            this(a.d, false, clientNow, a.puffs, a.pall);
            puffs.untrack();
            pall.rewound();
        }

        private Active(Detonation d, boolean live, long clientNow, CloudPuffs puffs, @Nullable PallField pall) {
            this.d = d;
            this.live = live;
            this.before = live ? 0 : Math.max(0, clientNow - d.gameTime());
            this.puffs = puffs;
            ClientLevel level = Minecraft.getInstance().level;
            // пришедший позже — время суток подрыва по нынешним часам: прыжка часов до входа он не видел (nuclear.md)
            this.pall = pall != null ? pall : new PallField(d, level == null ? 0 : level.getDayTime() - before);
            this.sounds = new NukeSounds.Schedule(this);
        }

        /** Тики после подрыва (с долей кадра). */
        public double ticks(float partialTick) {
            return before + age + partialTick;
        }

        /** Секунды модели после подрыва (с учётом масштаба мира). */
        public double seconds(float partialTick) {
            return ticks(partialTick) / (20.0 * d.scale());
        }
    }

    /**
     * На сколько тиков часы клиента могут уходить вперёд сервера. Клиент сам прибавляет тик к игровому времени,
     * а сервер присылает своё раз в секунду: пока сервер отстаёт (генерация, руины, сохранение), часы клиента
     * убегают вперёд и потом прыгают назад. Отсчёт до подрыва по часам клиента поэтому доходит до нуля раньше,
     * чем сервер доходит до срока, — это не задержка подрыва. Запас — самый большой прыжок назад за последние
     * десятки секунд (медленно тает), плюс {@link #DELAY_GRACE_TICKS}.
     */
    static final class ClockLead {
        /** Тает на столько тиков за тик: прыжок в секунду забывается за 20 с. */
        static final double DECAY = 0.05;
        private long last = Long.MIN_VALUE;
        private double lead;

        void tick(long now) {
            if (last != Long.MIN_VALUE && now < last + 1 && last + 1 - now < 20 * 60) {
                lead = Math.max(lead, last + 1 - now);
            } else {
                lead = Math.max(0, lead - DECAY);
            }
            last = now;
        }

        double lead() {
            return lead;
        }

        void reset() {
            last = Long.MIN_VALUE;
            lead = 0;
        }
    }

    /**
     * Сколько тиков после срока по часам клиента ждать пакета подрыва, прежде чем говорить «подрыв задерживается»:
     * сервер шлёт время раз в 20 тиков, пакет подрыва идёт с задержкой сети.
     */
    static final int DELAY_GRACE_TICKS = 50;

    /** Раз в сколько тиков сервер присылает игровое время (в повторе — так же, из записи). */
    static final int TIME_SYNC_TICKS = 20;

    private static final ClockLead CLOCK = new ClockLead();
    private static final Map<Integer, Active> DETONATIONS = new LinkedHashMap<>();
    private static final Map<Integer, S2C.NukeWarning> WARNINGS = new LinkedHashMap<>();
    @Nullable
    private static S2C.Radiation radiation;

    private ClientNuclear() {}

    // ---------------------------------------------------------------- пакеты

    /** Пуск МБР; {@code live} — не из перемотки повтора ({@link ua.zentix.incoming.client.replay.Replay#event}): рёв пуска только вживую. */
    public static void warning(S2C.NukeWarning w, boolean live) {
        boolean fresh = !WARNINGS.containsKey(w.strikeId());
        WARNINGS.put(w.strikeId(), w);
        if (fresh && live) NukeSounds.warning(w);
    }

    /**
     * Подрыв; {@code live} — не из перемотки повтора: вспышка, звук и тряска только вживую, подрыв из перемотки виден
     * в своём возрасте. Живой пакет подрыва, который уже показан как прошлый (повтор перемотали на миг до него), —
     * показывается заново вживую.
     */
    public static void detonation(S2C.NukeDetonation p, boolean live) {
        Detonation d = p.detonation();
        // подрыв бывает и позже отсчёта: сервер ждёт, пока догрузится место (NuclearStrikes.GIVE_UP_TICKS). Подрыв
        // номера удара не несёт — снимается одно предупреждение: из подходящих по месту и сроку — с самым ранним
        // отсчётом (сервер подрывает удары по сроку); другой удар в ту же точку остаётся на экране
        WARNINGS.values().stream()
                .filter(w -> w.target().distanceToSqr(new Vec3(d.burst().x, w.target().y, d.burst().z)) < 4 && d.gameTime() >= w.detonateTime() - 40)
                .min(Comparator.comparingLong(S2C.NukeWarning::detonateTime).thenComparingInt(S2C.NukeWarning::strikeId))
                .ifPresent(w -> WARNINGS.remove(w.strikeId()));
        ClientLevel level = Minecraft.getInstance().level;
        Active known = DETONATIONS.get(d.id());
        if ((known != null && (known.live || !live)) || level == null) return;
        // прошлый показ того же подрыва (из перемотки) — его петли горящего города прочь
        if (known != null) known.sounds.stop();
        Active a = new Active(d, live, level.getGameTime());
        DETONATIONS.put(d.id(), a);
        if (live) NukeFlash.detonation(a);
    }

    /** Вход в мир или смена измерения: всё, что уже есть, без вспышки и удара (они уже прошли). */
    public static void sync(S2C.NukeSync p) {
        stopAll();
        DETONATIONS.clear();
        WARNINGS.clear();
        ClientLevel level = Minecraft.getInstance().level;
        long now = level == null ? 0 : level.getGameTime();
        for (Detonation d : p.detonations()) DETONATIONS.put(d.id(), new Active(d, false, now));
        for (S2C.NukeWarning w : p.warnings()) WARNINGS.put(w.strikeId(), w);
        NukeDust.reset();
        NukePall.reset();
    }

    /**
     * Повтор Flashback перемотан: всё, что было до этого места записи, — прошлое, без вспышки, звука и оглушения,
     * в своём возрасте по часам мира; подрывы и пуски позже него убираются (дойдёт повтор — придут снова). Часы клиента
     * в повторе — по последнему пакету времени записи, а он раз в секунду: подрыв в пределах секунды после часов
     * остаётся (ему возраст 0).
     */
    public static void rewound() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        // прежние подрывы уходят (позже места записи) или становятся прошлым — их эхо и петли больше не ведут
        stopAll();
        DETONATIONS.values().removeIf(a -> a.d.gameTime() > now + TIME_SYNC_TICKS);
        DETONATIONS.replaceAll((id, a) -> new Active(a, now));
        WARNINGS.values().removeIf(w -> w.launchTime() > now + TIME_SYNC_TICKS);
        CLOCK.reset();
        NukeFlash.reset();
        Deafness.reset();
        NukeDust.reset();
        NukePall.reset();
    }

    public static void radiation(S2C.Radiation p) {
        radiation = p;
    }

    /** Блоки, которые снесла волна в чанке рядом: куски разлетаются ({@link NukeDust#debris}), руины потом осыпаются. */
    public static void debris(S2C.NukeDebris p) {
        Active a = DETONATIONS.get(p.detonation());
        if (a == null) return;
        NukeDust.debris(a, p.pieces());
        a.sounds.debris(p.pieces());
    }

    public static void reset() {
        stopAll();
        DETONATIONS.clear();
        WARNINGS.clear();
        radiation = null;
        CLOCK.reset();
        NukeFlash.reset();
        NukeSounds.reset();
        NukeSky.reset();
        Deafness.reset();
        NukeDust.reset();
        NukePall.reset();
    }

    /** Звуки всех подрывов — стоп (сами подрывы уходят или заменяются). */
    private static void stopAll() {
        for (Active a : DETONATIONS.values()) a.sounds.stop();
    }

    // ---------------------------------------------------------------- тик

    /**
     * Верх рельефа для пыли у земли; null — в измерении с потолком (верх карты высот там — потолок, а не земля: пыль
     * остаётся на высоте эпицентра). Над пустотой (Энд) рельефа нет — клуб остаётся на прежней высоте.
     */
    @Nullable
    private static Sightline.Heights ground(ClientLevel level) {
        if (level.dimensionType().hasCeiling()) return null;
        Sightline.Heights heights = FarTerrain.of(level);
        int floor = level.getMinBuildHeight() + 1;
        return (x, z) -> {
            double h = heights.at(x, z);
            return h <= floor ? Double.NaN : h;
        };
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        long now = level.getGameTime();
        CLOCK.tick(now);
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Sightline.Heights heights = ground(level);
        double near = mc.options.getEffectiveRenderDistance() * 16.0;
        for (Iterator<Active> it = DETONATIONS.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            a.age++;
            a.sounds.tick();
            a.pall.daylight(a.ticks(0), level.getDayTime());
            // пыль у земли встаёт на рельеф там, где будет к концу следующего кадра
            if (heights != null) a.puffs.track(a.seconds(1), heights, eye.x, eye.z, near);
            NukeDust.front(a, eye);
            if (a.ticks(0) > lifeTicks(a.d) && !a.pall.visible(a.ticks(0))) {
                a.sounds.stop();
                it.remove();
            }
        }
        // предупреждение живёт, пока не придёт подрыв (или отбой — сервер пришлёт всё заново), но не дольше срока ожидания места
        WARNINGS.values().removeIf(w -> now > w.detonateTime() + NuclearStrikes.GIVE_UP_TICKS + 100);
        NukeSky.tick(level);
        NukeSounds.tick();
        NukeFlash.tick();
        Deafness.tick();
        NukeDust.tick();
        NukePall.tick(level, eye);
        Geiger.tick();
    }

    /**
     * Подрыв исчезает с клиента, когда гриб рассеялся и звук дошёл до дальних слушателей (Distant Horizons: 20 км),
     * а подрыв с осадками — когда его забудет и сервер (далеко по ветру осадки и чёрный дождь приходят через десятки
     * игровых часов).
     */
    private static double lifeTicks(Detonation d) {
        double cloudTicks = Math.max(CloudPuffs.lifeSeconds(d) * 20 * d.scale(), d.arrivalTicks(d.blocks(20_000)) + 200);
        return d.hasFallout() ? Math.max(cloudTicks, NuclearEvents.FORGET_AFTER) : cloudTicks;
    }

    // ---------------------------------------------------------------- для рисования и звука

    public static List<Active> detonations() {
        return DETONATIONS.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(DETONATIONS.values()));
    }

    public static boolean isEmpty() {
        return DETONATIONS.isEmpty() && WARNINGS.isEmpty();
    }

    public static Iterable<S2C.NukeWarning> warnings() {
        return WARNINGS.values();
    }

    /**
     * Сервер задерживает подрыв (догружает место удара), а не просто часы клиента ушли вперёд: срок прошёл
     * с запасом на отставание сервера и на задержку пакета.
     */
    public static boolean detonationOverdue(S2C.NukeWarning w, double now) {
        return overdue(now, w.detonateTime(), CLOCK.lead());
    }

    static boolean overdue(double now, long detonateTime, double lead) {
        return now - detonateTime > DELAY_GRACE_TICKS + lead;
    }

    @Nullable
    public static S2C.Radiation radiationState() {
        return radiation;
    }

}
