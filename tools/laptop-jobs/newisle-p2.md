# Задание ноутбуку: трейлер Newisle, П2 — игра Артёма и ночь съёмки

Ветка `claude/newisle-production-16ixfw` (zentixua/incoming), коммит — в брифе сессии (после П1: позы
`newisle-poses.json` и найденное `newisle-found.json` уже в нём). Всё — в рабочем дереве съёмки
`/mnt/data/projects/airstrike`. Инстанс Артёма «Airstrike Pack», его миры и настройки не трогать; видеокарта — одна
задача и только при закрытой игре (конвейер сам ждёт, пока идёт java с `instances/Airstrike Pack`, и снимает свой
клиент, если игра запустилась). Удалять можно только копии этой задачи (`mod/run/scenario/saves/nw-*`, папка работы) —
строка Артёма, что начала П2, должна это разрешать. Записи и миры не покидают ноутбук; наружу — текст, утром черновик на
его телефон по Taildrop. Фильтр остановил шаг — точный шаг и сообщение Артёму, не обходить.

## 1. Мир и машина
1. Архив копии для съёмки прислал netcup по Taildrop (конец П1): `tailscale file get mod/run/`, распаковать в
   `mod/run/newisle-master/` (`level.dat`, `region/`, `data/` с базой DH), архив — прочь. Это мастер конвейера: он его
   только копирует.
2. `git fetch origin claude/newisle-production-16ixfw && git checkout <SHA>`; `cd mod && ./gradlew build`.
3. Машина `mod/run/newisle-laptop.json` (моды съёмки — каталог v5, `trailer-film.md`; `PATH` — с каталогом `uv`:
   служба `laptop_job.sh` не берёт окружение оболочки, а монтаж идёт через `uv run`):
   ```json
   {"repo": "/mnt/data/projects/airstrike", "master": "/mnt/data/projects/airstrike/mod/run/newisle-master",
    "work": "/mnt/data/projects/airstrike/mod/run/newisle-night", "protected": ["<python3 tools/paths.py PRISM>"],
    "record": ["tools/trailer/film.sh", "record", "shaders"], "render": ["tools/trailer/film.sh", "render", "shaders"],
    "commit": "<SHA>", "pause_if_running": ["instances/Airstrike Pack"],
    "env": {"JAVA_HOME": "<python3 tools/paths.py JAVA>", "PATH": "<каталог uv>:/usr/local/bin:/usr/bin:/bin",
            "INCOMING_MODS_DIR": "run/film-war/minecraft/mods", "INCOMING_SIZE": "2560x1440",
            "INCOMING_RENDER_DISTANCE": "32", "INCOMING_LANG": "en_us", "INCOMING_FILM_CLOUDS": "320",
            "INCOMING_NEED_FRAMES": "3000", "INCOMING_MUSIC": "<Juggernaut.mp3 на ноутбуке, как у монтажа v5>"}}
   ```

## 2. Игра Артёма (~20 минут, вечером)
Его игра закрыта (`pgrep -f 'instances/Airstrike Pack'` пусто). Окно игры — на его рабочем столе и звук — в его
колонки (`INCOMING_LIVE_WINDOW`, его согласие на этот вечер): окружение его сеанса (`WAYLAND_DISPLAY`, `DISPLAY`,
`XDG_RUNTIME_DIR` — `systemctl --user show-environment`).
```sh
python3 tools/trailer/produce.py live --config mod/run/newisle-laptop.json --take T03   # терраса: n49, n50a, n50
python3 tools/trailer/produce.py live --config mod/run/newisle-laptop.json --take T08   # рыбак: n53
```
Он появляется на месте, подсказки на экране; начало — Enter в игре, конец — сам. `stage: done` в
`mod/run/newisle-night/status.txt` — снят; `stage: stopped, waiting` — причина в `last error`, повтор сразу той же
строкой. Планы его дублей выводятся в начале ночи.

## 3. Ночь
```sh
setsid nohup tools/laptop_job.sh newisle-night -- python3 tools/trailer/produce.py night \
  --config mod/run/newisle-laptop.json > mod/run/newisle-night.out 2>&1 &
systemd-run --user --on-calendar='*:0/30' --unit=newisle-watch -- \
  /mnt/data/projects/airstrike/mod/run/claude-work/glm/glm.sh watch -o /mnt/data/projects/airstrike/mod/run/newisle-night/watch.txt \
  /mnt/data/projects/airstrike/mod/run/newisle-night/status.txt /mnt/data/projects/airstrike/mod/run/newisle-night/produce.log
```
`night`: проба T02 (убитый клиент), T04 («полный диск»), T24 (плохая камера) в `newisle-night/smoke`; прошла
(`smoke.json`: `pass`) — все дубли по порядку истории, вывод, черновик 720p30, при всех снятых планах — чистовой 1440p60;
не прошла — `stopped, waiting`, ночь не тратится. Перезапуск той же строкой продолжает с места.
Сторож (GLM, только чтение) раз в 30 минут пишет `watch.txt`: первая строка `STATUS: OK` или `STATUS: BROKEN: …`
(и конвейер, молчащий дольше 20 минут: статус он переписывает раз в 5 минут). После запуска — сразу конец хода: ночью
Claude не участвует, конвейер сам ставит паузы, пересъёмки и «stopped, waiting». Утром сторож — прочь
(`systemctl --user stop newisle-watch.timer`), его `watch.txt` читается вместе со статусом.

## 4. Утро (строка Артёма «черновик»)
Прочитать только `status.txt` и список не снятых; `mod/run/newisle-night/cut/newisle-draft.mp4` — на его телефон по
Taildrop (`tailscale file cp … pixel-9a:`; его строка покрывает передачу). Куски пробы — в черновике (n42, n52, n43; n13,
n38, n14; n33, n47, n36, n35). Ответ ≤ 5 строк: что готово, что не снято и почему. Правки по номерам планов: порядок,
длина, музыка — лист аниматика и `python3 tools/trailer/newisle_cut.py --work mod/run/newisle-night --draft`; камера —
перевывод одного плана из его повтора (`.claude/rules/trailer.md`: «Правка по замечанию»); мир дубля лежит в
`saves/`, пока его не убрала проверка места.
