package ua.zentix.airstrike.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import ua.zentix.airstrike.strike.ServerActions;

/**
 * Пакеты мода. Мод обязателен у всех, поэтому канал обязательный; версия протокола меняется при любом
 * несовместимом изменении — тогда NeoForge честно скажет «разные версии мода», а не упадёт.
 */
public final class AirstrikeNetwork {
    public static final String PROTOCOL = "1";

    private AirstrikeNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(PROTOCOL);

        r.playToClient(S2C.Blast.TYPE, S2C.Blast.CODEC, (p, ctx) -> ClientHooks.get().blast(p));
        r.playToClient(S2C.BunkerImpact.TYPE, S2C.BunkerImpact.CODEC, (p, ctx) -> ClientHooks.get().bunkerImpact(p));
        r.playToClient(S2C.Vent.TYPE, S2C.Vent.CODEC, (p, ctx) -> ClientHooks.get().vent(p));
        r.playToClient(S2C.Collapse.TYPE, S2C.Collapse.CODEC, (p, ctx) -> ClientHooks.get().collapse(p));
        r.playToClient(S2C.Quake.TYPE, S2C.Quake.CODEC, (p, ctx) -> ClientHooks.get().quake(p));
        r.playToClient(S2C.Siren.TYPE, S2C.Siren.CODEC, (p, ctx) -> ClientHooks.get().siren(p));
        r.playToClient(S2C.SalvoStatus.TYPE, S2C.SalvoStatus.CODEC, (p, ctx) -> ClientHooks.get().salvoStatus(p));
        r.playToClient(S2C.Cleared.TYPE, S2C.Cleared.CODEC, (p, ctx) -> ClientHooks.get().cleared());
        r.playToClient(S2C.OpenRemote.TYPE, S2C.OpenRemote.CODEC, (p, ctx) -> ClientHooks.get().openRemote());

        r.playToServer(C2S.Fire.TYPE, C2S.Fire.CODEC, ServerActions::fire);
        r.playToServer(C2S.SetLoadout.TYPE, C2S.SetLoadout.CODEC, ServerActions::setLoadout);
        r.playToServer(C2S.Clear.TYPE, C2S.Clear.CODEC, ServerActions::clear);
    }
}
