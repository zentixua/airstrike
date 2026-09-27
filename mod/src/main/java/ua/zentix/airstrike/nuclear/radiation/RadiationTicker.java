package ua.zentix.airstrike.nuclear.radiation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.model.SicknessModel;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModEffects;

/**
 * Радиация у игроков (DESIGN-nuke §4): раз в секунду — мощность дозы осадков в точке игрока с экранированием
 * крышей, заражение под чёрным дождём (смывается водой), накопление дозы и стадии лучевой болезни по
 * {@link SicknessModel}. Мобы получают только мгновенную дозу при подрыве, чтобы не тикать тысячи мобов.
 */
public final class RadiationTicker {
    /** 1 Р ≈ 0.01 Гр в теле. */
    private static final double GY_PER_R = 0.01;
    /** Секунда в игровых часах: 20 тиков из 1000. */
    private static final double HOURS_PER_SECOND = 20 / 1000.0;
    private static final ResourceLocation HEALTH_PENALTY = Airstrike.id("radiation_sickness");

    private RadiationTicker() {}

    public static RadiationDose dose(ServerPlayer p) {
        return p.getData(ModAttachments.RADIATION);
    }

    /** Мгновенная доза (проникающая радиация подрыва), Гр. */
    public static void addDose(ServerPlayer p, float gy) {
        RadiationDose r = dose(p);
        set(p, new RadiationDose(r.doseGy() + gy, r.contamination(), r.isExposed() || r.doseGy() + gy < 1 ? r.exposedAt() : p.level().getGameTime(), r.rate()));
    }

    public static void clear(ServerPlayer p) {
        set(p, RadiationDose.NONE);
        AttributeInstance health = p.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.removeModifier(HEALTH_PENALTY);
        p.removeEffect(ModEffects.RADIATION_SICKNESS);
    }

    private static void set(ServerPlayer p, RadiationDose next) {
        RadiationDose prev = dose(p);
        p.setData(ModAttachments.RADIATION, next);
        if (Math.abs(prev.doseGy() - next.doseGy()) > 0.005 || Math.abs(prev.rate() - next.rate()) > Math.max(0.01, prev.rate() * 0.05)
                || Math.abs(prev.contamination() - next.contamination()) > 0.05) {
            PacketDistributor.sendToPlayer(p, new S2C.Radiation(next.doseGy(), next.rate(), next.contamination(), stage(p, next).ordinal()));
        }
    }

