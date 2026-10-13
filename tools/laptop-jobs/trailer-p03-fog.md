# Задание ноутбуку: туман под камерой в плане 3 (рассвет над Zearth)

Ветка `claude/project-thread-45f5nj` (zentixua/incoming), коммит — в сообщении координатора. Правила — как в
`trailer-film.md`: копия для съёмки и своё рабочее дерево, инстанс, сервер и миры Артёма не трогать, видеокарта — одна
задача и не во время его игры, тяжёлое — через `tools/laptop_job.sh`, удалять ничего нельзя без слова Артёма; мастер
Zearth — `mod/run/zearth-master` (только чтение). Наружу — только кадры и текстовые итоги.

План 3 по эталону: пролёт на рассвете на высоте 300 над туманом — город внизу в слое тумана, верхи небоскрёбов центра
(до y 255) над ним в первом солнце. Камера сценария: от (2830, 300, 290) к (2905, 296, 352) за 60 тиков, взгляд на
(3060, 200, 560), время 23300, облака — над камерой (`INCOMING_FILM_CLOUDS=320`). Туман под камерой у нас может дать
только шейдерпак (Complementary из инстанса). Задача — найти его настройки, которые дают такой слой, и показать кадры
кандидатов: выбор — по ним. Остальные планы выводятся с настройками пака по умолчанию; план 3 потом выводится отдельным
запуском со своими (`INCOMING_SHADER_OPTS` у `film.sh render` с `INCOMING_FILM_SHOTS=p03`).

## 1. Запись плана
```sh
export INCOMING_MODS_DIR=run/film-war/minecraft/mods INCOMING_SIZE=1920x1080 INCOMING_RENDER_DISTANCE=32 \
       INCOMING_TRAILER_DIR=fog-p03 INCOMING_LANG=en_us INCOMING_FILM_CLOUDS=320
cp -r --reflink=auto mod/run/zearth-master mod/run/scenario/saves/war2-p03 && chmod -R u+w mod/run/scenario/saves/war2-p03
INCOMING_WAR_PART=p03 INCOMING_WORLD=war2-p03 tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
```
В логе — `SCENARIO film dh generator DISABLED PRE_EXISTING_ONLY`, как у полной записи. Копию `war2-p03` держать до
конца вывода (повтор читает LOD DH из неё).

## 2. Настройки тумана пака
Пак — `mod/run/scenario/shaderpacks/<пак>` (`record.sh` копирует его из инстанса; имя — `shaderPack=` в
`config/iris.properties` инстанса). Найти по коду пака всё, что управляет туманом и дымкой: `shaders/shaders.properties`
(экраны настроек), `#define ИМЯ значение // [значения]` в `shaders/**/*.glsl`, подписи — `shaders/lang/en_us.lang`;
имена и подписи с fog, mist, haze, atmosphere, altitude, height. Где туман по высоте (его верх, плотность, утро и
погода) — прочитать код, который его считает.

## 3. Вывод с кандидатами
Сперва — по умолчанию, потом до шести наборов настроек из п. 2, которые по коду дают слой тумана с верхом между y 100
и 170 под камерой на 300, при ясной погоде. Каждый кандидат — свой вывод плана 3 из того же повтора:
```sh
for C in "default:" "<имя>:<ИМЯ=значение ИМЯ=значение>" …; do
  N="${C%%:*}" O="${C#*:}"
  INCOMING_SHADER_OPTS="$O" INCOMING_FILM_SHOTS=p03 INCOMING_FILM_TRIES=1 \
    tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
  mv mod/run/scenario/fog-p03/render "mod/run/scenario/fog-p03/render-$N"
done
```
У каждого кандидата — лист из трёх кадров вывода (первый, средний, последний) в ряд, по 640×360, одним JPEG до 1 МиБ.

Прислать: список настроек из п. 2 (имя, значения, по умолчанию, подпись, что делает по коду — в несколько слов);
по каждому кандидату — строку настроек, итог `render-<имя>/report.jsonl` по p03 и лист кадров.
