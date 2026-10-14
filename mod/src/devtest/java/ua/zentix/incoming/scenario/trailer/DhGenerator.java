package ua.zentix.incoming.scenario.trailer;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.interfaces.config.both.IDhApiWorldGenerationConfig;
import ua.zentix.incoming.Incoming;

/**
 * Генератор LOD Distant Horizons у клиента съёмки (класс грузится, только если DH стоит). Съёмка берёт LOD только из
 * базы мира (мастер Zearth: база построена с карты) и из загруженных чанков. Генератор DH (по умолчанию
 * {@code SURFACE_THEN_CHUNKS}) строит LOD там, где их нет в базе, и пишет их в базу мира: первый проход — грубый
 * рельеф шумом генератора мира, без чтения сохранённых чанков ({@code DhRoughSurfaceGenerator}, ниже уровня моря —
 * вода), то есть лес и море поверх города карты; в повторе Flashback — генератором мира повтора. Поэтому {@code record.sh}
 * у съёмки ставит {@code generatorPlan = "DISABLED"}, а запись и вывод без него не начинаются.
 */
final class DhGenerator {
    private static boolean checked;

    private DhGenerator() {}

    /** Генератор выключен — иначе исключение (съёмка не начинается); проверка и строка в лог — один раз. */
    static void require() {
        if (checked) return;
        IDhApiWorldGenerationConfig gen = DhApi.Delayed.configs == null ? null : DhApi.Delayed.configs.worldGenerator();
        if (gen == null) throw new IllegalStateException("DH: настройки генератора ещё не загружены");
        EDhApiGeneratorPlan plan = gen.GeneratorPlan().getValue();
        Incoming.LOG.info("SCENARIO film dh generator {} {}", plan, gen.chunkGeneratorMode().getValue());
        if (plan != EDhApiGeneratorPlan.DISABLED) {
            throw new IllegalStateException("DH строит LOD генератором (" + plan + ") поверх базы мира: нужен generatorPlan"
                    + " = \"DISABLED\" в config/DistantHorizons.toml (record.sh)");
        }
        checked = true;
    }
}
