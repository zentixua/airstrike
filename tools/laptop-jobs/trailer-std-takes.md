# Задание ноутбуку: трейлер на 90 с, шаги 3–5 — дубли без ядерки

Ветка `claude/project-thread-m4caef` (zentixua/incoming), коммит — в брифе сессии. Порядок производства и что с каждым
планом — `/mnt/project-files/trailer-std/production.md` (у ноутбука его нет: нужное — здесь). Монтажный лист —
`tools/trailer/scenes/newisle-90.json`, дубли — `newisle-takes.json`, планы — `newisle.json`, позы — `newisle-poses.json`.
Конвейер — `tools/trailer/produce.py` (шапка файла), устройство и камни — `.claude/rules/trailer.md`, прежнее задание
ночи — `tools/laptop-jobs/newisle-p2.md` (машина `mod/run/newisle-laptop.json`, работа `mod/run/newisle-night`).

Твоя часть — дубли **T05** (n07, экран пульта), **T07** (n11, ущелье на восходе 23 500), **T14** (только ради n24,
B-2 по бункеру), **T21** (n41, n41b, ночной удар по Городу 1), **T23** (n30, n31, второй ключ и пуск МБР).
Ядерные дубли T24, T25 и живую сессию Артёма (T03 и T25) не трогать: они после PR ядерного следа, их начнёт тред.

## Правила
- Инстанс Артёма «Airstrike Pack», его миры и настройки не трогать. Его игра идёт — конвейер сам ждёт
  (`pause_if_running`); клиенты — только под замком видеокарты `mod/run/gpu.lock` (`gpu_lock` машины). Тред ядерки
  тоже снимает кадры на ноутбуке под тем же замком: замок занят — ждать, не снимать его.
- Рабочее дерево `/mnt/data/projects/airstrike` общее с другими сессиями: перед `checkout` — `git status` и
  `git worktree list`. Чужие незакоммиченные правки или идущий клиент другой задачи — не переключать ветку, а сказать мне.
- Снимки `mod/run/scenario/saves/nw-*` — входы будущих дублей (T03 — снимок T02, T25 — снимок T22, T07 — снимок T06):
  удалять только копии, созданные этим заданием. Свободного места должно хватать на копии пяти входов и их снимки (по
  размеру копии мира) без уборки конвейера: не хватает — стоп и сказать мне, а не чистить.
- Наружу — только текст отчёта и черновые кадры (ниже). Повторы и миры остаются на ноутбуке. Фильтр остановил шаг —
  точный шаг и сообщение мне, не обходить.

## 1. Готовность (отчёт мне сразу, ≤ 8 строк)
Игра Артёма (`pgrep -af 'java.*instances/Airstrike Pack'`), другие клиенты игры и службы `laptop_job.sh`
(`systemctl --user list-units --state=running | grep -i job`), кто держит `mod/run/gpu.lock` (`fuser`), свободно на
диске и в памяти, ветка и `git status` дерева, `produce.py status --config mod/run/newisle-laptop.json`, и есть ли
входы пересъёмки (`level.dat`): T05 — снимок T04, T07 — снимок T06, T14 — снимок T13, T21 — снимок T20, T23 —
снимок T22 (имена — `state.json` работы, `Conveyor.retake_input`).

## 2. Сборка
`git fetch origin claude/project-thread-m4caef && git checkout <SHA>`; `cd mod && ./gradlew build`. В машине
`newisle-laptop.json` — `commit` на этот SHA. Ветка уже с `main` (Incoming 3.1.0: настоящие названия оружия, первый шаг
ядерного следа).

## 3. Шаг 3: постановка до записи
- **n24.** Прошлый вывод: удар за блоками в 100 % кадров. Поза камеры (`newisle-poses.json`, n24) — заново лучом по
  блокам на мире входа T14 (`tools/trailer/ray.py <регионы> камера цель`, места — `scout.py`): бункер (`desert_bunker`)
  и воронка видны из камеры, B-2 входит в кадр сверху. Правка — коммитом в ветку.
- **Прогон вслепую** каждого дубля: своя машина `mod/run/newisle-blind.json` — копия `newisle-laptop.json` с
  `"work": ".../mod/run/newisle-blind"`, `"record": ["tools/trailer/film.sh", "record"]` (без шейдеров), `"render": null`,
  `"cut": false`, env `INCOMING_SIZE=640x360`, `INCOMING_FILM_LIVE=0`, а в `protected` — все входы из п. 1 (запись и
  удаление там конвейер тогда отказывается делать). Работа — копия `state.json` из `newisle-night` и `retake.txt` с
  этими дублями (дубль не `passed`/`chained` пересъёмка не берёт — тогда в копии состояния ему `retake_input` по
  `Conveyor.retake_input` вручную), запуск `produce.py run --config mod/run/newisle-blind.json --takes T05,T07,T14,T21,T23` через
  `tools/laptop_job.sh`. Чисто — `result.json` без провалов (`bound`, `eta`/`actual` в пределах секунды, `warnings`
  пусто), в `record.log` нет `SCENARIO film staging`, камеры не в блоках, нет `Can't keep up`. Не чисто — правка
  постановщика (`Newisle.java`, `NewisleData.java`) или данных, юнит-тесты (`./gradlew test`,
  `python3 -m unittest discover -s tools/trailer/tests`), прогон этого дубля снова; пуш в ветку.
- **Ущелье n11 на 23 500:** один вывод пробы 640×360 с шейдерами из повтора прогона (`film.sh render shaders`,
  `INCOMING_FILM_SHOTS=n11`, своя `INCOMING_FILM_OUT`): средняя яркость кадров удара не ниже 60 (как `brightness` в
  `cover`). Темнее — сказать мне с числами, время не менять самому.
- Работу `newisle-blind` и её снимки после шага 4 удалить.

## 4. Шаг 4–5: запись и камеры
Когда все пять чистые: в `mod/run/newisle-night` — `retake.txt` с T05, T07, T14, T21, T23, запуск
`produce.py run --config mod/run/newisle-laptop.json --takes T05,T07,T14,T21,T23` через `laptop_job.sh` (запись 1440p с
шейдерами: живые планы n07 и n30 снимаются в записи сразу набело). Вывод черновой, как в стандарте: в машине
`render` — `["env", "INCOMING_SIZE=1280x720", "tools/trailer/film.sh", "render"]` (без шейдеров), чистовой 1440p — потом,
только планы закрытого монтажа. Конвейер сам переснимает не прошедшее (до 2 раз) и пишет `best.txt`.
Ждать — Bash в фоне с циклом `until` по первой строке `status.txt` (срок — 2 ч, потом снова), без опроса меня.

## 5. Отчёт мне (send_message, ≤ 20 строк) и кадры
По плану (n07, n11, n24, n41, n41b, n30, n31): PASS или FAIL с причиной из `best.txt`, камера, сколько попыток; что
правил в постановке (коммиты); яркость n11; время записи и вывода. Черновые кадры для раскадровки — по одному JPEG
на план (середина окна плана, 1280×720, качество 85) именем `trailer-std-<план>.jpg` — файлами мне в проект так же,
как ты отдаёшь файлы треду.
