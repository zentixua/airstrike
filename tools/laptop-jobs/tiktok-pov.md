# Задание ноутбуку: отрезки игр 06.10 глазами игроков (TikTok)

Коммит zentixua/incoming — в сообщении координатора. Повторы — только копии и только игры 06.10: война
`2026-10-09T15_24_55`, `06.10 09:59`, `06.10 16:31`, `06.10 22:13`. Инстанс, миры и настройки Артёма не трогать, не во
время его игры; на видеокарте — одна тяжёлая задача. Всё большое — в `mod/run/claude-work/tiktok/` (`T` ниже).
Клиенты съёмки и копии повторов — те же, что у прошлой съёмки превью (по клиенту на повтор, моды того дня, Sodium,
Iris с шейдерпаком, Distant Horizons).

## 1. Сборка
```sh
mod/gradlew -p tools/replayscan build      # tools/replayscan/build/libs/replayscan-1.0.0.jar — в mods/ каждого клиента съёмки
```
В этом jar камера «глазами игрока» садит и зрителя повтора на сервере в того же игрока: звуки рядом с игроком
(выстрелы, шаги) теперь доходят до кадра.

## 2. Отрезки
```sh
T=mod/run/claude-work/tiktok
uv run tools/clips/moments.py mod/run/claude-work/replayscan/out/{2026-10-09T15_24_55,2026-10-06T09_59_03,2026-10-06T16_31_49,2026-10-06T22_13_30}/replay.duckdb \
  --out $T/pov --notes $T/notes-0610.json --star player:MartinFrances --pov-top 15
uv run tools/clips/plan.py $T/pov/candidates.json --all pov --replays <каталог копий повторов>
```
Итог: раздел «6. Глазами игроков» в `$T/pov/candidates.md`, планы — `$T/pov/film/<повтор>/plan.json`.

## 3. Съёмка (видеокарта)
По одному повтору за раз, окно клиента — не размера кадра (как в прошлый раз):
```sh
tools/laptop_job.sh tiktok-pov -- tools/prod_client.py --no-copy --dir <клиент> --incoming-jar <jar Incoming> \
  --jvm -Xmx12g --prop replayscan.film=$PWD/$T/pov/film/<повтор>/plan.json
```
Успех: в `<клиент>/logs/latest.log` — `Film: done`; у каждого плана — `Film: the replay viewer spectates <игрок> on the
replay server` и `Film: verdict <план> <камера>: PASS` (`dh_world` 0). Звук: в `sounds.jsonl` отрезка со сбитием
глазами MartinFrances есть звуки оружия (`pointblank:`), в остальных — шаги игрока (`step`). Не прошедший план не
доводить: Артём выбирает из снятых.

## 4. Монтаж
```sh
uv run tools/clips/edit.py $T/pov/film --out $T/pov/clips
```

## Прислать
`$T/pov/candidates.md` (раздел «6»), `$T/pov/clips/report.md`, строки `Film: verdict` и `spectates`, ошибки
(`Exception`, `ERROR`) из логов съёмки, по каждому отрезку число строк `pointblank:` и `step` в его `sounds.jsonl`;
ролики `$T/pov/clips/pov-<n>.mp4` и подписи `pov-<n>.txt` — в файлы проекта (`tiktok-pov/`; копии до 20 МиБ, если
больше: H.264 CRF 20, звук как есть).
