---
paths:
  - "tools/**"
  - "pack/**"
  - "mod/scripts/**"
  - ".github/**"
  - "mod/build.gradle"
  - "mod/gradle.properties"
---

# Где что лежит — подробно

Краткая раскладка — в корневом CLAUDE.md; здесь — что умеет каждый скрипт.

```
/mnt/data/projects/airstrike/            ← этот проект (git)
  mod/                                   ← исходники мода (ModDevGradle 2, NeoForge 21.1.250, Parchment, Java 21)
    src/main/java/ua/zentix/airstrike/   ← код (пакеты — в корневом CLAUDE.md)
    src/main/resources/                  ← assets (звуки, текстуры, lang ru/en), data (теги, типы урона), AT
    src/devtest/                         ← GameTest, шаблоны площадок, сценарий клиента (в jar не входят)
      …/gametest/scenario/               ← сценарии полёта: оружие × цель × возмущение × зерно, свойства полёта,
                                           эталон траекторий src/devtest/resources/scenario-baseline.json
    src/test/                            ← юнит-тесты JUnit (звук, модель ядерного взрыва)
    scripts/gen_test_structures.py       ← шаблоны GameTest (pad, range, runway; floor мода ведущего)
    gm/                                  ← мод ведущего airstrike_gm (только сервер): мост Claude к серверу, .claude/rules/gm.md
    run/<client|server|gametest|scenario>/  ← папки запусков (в .gitignore)
  tools/
    paths.py                             ← все пути к игре (единственное место)
    gm.py mcp|call|follow                ← ведущий: MCP-сервер для Claude Code над мостом мода airstrike_gm, вызов метода,
                                           лента событий построчно (для Monitor); адрес и токен — AIRSTRIKE_GM_URL, _TOKEN(_FILE)
    fetch_runtime_mods.py                ← Create/Sable/Aeronautics/Lithium с Modrinth (sha512) — для CI и облака без инстанса
    pack_dir.py <каталог> [--optional]   ← каталог игры из pack/ (моды по хешам, config/) — для prod_client.py --no-copy
    prism_instance.py [zip]              ← экземпляр Prism со сборкой, которая обновляется сама: перед запуском packwiz-installer
                                           ставит pack/ с main (артефакт CI airstrike-pack)
    deploy.sh                            ← сборка → mods/ инстанса и dist/ (--test, --dry; --jar F — готовый jar CI/релиза);
                                           инстанс с автообновлением сборки не трогает: packwiz-installer вернул бы jar сборки
    logscan.py                           ← выжимка из logs/latest.log
    client_scenario.sh <сценарий> [shaders] [dh] ← клиент без окна (KWin virtual + Xwayland), кадры и звук в WAV;
                                           сценарии и что снимает каждый — в шапке скрипта
    nested_kwin.sh                       ← вложенный KWin для клиента: без окна и без звука хоста, своя шина D-Bus без запуска служб
                                           (nested_kwin_bus.conf) и каталоги XDG
    laptop_job.sh <имя> -- <команда>     ← тяжёлая задача на ноутбуке хоста: своя временная служба systemd (не в группе Claude),
                                           ноутбук не засыпает, по выходу гасится всё её
    laptop-jobs/<имя>.md                 ← задания ноутбуку: координатор отправляет файл по SHA коммита, ноутбук сверяет sha256;
                                           отработанные удаляются (история — в git)
    logtime.py                           ← время строк лога Minecraft для выжимок заданий ноутбука
    free_port.py                         ← свободный порт на 127.0.0.1 для серверов проверок (stress.sh, mp_scenario.sh)
    rig_procs.sh                         ← процессы проверок в своих сессиях (source из stress.sh, mp_scenario.sh): выход скрипта гасит их с JVM
    test_rig_procs.sh                    ← проверка rig_procs.sh (CI): Ctrl+C, TERM, конец скрипта, чужая сессия, fd, срок RIG_GRACE — без сирот
    gametest_durations.py <лог>...       ← время партий GameTest по логам (latest.log или лог задачи CI) → таблица, по которой
                                           CI делит GameTest на части (src/devtest/resources/gametest-durations.json)
    mp_scenario.sh                       ← мультиплеер без окон: сервер и два клиента (Alpha бьёт, Bravo — цель), выходы и входы посреди удара
    prod_client.py <сценарий> [--world …] ← боевой клиент со всей сборкой хоста (копия инстанса, без Prism): сценарий из
                                           ./gradlew scenarioJar (build/scenario-libs, в релиз не попадает); nuke-profile — замер подрыва;
                                           strike-profile — тики сервера вокруг ударов и телепортов (--prop airstrike.profile.steps=…);
                                           leak — кто держит мир после выхода в меню: заходы в мир с командами (airstrike.leak.*),
                                           в меню — гистограмма классов и выборка JFR с путями до корней (нужен -XX:VMThreadStackSize=8192:
                                           со стеком по умолчанию поиск путей ронял JVM), в лог — строки `SCENARIO leak … holder`;
                                           replay — повтор Flashback в каталоге pack_dir.py с модами записи и quicksave: сохранился, открылся и проигрался — кадры снаряда и места, где он пропал; перемотки назад и вперёд через удары и ядерку — без живых взрывов и вспышек
    x11_record.py                        ← окно клиента во вложенном KWin — в видео в реальном времени (ffmpeg x11grab) с отметками
                                           времени для звука audio.wav (prod_client.py --video, --size)
    stress.sh                            ← стенд нагрузки (облако, xvfb): выделенный сервер с режиссёром (src/devtest/.../stress) и три игрока
                                           без окна; залпы по 30, выход/вход, Незер, ядерка, «Отбой»; сводка — строки STRESS в логе
    build_sounds.py [разделы]            ← все звуки: записи CC0/CC BY с Freesound (кэш tools/.sound-cache) + синтез
                                           synth_mod_sounds.py; пишет sounds.json и SOUND-CREDITS.md (numpy, scipy, soundfile);
                                           взрывы и петли моторов — из оригиналов без потерь, громкость по EBU R128 (ракурсы
                                           взрывов — ровно на `BlastMix`, петли — тише взрыва своего снаряда, ровность проверяется)
    freesound.py login|fetch             ← оригиналы Freesound (OAuth2): вход вне репозитория (~/.config/airstrike), файлы —
                                           tools/.sound-cache/orig; скачанные вход не требуют
    gen_textures.py                      ← текстуры (Pillow), фиксированный сид
    gen_particles.py                     ← текстуры частиц эффектов (textures/fx/particle), факела и дальних спрайтов (numpy + Pillow)
    gen_models.py                        ← модели снарядов: сетки OBJ + текстуры (numpy + Pillow) и упрощённые копии
                                           деталей `_lod1` для моделей вдали, не править OBJ руками
    gen_grid_assets.py                   ← модели и состояния двойников ламп блэкаута из ванильного jar, не править руками
    trailer/record.sh [shaders]          ← трейлер: сценарий клиента trailer снимает планы покадрово в 60 fps
                                           (AIRSTRIKE_SIZE=1920x1080, кадры и журнал звуков — mod/run/scenario/trailer/)
    trailer/edit.py [--lang en|ru] [--draft] [--rec …] ← монтаж под музыку (Kevin MacLeod, CC BY), титры, звук из журнала →
                                           dist/airstrike-trailer.mp4, -lite.mp4 и -credits.txt (строки для описания ролика)
    trailer/icon_from_frames.py <кадры> <папка> ← иконка мода из плана «icon» (шахед на фоне неба): 512 и малый вариант
  pack/                                  ← своя сборка «Airstrike Pack» (packwiz: pack.toml, mods/*.pw.toml, config/);
                                           .mrpack — `packwiz mr export` или артефакт CI `airstrike-pack`; состав — pack/README.md;
                                           pack/ на main игроки с автообновлением ставят при каждом запуске игры
  docs/DESIGN-nuke.md                    ← проект ядерного удара
  .github/workflows/build.yml            ← CI: что изменилось → сборка и юнит-тесты, GameTest частями, сборка модов, итог; jar, .mrpack и экземпляр Prism в артефактах; релиз
  docs/releases/<версия>.md              ← заметки к релизу
```
