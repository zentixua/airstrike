---
paths:
  - "tools/**"
  - "mod/scripts/**"
  - ".github/**"
  - "mod/build.gradle"
  - "mod/gradle.properties"
---

# Где что лежит — подробно

Краткая раскладка — в корневом CLAUDE.md; здесь — что умеет каждый скрипт.

```
/mnt/data/projects/airstrike/            ← этот проект (git)
  mod/                                   ← исходники мода (ModDevGradle 2, NeoForge 21.1.259, Parchment, Java 21)
    src/main/java/ua/zentix/incoming/   ← код (пакеты — в корневом CLAUDE.md)
    src/main/resources/                  ← assets (звуки, текстуры, lang ru/en), data (теги, типы урона), AT
    src/devtest/                         ← GameTest, шаблоны площадок, сценарий клиента (в jar не входят)
      …/gametest/scenario/               ← сценарии полёта: оружие × цель × возмущение × зерно, свойства полёта,
                                           эталон траекторий src/devtest/resources/scenario-baseline.json
    src/test/                            ← юнит-тесты JUnit (звук, модель ядерного взрыва)
    scripts/gen_test_structures.py       ← шаблоны GameTest (pad, range, runway)
    run/<client|server|gametest|scenario>/  ← папки запусков (в .gitignore)
  tools/
    paths.py                             ← все пути к игре (единственное место)
    fetch_runtime_mods.py                ← Create/Sable/Aeronautics/Lithium с Modrinth (sha512) — для CI и облака без инстанса
    deploy.sh                            ← сборка → mods/ инстанса и dist/ (--test, --dry; --jar F — готовый jar CI/релиза);
                                           инстанс с автообновлением сборки не трогает: packwiz-installer вернул бы jar сборки
    logscan.py                           ← выжимка из logs/latest.log
    client_scenario.sh <сценарий> [shaders] [dh] ← клиент без окна (KWin virtual + Xwayland), кадры и звук в WAV;
                                           сценарии и что снимает каждый — в шапке скрипта
    nested_kwin.sh                       ← вложенный KWin для клиента: без окна и без звука хоста, своя шина D-Bus без запуска служб
                                           (nested_kwin_bus.conf) и каталоги XDG
    laptop_job.sh <имя> -- <команда>     ← тяжёлая задача на ноутбуке хоста: своя временная служба systemd (не в группе Claude),
                                           ноутбук не засыпает, по выходу гасится всё её
    laptop-jobs/<имя>.md                 ← задания ноутбуку и netcup (netcup-…): координатор отправляет файл по SHA коммита,
                                           машина сверяет sha256; отработанные удаляются (история — в git)
    logtime.py                           ← время строк лога Minecraft для выжимок заданий ноутбука
    free_port.py                         ← свободный порт на 127.0.0.1 для серверов проверок (stress.sh, mp_scenario.sh)
    rig_procs.sh                         ← процессы проверок в своих сессиях (source из stress.sh, mp_scenario.sh): выход скрипта гасит их с JVM
    test_rig_procs.sh                    ← проверка rig_procs.sh (CI): Ctrl+C, TERM, конец скрипта, чужая сессия, fd, срок RIG_GRACE — без сирот
    gametest_durations.py <лог>...       ← время партий GameTest по логам (latest.log или лог задачи CI) → таблица, по которой
                                           CI делит GameTest на части (src/devtest/resources/gametest-durations.json)
    mp_scenario.sh                       ← мультиплеер без окон: сервер и два клиента (Alpha бьёт, Bravo — цель), выходы и входы посреди удара
    prod_client.py <сценарий> [--world …] ← клиент со всей сборкой хоста, как у игрока (копия инстанса, без Prism): сценарий из
                                           ./gradlew scenarioJar (build/scenario-libs, в релиз не попадает); nuke-profile — замер подрыва;
                                           strike-profile — тики сервера вокруг ударов и телепортов (--prop incoming.profile.steps=…);
                                           leak — кто держит мир после выхода в меню: заходы в мир с командами (incoming.leak.*),
                                           в меню — гистограмма классов и выборка JFR с путями до корней (нужен -XX:VMThreadStackSize=8192:
                                           со стеком по умолчанию поиск путей роняет JVM), в лог — строки `SCENARIO leak … holder`;
                                           replay — повтор Flashback в каталоге tools/pack_dir.py сборки (zentixua/open-skies) с модами записи и quicksave: сохранился, открылся и проигрался — кадры снаряда и места, где он пропал; перемотки назад и вперёд через удары и ядерку — без живых взрывов и вспышек
    replayscan/                          ← разбор повтора Flashback самой игрой в таблицы: мод клиента без окна (события, пути,
                                           видимость глазами игрока или заданных камер, замеры земли у взрывов) и replaydb.py —
                                           база DuckDB с запросами под планы роликов (пуски, пролёты, удары по земле); съёмка планов
                                           видео Flashback с проверкой каждого кадра (-Dreplayscan.film); README.md там
    clips/                               ← короткие ролики по данным повторов: moments.py (кандидаты), plan.py (планы съёмки),
                                           edit.py (монтаж, подписи, проверка вспышек по кадрам); README.md там
    guide_images.sh [кадры]              ← картинки руководства docs/guide/img из кадров `client_scenario.sh guide`: рецепты
                                           по месту окна верстака, остальное — анимации из серий: экраны GUI — GIF (экран
                                           пульта — вырезом окна), мир — анимированный WebP (втрое-вчетверо легче GIF);
                                           первый кадр и длина каждой — числами в скрипте, нет серии — анимация не трогается
    x11_record.py                        ← окно клиента во вложенном KWin — в видео в реальном времени (ffmpeg x11grab) с отметками
                                           времени для звука audio.wav (prod_client.py --video, --size)
    stress.sh                            ← стенд нагрузки (облако, xvfb): выделенный сервер с режиссёром (src/devtest/.../stress) и три игрока
                                           без окна; залпы по 30, выход/вход, Незер, ядерка, «Отбой»; сводка — строки STRESS в логе
    build_sounds.py [разделы]            ← все звуки: записи CC0/CC BY с Freesound (кэш tools/.sound-cache) + синтез
                                           synth_mod_sounds.py; пишет sounds.json и SOUND-CREDITS.md (numpy, scipy, soundfile);
                                           взрывы и петли моторов — из оригиналов без потерь, громкость по EBU R128 (ракурсы
                                           взрывов — ровно на `BlastMix`, петли — тише взрыва своего снаряда, ровность проверяется)
    freesound.py login|fetch             ← оригиналы Freesound (OAuth2): вход вне репозитория (~/.config/incoming), файлы —
                                           tools/.sound-cache/orig; скачанные вход не требуют
    gen_textures.py                      ← текстуры (Pillow), фиксированный сид; у каждой группы свой генератор
                                           случайных чисел, новая — в конец (прежние файлы не меняются)
    gen_particles.py                     ← текстуры частиц эффектов (textures/fx/particle), факела и дальних спрайтов (numpy + Pillow)
    gen_models.py                        ← модели снарядов: сетки OBJ + текстуры (numpy + Pillow) и упрощённые копии
                                           деталей `_lod1` для моделей вдали, не править OBJ руками
    gen_grid_assets.py                   ← модели и состояния двойников ламп блэкаута из ванильного jar, не править руками
    trailer/record.sh [shaders]          ← трейлер: сценарий клиента trailer снимает планы покадрово в 60 fps
                                           (INCOMING_SIZE=1920x1080, кадры и журнал звуков — mod/run/scenario/trailer/)
    trailer/edit.py [--lang en|ru] [--draft] [--rec …] [--take план=папка] [--music F] ← монтаж «Войны двух городов» по планам pNN[буква]
                                           под «Juggernaut» (Scott Buckley, CC BY; файл — --music, не скачивается), склейки
                                           на доли, неснятый план — заглушка; титры, звук из журнала →
                                           dist/incoming-trailer.mp4, -lite.mp4 и -credits.txt (строки для описания ролика)
    trailer/montage.py <план.json> [--take план=папка] [--check] [--draft] [--music F] ← монтаж от событий дублей: план
                                           — данные (схема в начале файла), склейка держит свою цепочку (пуск, полёт,
                                           удар, приход звука), звук через склейку (J/L) до обрыва в тишину, музыка
                                           на тактах edit.py; до картинки — проверка verdict.py (FAIL или нет дубля —
                                           стоп), --check — только отчёт; громкость −14 LUFS, пик ≤ −1 dBTP (loudness.py)
    trailer/loudness.py                  ← громкость BS.1770-4 и истинный пик на numpy, ограничитель и `master`
    trailer/icon_from_frames.py <кадры> <папка> ← иконка мода из плана «icon» (шахед на фоне неба): 512 и малый вариант
    trailer/film.sh record|render [shaders] ← трейлер из повторов: запись планов повторами Flashback с журналом, вывод
                                           камерами по журналу (таблица Coverage) с проверкой каждого кадра по данным игры
    trailer/report.py <папка>/render [--md F] ← отчёт по планам: вывод из повторов и живые планы с проверкой (hud.jsonl
                                           папки записи) — камера, итог проверки, доли кадров (мир, объект до удара,
                                           последняя секунда, удар, мотор, кадры в дыму) и причина
    trailer/sound_audit.py <таблица склеек> <корень> ← звуки в дублях монтажа: сколько выстрелов, моторов, ударов, сирен
                                           в журнале каждого дубля и далеко ли записывающий игрок от людей плана
    trailer/sound_compare.py <timeline живого клиента> <запись> <план> ← сверка звука мира (`<план>.sounds.jsonl`) с
                                           живым клиентом в точке: совпало, пропало, лишнее, разница в дБ; код 1 — пропало слышимое
    trailer/zmap.py <регионы> <png>      ← карта сверху и высоты по файлам регионов (места камер)
    trailer/ray.py <регионы> камера цель ← луч по блокам из камеры на цель и сетка лучей кадра: видна ли цель плана
    trailer/mapstudy.py <регионы> <папка> ← изучение карты по регионам: районы, высотки, улицы, метро и станции (шахты
                                           без начал — по крепи и паутине), мосты, вода, вершины и что с них видно;
                                           сводка, картинки сверху, высоты top/ground/water.npy
    trailer/scout.py <сценарий> <mapstudy> <папка> ← места камер планов по замыслам (крыша, земля, вершина, улица, путь,
                                           точка) с лучом к цели по высотам; --regions --previs — черновые кадры
                                           лучами по блокам и листы; сценарий Newisle — trailer/scenes/newisle.json
    trailer/animatic.py <лист> <scout> <mp4> ← аниматик: черновые кадры планов по монтажному листу (длины в секундах или
                                           тактах) под куски трека, подписи и заставки; лист Newisle — scenes/newisle-animatic.json
    trailer/produce.py run|live|search|night|status --config МАШИНА.json ← конвейер трейлера Newisle без человека:
                                           дубли scenes/newisle-takes.json по порядку истории (постановщик
                                           scenario/trailer/Newisle.java), снимок мира после каждого, проверка по данным,
                                           вывод, пересъёмка запасной камерой (не больше двух), пропуск; ограждения
                                           (пауза на диске, памяти и игре Артёма, сторож клиента, отказ писать вне папок
                                           работы, «stopped, waiting» на неожиданном), статус в пять строк; `night` —
                                           проба трёх дублей с порчей, потом вся цепочка; задания — laptop-jobs/
                                           netcup-newisle-p1.md, newisle-p2.md; тесты — tools/trailer/tests
    trailer/newisle_cut.py --work ПАПКА [--draft|--final] ← монтаж Newisle по листу scenes/newisle-animatic.json из
                                           снятого конвейером (PASS из best.txt, живые записи); неснятое — заливкой
                                           в черновике, чистовой ждёт все планы
    trailer/drift.py <запись>            ← у каких планов снаряд в журнале записи ушёл с пути (очередь мест до 22b2cfd)
    trailer/cut.sh [аргументы edit.py]   ← монтаж из всего снятого: у плана — PASS из самого сильного вывода (CUT_RENDERS),
                                           нет PASS — не прошедший, живые планы — из папок записи (CUT_RECS)
    shot/shot.py plan|check|demo         ← камера плана по сцене до игры: карта (регионы) + журнал плана → путь тела камеры
                                           (FPV, дрон, штатив, следящая) по одной мере с проверкой кадров, черновой кадр
                                           лучами; `check` — мера против qa.jsonl вывода; README.md там
  docs/DESIGN-nuke.md                    ← проект ядерного удара
  docs/guide/                            ← руководство игрока по-английски (страницы по темам, картинки в img/),
                                           docs/guide/ru/ — то же по-русски
  docs/store/                            ← тексты страниц Modrinth и CurseForge
  .github/workflows/build.yml            ← CI: что изменилось → сборка и юнит-тесты, GameTest частями, итог; jar в артефактах; релиз
  docs/releases/<версия>.md              ← заметки к релизу
```
