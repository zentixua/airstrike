# Задание ноутбуку: пять TikTok-превью по повторам 06.10

Ветка `claude/project-thread-h5ohwa` (zentixua/incoming), коммит — в сообщении координатора. Повторы — только копии и
только игры 06.10: война `2026-10-09T15_24_55` (главный), `06.10 09:59`, `06.10 16:31`. Инстанс, миры и настройки
Артёма не трогать, не во время его игры. Шаги 1–3 — процессор, их можно делать сразу, параллельно выводу трейлера;
шаг 4 — видеокарта, после вывода трейлера (одна тяжёлая задача на видеокарте). Всё большое — в
`mod/run/claude-work/tiktok/`.

## 1. Сборка
```sh
mod/gradlew -p tools/replayscan build      # tools/replayscan/build/libs/replayscan-1.0.0.jar
```

## 2. Выбор моментов
Базы — из разбора повторов (тред «Разбор реплеев»): `<вывод разбора>/<повтор>/replay.duckdb` трёх повторов.
```sh
T=mod/run/claude-work/tiktok
uv run tools/clips/moments.py <база войны> <база 06.10 09:59> <база 06.10 16:31> --out $T/clips --prefer 2026-10-09T15_24_55
uv run tools/clips/plan.py $T/clips/candidates.json --replays <каталог копий повторов>
```
Итог: `$T/clips/candidates.md` (кандидаты и почему), `$T/clips/film/runs.json` (повтор, план, версия Incoming).

## 3. Клиент съёмки
На каждый повтор из `runs.json` — клиент, которым шёл его разбор (моды того дня и jar Incoming версии `incoming`), в
его `mods/` — свежий `replayscan-1.0.0.jar`. Отрисовка — как у Артёма в тот день: Sodium, Iris с шейдерпаком и Distant
Horizons из той же копии.

## 4. Съёмка (видеокарта)
По одному повтору за раз:
```sh
tools/laptop_job.sh tiktok-film -- tools/prod_client.py --no-copy --dir <клиент> --incoming-jar <jar Incoming> \
  --jvm -Xmx12g --prop replayscan.film=$PWD/$T/clips/film/<повтор>/plan.json
```
Успех: в `<клиент>/logs/latest.log` — `Film: done`, у каждого плана строка `Film: verdict <план> <камера>: PASS`;
`$T/clips/film/<повтор>/film/best.json` — все планы. Клиент выходит сам. Упал — запуск заново продолжит с того плана,
которого нет в `best.json`.

## 5. Монтаж
```sh
uv run tools/clips/edit.py $T/clips/film --music <трек из release-media/music> --credit "<строка из ATTRIBUTION.txt>"
```

## Прислать
`$T/clips/candidates.md`, `$T/clips/film/previews/report.md`, строки `Film: verdict` и ошибки (`Exception`, `ERROR`)
из логов съёмки; ролики `previews/<ролик>.mp4`, `-music.mp4` и подписи `<ролик>.txt` — в файлы проекта
(`tiktok-previews/`, сжатые копии до 20 МиБ, если больше).
