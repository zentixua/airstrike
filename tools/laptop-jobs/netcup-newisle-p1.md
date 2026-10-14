# Задание netcup: трейлер Newisle, П1 — прогоны вслепую и поиски

Ветка `claude/newisle-production-16ixfw` (zentixua/incoming), коммит — в брифе сессии. План —
`/mnt/project-files/handoff/trailer-newisle-plan.md` (у netcup его нет: всё нужное — здесь и в брифе). Цель: каждый дубль
трейлера (`tools/trailer/scenes/newisle-takes.json`) ставится чисто по данным до ночи на ноутбуке, поиски выбирают места
и размеры, постановщик (`Newisle.java`) чинится в коде, пока все дубли не чистые.

Не трогать: golden `~/airstrike-server/golden/Newisle` и копию для съёмки `~/airstrike-work/newisle-film-1014` (конвейер
только копирует их; запись и удаление под ними он отказывается делать по пути), Docker-службы Артёма, его сервер.
Свободной памяти — не меньше 4 ГБ (`limits.ram_floor_gb` машины), один игровой сервер за раз. «Newisle View» (Crafty,
d647ec92) — стоп только на прогоны и только по строке Артёма в этой нити, запуск обратно — в конце при любом исходе.
Наружу — только текст (итоги, позы, найденное); миры, повторы и логи остаются на netcup. Фильтр остановил шаг — точный
шаг и сообщение в отчёт, не обходить.

## Подготовка
1. Клон: `git fetch origin claude/newisle-production-16ixfw && git checkout <SHA>`; `cd mod && ./gradlew build`.
2. Моды съёмки `mod/run/film-fb-mods` и «чистый» jar клиента — как в `netcup-film-check.md` («Подготовка»), версии
   сверить с ним. Без Sodium, Iris и DH.
3. Позы камер: новейшая разведка `scout.py` (`~/claude/scout4/poses.json`; нет — новейшая из scout2..3) →
   `tools/trailer/scenes/newisle-poses.json`, коммитом (это текст). Позы нет — постановщик сам останавливается
   («нет поз камер …»), конвейер — «stopped, waiting».
4. Лицензия карты: readme, credits или licence из загрузки Newisle (рядом с golden или в архиве) — цитата в отчёт.
   Видео запрещено — остановиться и сказать Артёму до прогонов.
5. Машина `~/claude/newisle-p1.json` (пути — свои; `render: null` — прогон вслепую: запись и проверка по данным, без
   вывода и монтажа; кадры вживую не снимаются — `INCOMING_FILM_LIVE=0`):
   ```json
   {"repo": "<клон incoming>", "master": "/home/ubuntu/airstrike-work/newisle-film-1014",
    "work": "/home/ubuntu/claude/newisle-p1", "protected": ["/home/ubuntu/airstrike-server"],
    "record": ["tools/trailer/film.sh", "record"], "render": null, "cut": false, "commit": "<SHA>",
    "limits": {"ram_floor_gb": 4}, "pause_if_running": [],
    "env": {"JAVA_HOME": "<JDK 21>", "INCOMING_DH": "", "INCOMING_CLOUDS": "false", "INCOMING_FILM_CLOUDS": "320",
            "INCOMING_FILM_READY": "0", "INCOMING_FILM_LIVE": "0", "INCOMING_CONNECTOR_CLEAN": "<srg jar>",
            "INCOMING_MODS_DIR": "run/film-fb-mods", "INCOMING_SIZE": "640x360", "INCOMING_NEED_FRAMES": "1000",
            "INCOMING_LANG": "en_us", "INCOMING_RENDER_DISTANCE": "24"}}
   ```

## Ход
Долгое — своей службой и без опроса: `systemd-run --user --collect --same-dir --unit=newisle-p1-<шаг> -- <команда>`
(linger включён), ждать — одним циклом Monitor по первой строке `<work>/status.txt` (`stage: done` или
`stage: stopped`), без запросов к Claude между проверками.
1. Остановить «Newisle View» (Crafty, d647ec92, как в team memory `netcup-host-facts`; пароль администратора никуда
   не копировать). Запуск обратно — последним шагом при любом исходе.
2. Поиски: `python3 tools/trailer/produce.py search --config ~/claude/newisle-p1.json` — каждая проба на своей копии
   мастера; выбор — по правилам `searches` в `newisle-takes.json`, итог — `tools/trailer/scenes/newisle-found.json`
   (постановщик берёт его места и размер залпа раньше своих). Коммит одним файлом.
3. Дубли Артёма ботом вместо него (копия мастера): `produce.py live --config … --take T03 --standin`, то же `T08`.
4. Вся цепочка: `produce.py run --config …` (порядок истории, снимок после каждого дубля, пересъёмка дважды, потом
   «не снят»).

Что проверить у каждого дубля (папка `<work>/takes/<дубль>/a<n>/`): `result.json` (у ударов `bound`, `eta` и
`actual`, у пролёта — `pass_distance`; `probes` — стекло до и после в n50, n39, n40, дом стоит; `warnings`), строки
`SCENARIO film staging` и `SCENARIO film camera` в `record.log`, `Can't keep up` (тики), `replays/<план>.sounds.jsonl`
есть у каждого плана, в n49–n50 — без сирены, МБР n31 стартует со дна шахты (первое место снаряда в
`replays/n31.events.jsonl` — в шахте). Конвейер сам ловит падение игры, удар не в срок (больше 1 с), камеру в блоках и
ненаступивший момент; второе падение на том же дубле — «stopped, waiting».

## Починка
Дубль не чист — причина по его `record.log` и `result.json`, правка постановщика (`Newisle.java`, `NewisleData.java`)
или данных (`newisle-takes.json`, `newisle.json`), `./gradlew build`, юнит-тесты (`./gradlew test`,
`python3 -m unittest discover -s tools/trailer/tests`), прогон одного дубля в новой папке работы:
`produce.py run --config … --takes T05 --work ~/claude/newisle-p1-fix-<n>` (с копии мастера — постановку этого хватает
проверить). Когда все чистые — вся цепочка ещё раз в новой папке. Пуш в ветку.

## Мир на ноутбук
Когда все дубли чистые: архив копии для съёмки (только чтение мастера, архив — в `~/claude`) → ноутбуку по Taildrop
(`tailscale file cp newisle-film.tar zentixua-pc:`; обе машины Артёма, строка Артёма, что начала П1, это разрешает),
потом архив — прочь. Ноутбук берёт его в П2.

## Отчёт (send_message координатору, ≤ 20 строк)
По дублю — PASS или что не так; что выбрали поиски (`newisle-found.json`: терраса, «Град», места, лодка, Point Blank);
цитата лицензии; SHA коммита; «Newisle View» запущен снова. Папки работы и копии миров (`mod/run/scenario/saves/nw-*`)
после отчёта удалить — это копии этой задачи.
