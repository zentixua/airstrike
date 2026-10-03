package ua.zentix.airstrike.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import ua.zentix.airstrike.strike.PickHints;
import ua.zentix.airstrike.strike.ServerActions;

/**
 * Пакеты мода. Мод обязателен у всех, поэтому канал обязательный; версия протокола меняется при любом
 * несовместимом изменении — тогда NeoForge честно скажет «разные версии мода», а не упадёт.
 */
public final class AirstrikeNetwork {
    public static final String PROTOCOL = "14";

    private AirstrikeNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(PROTOCOL);

        r.playToClient(S2C.Blast.TYPE, S2C.Blast.CODEC, (p, ctx) -> ClientHooks.get().blast(p));
        r.playToClient(S2C.BunkerImpact.TYPE, S2C.BunkerImpact.CODEC, (p, ctx) -> ClientHooks.get().bunkerImpact(p));
        r.playToClient(S2C.Vent.TYPE, S2C.Vent.CODEC, (p, ctx) -> ClientHooks.get().vent(p));
        r.playToClient(S2C.Collapse.TYPE, S2C.Collapse.CODEC, (p, ctx) -> ClientHooks.get().collapse(p));
        r.playToClient(S2C.GridFailure.TYPE, S2C.GridFailure.CODEC, (p, ctx) -> ClientHooks.get().gridFailure(p));
        r.playToClient(S2C.GridDistrict.TYPE, S2C.GridDistrict.CODEC, (p, ctx) -> ClientHooks.get().gridDistrict(p));
        r.playToClient(S2C.Quake.TYPE, S2C.Quake.CODEC, (p, ctx) -> ClientHooks.get().quake(p));
        r.playToClient(S2C.Siren.TYPE, S2C.Siren.CODEC, (p, ctx) -> ClientHooks.get().siren(p));
        r.playToClient(S2C.SalvoStatus.TYPE, S2C.SalvoStatus.CODEC, (p, ctx) -> ClientHooks.get().salvoStatus(p));
        r.playToClient(S2C.Cleared.TYPE, S2C.Cleared.CODEC, (p, ctx) -> ClientHooks.get().cleared(p));
        r.playToClient(S2C.NukeWarning.TYPE, S2C.NukeWarning.CODEC, (p, ctx) -> ClientHooks.get().nukeWarning(p));
        r.playToClient(S2C.NukeDetonation.TYPE, S2C.NukeDetonation.CODEC, (p, ctx) -> ClientHooks.get().nukeDetonation(p));
        r.playToClient(S2C.NukeSync.TYPE, S2C.NukeSync.CODEC, (p, ctx) -> ClientHooks.get().nukeSync(p));
        r.playToClient(S2C.Radiation.TYPE, S2C.Radiation.CODEC, (p, ctx) -> ClientHooks.get().radiation(p));
        r.playToClient(S2C.Flights.TYPE, S2C.Flights.CODEC, (p, ctx) -> ClientHooks.get().flights(p));
        r.playToClient(S2C.FarFlights.TYPE, S2C.FarFlights.CODEC, (p, ctx) -> ClientHooks.get().farFlights(p));
        r.playToClient(S2C.MapPlayers.TYPE, S2C.MapPlayers.CODEC, (p, ctx) -> ClientHooks.get().mapPlayers(p));
        r.playToClient(S2C.OpenRemote.TYPE, S2C.OpenRemote.CODEC, (p, ctx) -> ClientHooks.get().openRemote());
        r.playToClient(S2C.ReplayTick.TYPE, S2C.ReplayTick.CODEC, (p, ctx) -> ClientHooks.get().replayTick(p));

        r.playToServer(C2S.Fire.TYPE, C2S.Fire.CODEC, ServerActions::fire);
        r.playToServer(C2S.SetLoadout.TYPE, C2S.SetLoadout.CODEC, ServerActions::setLoadout);
        r.playToServer(C2S.Clear.TYPE, C2S.Clear.CODEC, ServerActions::clear);
        r.playToServer(C2S.Retarget.TYPE, C2S.Retarget.CODEC, ServerActions::retarget);
        r.playToServer(C2S.MapPlayers.TYPE, C2S.MapPlayers.CODEC, ServerActions::mapPlayers);
        r.playToServer(C2S.Pick.TYPE, C2S.Pick.CODEC, PickHints::pick);
    }
}
