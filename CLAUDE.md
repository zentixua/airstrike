# Airstrike — мод NeoForge для Minecraft 1.21.1 (модпак «All of Create Aeronautics»)

Кинематографичные и при этом реалистичные удары: дрон-камикадзе (как Shahed-136), крылатая ракета,
B-2 с бетонобойной бомбой, залпы с разбросом, МБР с ядерной боевой частью; пульт наведения с биноклем,
точное наведение на всё, куда смотрит игрок (блоки, мобы, игроки, летательные аппараты Create Aeronautics),
свой 3D-звук с Доплером. Репозиторий: https://github.com/zentixua/airstrike (приватный).
Что умеет мод для игрока — README.md; проект ядерного удара и отступления от него — docs/DESIGN-nuke.md.

## Люди и правила общения
- Автор/хост: **Артём**, в игре **ZentixUA**. Друзья: **ENOTzRPG**, **WallyFillmark**.
- Общаемся по-русски, кратко и по делу.
- **Не управлять компьютером пользователя** (никакого computer-use, кликов в игре). Всё готовим так,
  чтобы оставалось поставить jar (`tools/deploy.sh`) и перезапустить игру.
- **Минимум просьб «проверь в игре»** — это мешает игре. Проверяем сами: юнит-тесты, GameTest, клиент без окна
  (`tools/client_scenario.sh`), после игры — `tools/logscan.py`.
- Не удалять миры/сейвы/файлы пользователя. Лишнее — переносить в сторону (`deploy.sh` кладёт в `airstrike-backup/`).
- Качество важнее скорости: перед сдачей — сборка, тесты, GameTest, ревью диффа.

## Где что лежит
```
/mnt/data/projects/airstrike/            ← этот проект (git)
  mod/                                   ← исходники мода (ModDevGradle 2, NeoForge 21.1.250, Parchment, Java 21)
    src/main/java/ua/zentix/airstrike/   ← код (пакеты — ниже)
    src/main/resources/                  ← assets (звуки, текстуры, lang ru/en), data (теги, типы урона), AT
    src/devtest/                         ← GameTest, шаблоны площадок, сценарий клиента (в jar не входят)
    src/test/                            ← юнит-тесты JUnit (звук, модель ядерного взрыва)
    scripts/gen_test_structures.py       ← шаблоны GameTest (pad, range, runway)
    run/<client|server|gametest|scenario>/  ← папки запусков (в .gitignore)
  tools/
    paths.py                             ← все пути к игре (единственное место)
    deploy.sh                            ← сборка → mods/ инстанса и dist/ (--test, --dry)
    logscan.py                           ← выжимка из logs/latest.log
    client_scenario.sh [all|nuke]        ← клиент без окна (KWin virtual + Xwayland), кадры и звук в WAV
    synth_mod_sounds.py, gen_textures.py ← генерация звуков (numpy + ffmpeg) и текстур (Pillow), фиксированный сид
  docs/DESIGN-nuke.md                    ← проект ядерного удара

~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/   ← Prism (tools/paths.py: PRISM)
  instances/All of Create Aeronautics/minecraft/                  ← .minecraft инстанса (MC)
    mods/                              ← моды; отсюда же build.gradle берёт Create/Sable/Aeronautics для запусков
    logs/latest.log                    ← лог клиента и встроенного сервера
    airstrike-backup/                  ← что deploy.sh убрал в сторону (старые jar, датапак, пакет звуков)
  java/java-runtime-delta              ← JDK 21 (JAVA_HOME для gradle)
```

