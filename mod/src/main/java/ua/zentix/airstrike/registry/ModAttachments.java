package ua.zentix.airstrike.registry;

import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.radiation.RadiationDose;

import java.util.function.Supplier;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTER = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Airstrike.MOD_ID);

    /** Доза и заражение игрока; смерть сбрасывает (не копируется). */
    public static final Supplier<AttachmentType<RadiationDose>> RADIATION = REGISTER.register("radiation",
            () -> AttachmentType.builder(() -> RadiationDose.NONE).serialize(RadiationDose.CODEC).build());

    /** Чанк: номер последнего ядерного подрыва, чьи повреждения к нему уже применены. */
    public static final Supplier<AttachmentType<Integer>> CHUNK_SCAR = REGISTER.register("chunk_scar",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    private ModAttachments() {}
}
