package ua.zentix.airstrike.net;

/**
 * Мост к клиентскому коду: сервер регистрирует обработчики пакетов «сервер → клиент», но на выделенном сервере
 * они никогда не выполняются, а клиентских классов там нет. Клиентский модуль подставляет реализацию при запуске.
 */
public interface ClientHooks {
    default void blast(S2C.Blast p) {}

    default void bunkerImpact(S2C.BunkerImpact p) {}

    default void vent(S2C.Vent p) {}

    default void collapse(S2C.Collapse p) {}

    default void quake(S2C.Quake p) {}

    default void gridFailure(S2C.GridFailure p) {}

    default void gridDistrict(S2C.GridDistrict p) {}

    default void siren(S2C.Siren p) {}

    default void salvoStatus(S2C.SalvoStatus p) {}

    default void openRemote() {}

    default void cleared(S2C.Cleared p) {}

    default void nukeWarning(S2C.NukeWarning p) {}

    default void nukeDetonation(S2C.NukeDetonation p) {}

    default void nukeSync(S2C.NukeSync p) {}

    default void radiation(S2C.Radiation p) {}

    default void flights(S2C.Flights p) {}

    default void heard(S2C.Heard p) {}

    default void mapPlayers(S2C.MapPlayers p) {}

    ClientHooks NONE = new ClientHooks() {};

    final class Holder {
        static volatile ClientHooks instance = NONE;

        private Holder() {}
    }

    static ClientHooks get() {
        return Holder.instance;
    }

    static void set(ClientHooks hooks) {
        Holder.instance = hooks;
    }
}