## Рабочий цикл
```sh
export JAVA_HOME=~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd mod && ./gradlew build                 # компиляция (-Xlint:all без предупреждений) и юнит-тесты
cd mod && ./gradlew runGameTestServer     # GameTest: сервер без окна с Create 6.0.10, Sable 2.0.5, Aeronautics 1.3.2
tools/client_scenario.sh nuke             # клиент без окна: mod/run/scenario/screenshots/*.png, audio.wav, logs
tools/deploy.sh                           # jar → mods/ инстанса (Артём перезапускает игру) и dist/ (для друзей)
python3 tools/logscan.py [--since 18:30]  # после игры: ошибки мода, удары, подрывы, Sable
git commit
```
Моды для запусков (`run/*/mods`) копируются из инстанса задачами `copyRuntimeMods_*`; путь — `MC_DIR` или по умолчанию.
Сценарий клиента пишет строки `SCENARIO …` в лог (звуки, fps, вспышка) — по ним и по кадрам проверяется картинка и звук.

## Архитектура (пакеты `ua.zentix.airstrike`)
- `Airstrike` — регистрации и подписки на события (игровая шина); `AirstrikeConfig` — SERVER (мир) и CLIENT.
- `registry/` — сущности, предметы (пульт, счётчик Гейгера, тринитит), блоки, эффекты, attachments, звуки,
  теги, типы урона, компоненты данных (`Loadout` в пульте), вкладка творчества.
- `strike/` — приказы: `ServerActions` (пакеты пульта, права, отбой), `StrikeService` (пуск одного снаряда, сирена,
  строка в лог), `SalvoData` (залпы, SavedData), `StrikeWorld` (таймлайны взрывов + залпы в конце тика мира),
  `Loadout`/`WeaponType`/`TargetMode`, `ChunkTickets` (снаряды держат чанки впереди себя).
- `entity/` — `StrikeProjectile` (общий полёт: удержание высоты по рельефу, неконтактный взрыватель, столкновения),
  `DroneEntity`, `CruiseMissileEntity`, `BomberEntity` + `BunkerBusterEntity` (бурение), `IcbmEntity` (только разгон),
  `DebrisEntity` (обломки по баллистике). `guidance/FlightController` — повороты с ограничением скорости и ускорения.
- `target/` — `Target` (точка, сущность, аппарат Sable; кодек), `TargetPicker` (что под прицелом: аппарат → блок
  аппарата → сущность → блок), `TargetTracker`. `compat/SubLevels` — вся связь с Sable (через sable-companion,
  вшит jar-in-jar; сам Sable — compileOnly).
- `warhead/` — `Warheads` (ванильный `explode` со своим DamageSource и беззвучным звуком, подземный взрыв бомбы
  с ослабленным набором блоков, выбитые стёкла по палитрам секций, толчки), `DebrisSpawner`, `GroundMaterial`.
- `nuclear/` — ядерный удар:
  - `model/` — чистая физика без Minecraft (давление Кинни–Грэма, приход фронта, шар, гриб, свет, проникающая
    радиация, осадки, воронка, лучевая болезнь), юнит-тесты;
  - `Detonation` — один подрыв (всё, из чего сервер и клиенты выводят картинку и последствия), `NuclearEvents`
    (SavedData: подрывы и запланированные удары), `NuclearWarhead` (подрыв: свет и радиация по сущностям),
    `NuclearStrikes` (пуск МБР, таймер, события мира, синхронизация клиентам);
  - `world/` — `ScarQueue` (очередь чанков по времени прихода фронта, бюджет `time_budget_ms`), `ColumnScar`
    (столбец: давление ломает надземное, свет поджигает/выжигает, деревья валятся от эпицентра),
    `BlockResponse` (пороги по тегам `nuke_*` или прочности), `ThermalShadow` (тень по карте высот), `CraterJob`,
    `NuclearWorld` (фронт по сущностям и аппаратам, очереди); отметка чанка — attachment `CHUNK_SCAR`;
  - `radiation/` — доза игрока (attachment), `RadiationTicker` (раз в секунду: поле осадков × крыша + заражение),
    эффекты лучевой болезни и ожогов.