    public static void tick(ServerLevel level) {
        if (!AirstrikeConfig.SERVER.nukeRadiation.get() || level.getGameTime() % 20 != 0) return;
        NuclearEvents events = NuclearEvents.get(level);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            tick(level, p, events);
        }
    }

    private static void tick(ServerLevel level, ServerPlayer p, NuclearEvents events) {
        long now = level.getGameTime();
        double field = 0;
        boolean blackRain = false;
        for (Detonation d : events.detonations()) {
            if (!d.hasFallout()) continue;
            field += d.falloutRate(p.getX(), p.getZ(), now - d.gameTime());
            if (d.blackRain(p.getX(), p.getZ(), now - d.gameTime())) blackRain = true;
        }
        RadiationDose r = dose(p);
        double contamination = r.contamination();
        if (blackRain && AirstrikeConfig.SERVER.nukeBlackRain.get() && Detonation.underOpenSky(level, p.getEyePosition())) {
            contamination = Math.min(contamination + field * 0.05, field * 2);
        }
        if (p.isInWater()) contamination *= 0.8;
        if (contamination < 0.01) contamination = 0;
        double rate = (field > 0 ? field * roofShielding(level, p.blockPosition()) : 0) + contamination;
        // в творческом режиме счётчик показывает поле, но доза не копится и болезни нет
        double gy = r.doseGy() + (p.isCreative() ? 0 : rate * GY_PER_R * HOURS_PER_SECOND);
        long exposed = r.isExposed() || gy < 1 ? r.exposedAt() : now;
        RadiationDose next = new RadiationDose((float) gy, (float) contamination, exposed, (float) rate);
        set(p, next);
        sickness(level, p, next);
    }

    /** Крыша над головой: каждый твёрдый блок до неба ×0.3, но не меньше ×0.001. */
    private static double roofShielding(ServerLevel level, BlockPos pos) {
        double k = 1;
        BlockPos.MutableBlockPos m = pos.mutable().move(0, 2, 0);
        for (int i = 0; i < 24 && m.getY() < level.getMaxBuildHeight(); i++, m.move(0, 1, 0)) {
            BlockState s = level.getBlockState(m);
            if (!s.getCollisionShape(level, m).isEmpty()) k *= 0.3;
            if (k <= 0.001) return 0.001;
        }
        return k;
    }

    private static SicknessModel.Stage stage(ServerPlayer p, RadiationDose r) {
        if (!r.isExposed()) return SicknessModel.Stage.NONE;
        return SicknessModel.stage(r.doseGy(), (p.level().getGameTime() - r.exposedAt()) / 1000.0);
    }

    /** Стадии лучевой болезни: тошнота и рвота, скрытый период, разгар (слабость, голод, меньше здоровья), гибель. */
    private static void sickness(ServerLevel level, ServerPlayer p, RadiationDose r) {
        SicknessModel.Stage stage = stage(p, r);
        AttributeInstance health = p.getAttribute(Attributes.MAX_HEALTH);
        double penalty = stage == SicknessModel.Stage.MANIFEST || stage == SicknessModel.Stage.FATAL ? SicknessModel.maxHealthPenalty(r.doseGy()) : 0;
        if (health != null) {
            health.removeModifier(HEALTH_PENALTY);
            if (penalty > 0) health.addTransientModifier(new AttributeModifier(HEALTH_PENALTY, -penalty, AttributeModifier.Operation.ADD_VALUE));
        }
        if (stage == SicknessModel.Stage.NONE) {
            p.removeEffect(ModEffects.RADIATION_SICKNESS);
            return;
        }
        p.addEffect(new MobEffectInstance(ModEffects.RADIATION_SICKNESS, 60, stage.ordinal() - 1, true, false, true));
        switch (stage) {
            case PRODROMAL -> {
                p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0, true, false, false));
                if (level.random.nextFloat() < 0.04f) vomit(level, p);
            }
            case MANIFEST, FATAL -> {
                int weakness = SicknessModel.weaknessAmplifier(r.doseGy());
                if (weakness >= 0) p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, weakness, true, false, false));
                int hunger = (int) Math.round(SicknessModel.hungerMultiplier(r.doseGy()) * 2) - 2;
                if (hunger >= 0) p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 60, hunger, true, false, false));
                if (stage == SicknessModel.Stage.FATAL) {
                    float dmg = Math.max(1, r.doseGy() / 5);
                    p.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, null), dmg);
                    if (level.random.nextFloat() < 0.05f) vomit(level, p);
                }
            }
            default -> {}
        }
    }

    private static void vomit(ServerLevel level, ServerPlayer p) {
        p.getFoodData().setFoodLevel(Math.max(0, p.getFoodData().getFoodLevel() - 3));
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0, true, false, false));
        level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 1.0f, 0.6f);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.ROTTEN_FLESH)),
                p.getX(), p.getEyeY() - 0.2, p.getZ(), 12, 0.1, 0.1, 0.1, 0.12);
    }

    /** В разгаре болезни при дозе ≥ 2 Гр естественного восстановления здоровья нет. */
    public static void onHeal(LivingHealEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        RadiationDose r = dose(p);
        SicknessModel.Stage stage = stage(p, r);
        if ((stage == SicknessModel.Stage.MANIFEST || stage == SicknessModel.Stage.FATAL) && SicknessModel.noRegeneration(r.doseGy())) e.setCanceled(true);
    }
}
