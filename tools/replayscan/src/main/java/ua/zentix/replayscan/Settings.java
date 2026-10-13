package ua.zentix.replayscan;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Настройки разбора — свойства JVM {@code replayscan.*} (запуск из Gradle: {@code -P<имя>}, README.md).
 *
 * @param replay   повтор Flashback (zip); без него мод ничего не делает
 * @param out      каталог таблиц (по умолчанию {@code replayscan/<имя повтора>} в каталоге игры)
 * @param speed    тиков повтора в секунду (20 — как в игре); сверху его держит скорость разбора
 * @param viewers  чьими глазами считать видимость: {@code recorder} (записавший), {@code players} (все игроки)
 * @param every    видимость сущностей и дальних снарядов — раз в столько тиков
 * @param range    дальше этого (блоков) видимость не считается
 * @param subjects id типов сущностей, за которыми следить (регулярное выражение; игроки — всегда)
 * @param ask      файл камер (JSON, README.md): видимость всего из этих точек
 * @param from     с какого тика писать (до него — перемотка без таблиц)
 * @param to       до какого тика (включительно); 0 — до конца
 */
record Settings(Path replay, Path out, float speed, String viewers, int every, double range, Pattern subjects, Path ask, int from, int to) {

    static Settings read(Path gameDir) {
        String replay = System.getProperty("replayscan.replay");
        if (replay == null || replay.isBlank()) return null;
        Path zip = Path.of(replay).toAbsolutePath();
        String name = zip.getFileName().toString().replaceFirst("\\.zip$", "");
        String out = System.getProperty("replayscan.out");
        String ask = System.getProperty("replayscan.ask");
        return new Settings(zip, out == null ? gameDir.resolve("replayscan").resolve(name) : Path.of(out).toAbsolutePath(),
                Float.parseFloat(System.getProperty("replayscan.speed", "200")),
                System.getProperty("replayscan.viewers", "recorder"),
                Math.max(1, Integer.parseInt(System.getProperty("replayscan.every", "2"))),
                Double.parseDouble(System.getProperty("replayscan.range", "1500")),
                Pattern.compile(System.getProperty("replayscan.subjects", "(incoming|airstrike):(?!debris$).*")),
                ask == null || ask.isBlank() ? null : Path.of(ask).toAbsolutePath(),
                Integer.parseInt(System.getProperty("replayscan.from", "0")),
                Integer.parseInt(System.getProperty("replayscan.to", "0")));
    }
}