- `net/` — `S2C`/`C2S` пакеты; `ClientHooks` — интерфейс, который реализует клиент (сервер не грузит клиентские классы).
- `client/` (`@Mod(dist = CLIENT)`) — `render/` (модели из блоков, как display-сущности датапака), `sound/` (задержка
  звука и Доплер: решение уравнения запаздывания, `EngineSound`), `fx/` (вспышка, тряска камеры без сдвига прицела,
  частицы взрывов), `hud/`, `aim/Designator` (бинокль), `screen/RemoteScreen` (пульт), `nuclear/` (вспышка
  и послеобраз, небо и туман, шар и гриб, чёрный дождь, звук по приходу фронта, оглушение EFX, счётчик Гейгера,
  отсчёты и тревога, двухшаговый пуск).
- `legacy/LegacyMigration` — переезд со старого датапака: выключает `file/airstrike`/`file/shahed`, переносит
  `storage airstrike:cfg` в конфиг, убирает objectives и старые сущности с тегами.

## Единицы и масштаб
- Блок = метр. Скорости снарядов — блоки/тик (шахед 2.1, ракета 11.5, B-2 12, МБР до 25). Звук — 17.15 блока/тик.
- Ядерная модель — в метрах, секундах, килотоннах; `Detonation.blocks/metres` переводят с учётом `effects_scale`
  (`scale`): расстояния × scale, время фронта × scale (фронт в блоках всегда идёт со скоростью звука).
  Игровые часы осадков: 1000 тиков = 1 час.

## Подводные камни (выучено на практике)
- Клиент получает только карты высот `MOTION_BLOCKING` и `WORLD_SURFACE`; `…_NO_LEAVES` есть лишь на сервере —
  в клиентском коде (и в общем, что зовёт клиент) только `MOTION_BLOCKING`.
- GameTest ставит шаблон на 1 блок выше его начала; без `skyAccess = true` площадку накрывает потолок из барьеров
  (и рельеф для полёта — это потолок). Тесты идут на случайных дальних координатах; каждому ядерному тесту — своя партия.
- Мок-игрок GameTest (`makeMockServerPlayerInLevel`) ломается о пакеты Create («simulated:end_sea») — тестировать
  через методы, которым достаточно позиции и UUID (`NuclearStrikes.launchFrom`).
- Дальняя плоскость отсечения — `renderDistance × 4` блоков: гриб выше рисуется «сжатым» после неба (`NukeRenderer`).
- Пакет подрыва может прийти на тик раньше, чем часы клиента дойдут до `gameTime` подрыва — время не бывает < 0.
- Фильтр OpenAL (оглушение) ставится в `PlaySoundSourceEvent` (звуковой поток); `Channel.source` открыт AT
  (`META-INF/accesstransformer.cfg`, объявлен в `neoforge.mods.toml`). После перезапуска звукового движка фильтр — заново.
- Сервер может отставать от клиента по тикам (генерация мира): в сценарии клиента кадры привязаны к событиям, не к счётчику.
- `Locale.ROOT` для чисел в командах: у Артёма русская локаль, `String.format("%.1f")` даёт запятую.
- Экран приветствия доступности и пауза без фокуса ломают клиент без окна — `client_scenario.sh` пишет свой `options.txt`.

## Окружение
- NeoForge 21.1.250, Minecraft 1.21.1, ~217 модов. Мультиплеер: хост открывает мир через e4mc/Essential;
  jar мода нужен всем (сервер и клиенты).
- Важные моды: Create 6.0.10, **Create Aeronautics 1.3.2 + Sable 2.0.5** (аппараты — «sub-levels», блоки живут
  в далёком «плоте», их ломает ванильный взрыв через миксин Sable), Sodium, **Iris с шейдерами у хоста**, Essential, e4mc.

## Не сделано / идеи
- Ядерная БЧ на крылатой ракете и B-2 (DESIGN §7) — сейчас только МБР.
- Картинка под шейдерами Iris не проверена клиентом без окна (там Iris нет).
