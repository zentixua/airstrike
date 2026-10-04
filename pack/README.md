# Airstrike Pack

Своя сборка под Airstrike: NeoForge 1.21.1, Create, Create Aeronautics, Distant Horizons, шейдеры и запись повторов.
Без контента не по теме: оружие и техника, Create для построек и аппаратов, всё лишнее для такой игры не берём.

Сборка описана файлами [packwiz](https://packwiz.infra.link/): `pack.toml` (версии игры и NeoForge), `mods/*.pw.toml` и
`shaderpacks/*.pw.toml` (откуда скачать каждый файл и его хеш), остальное — файлы, которые кладутся в игру как есть
(`config/`). Файл для лаунчера, `.mrpack`, собирает CI (артефакт `airstrike-pack`) или руками:

```sh
go install github.com/packwiz/packwiz@v0.0.0-20260906154125-ef87d964f8cb
cd pack && packwiz mr export      # → Airstrike Pack-<версия>.mrpack
```

## Установка

Сборка обновляется сама: перед каждым запуском игры Prism Launcher запускает
[packwiz-installer](https://packwiz.infra.link/tutorials/installing/packwiz-installer/), и тот ставит `pack/` с `main`
этого репозитория — новое докачивает, убранное из сборки удаляет. Свои файлы игрока (миры, настройки, моды не из
сборки) он не трогает; файл из `config/` сборки перезаписывает, только когда сборка его меняет.

Новый экземпляр: Prism → «Добавить экземпляр…» → «Импорт» → `Airstrike Pack.zip` (артефакт CI `airstrike-pack` или
`tools/prism_instance.py`). В нём Minecraft и NeoForge из `pack.toml`, 6144 МБ памяти, `packwiz-installer-bootstrap.jar`
в папке игры и команда перед запуском. Первый запуск скачивает моды и спрашивает про необязательные: запись (Flashback,
Sinytra Connector, Forgified Fabric API, Flashback NeoForge Fixed) нужна только тем, кто снимает; Distant Horizons
включён, на macOS его выключить (ниже).

Экземпляр, поставленный из `.mrpack`, переводится на автообновление так же, миры остаются:
1. Prism: «Папка» (папка игры экземпляра, там `mods` и `saves`): переименовать `mods` в `mods-old` и положить
   туда же [packwiz-installer-bootstrap.jar](https://github.com/packwiz/packwiz-installer-bootstrap/releases/latest).
2. «Изменить…» → «Параметры» → «Пользовательские команды»: включить и в «Предстартовая команда» вписать
   `"$INST_JAVA" -jar packwiz-installer-bootstrap.jar https://raw.githubusercontent.com/zentixua/airstrike/main/pack/pack.toml`
3. Запустить: packwiz-installer скачает моды сборки заново и спросит про необязательные. Свои моды не из сборки, если
   они были, вернуть из `mods-old` в `mods`.

`mods` в сторону — потому что удаляет packwiz-installer только то, что ставил сам: jar старой версии сборки с другим
именем (`airstrike-2.5.0.jar` у 0.1.0) остался бы рядом с новым, и игра не запустилась бы с двумя Airstrike. Файлы
`config/` сборки на первом запуске он перезаписывает, если они отличаются.

Окно packwiz-installer на каждом запуске: «Continue» — сразу в игру (иначе само через 10 с), «Optional mods...» —
включить или выключить запись и Distant Horizons. Галочки модов сборки на странице «Моды» Prism не менять: убранный
или выключенный мод packwiz-installer вернёт. Если сборка сменит версию Minecraft или NeoForge, он спросит, обновить
ли их в экземпляре. Файлы сборки GitHub раздаёт с кэшем до 5 минут: запуск сразу после слияния PR сборки может застать
`pack.toml` и `index.toml` разных коммитов, и packwiz-installer скажет про неверный хеш индекса — «Quit» и запустить
снова позже.

`.mrpack` (`packwiz mr export`, артефакт CI) — разовая установка без автообновления: «Импорт» этого файла, Prism сам
спросит про необязательные моды. Галочек по умолчанию в формате Modrinth нет, и Prism предлагает их все выключенными:
Distant Horizons отметить (кроме macOS).

## Что внутри

| группа | моды |
|---|---|
| основа | Create, Sable, Create Aeronautics, Airstrike (jar выпуска с GitHub), Distant Horizons (по выбору, включён) |
| производительность | Sodium, Sodium Extra, Reese's Sodium Options, Iris, Colorwheel, Lithium, FerriteCore, ModernFix, ImmediatelyFast, Entity Culling, More Culling (+ Cloth Config), Dynamic FPS |
| Create | Propulsion: Simulated, Aeroworks, Tweaked Controllers, Big Cannons (+ RPL), Connected, Deco, Diesel Generators, Aeronautics Hot Air Fix |
| авиация и оружие | Immersive Aircraft, Man of Many Planes, Vic's Point Blank (+ GeckoLib), Point Blank Aeronautics compat |
| картинка и камера | шейдерпак Complementary Reimagined, Euphoria Patches, Aeronautics Camera Sync, 3D Skin Layers, Not Enough Animations |
| мультиплеер и удобство | Essential, TrueUUID (вход на сервер, ниже), LAN Server Properties (временно, ниже), JEI (+ MezzConfig), Jade, Jade Sable Compat, Xaero's Minimap и World Map (+ MapSyncer: вся карта мира с сервера), Create – Xaero's map, GraveStone и его патч для Sable, Mouse Tweaks, No Chat Reports |
| запись (по выбору) | Flashback, Flashback NeoForge Fixed, Sinytra Connector, Forgified Fabric API |

LAN Server Properties — временно, чтобы в мир хоста мог зайти игрок без входа в аккаунт Microsoft; уберём по слову
Артёма. Хост: пауза → «Открыть для сети» → «Проверка лицензии»: «Без проверки лицензии + исправление UUID» → «Открыть
мир для сети»; заходят по адресу хоста в Tailscale и порту из этого окна. Исправление UUID оставляет игрокам с лицензией
их UUID (инвентарь и достижения в мире те же), игрок без лицензии получает свой. Порт мод не пробрасывает (UPnP у него
нет), проверка лицензии по умолчанию включена; «Сохранить настройки» не нажимать — иначе мир будет открываться без
проверки и дальше.

Сервер сборки (выделенный, на VPS хоста) работает без проверки лицензии сервером (`online-mode=false`), а проверяет её
TrueUUID — он стоит и на сервере, и у каждого игрока. При входе сервер посылает клиенту одноразовый вызов, клиент
отвечает через `joinServer` своей сессии Mojang (токен остаётся у игрока), сервер сверяет ответ с `hasJoined`: игрок
с лицензией входит со своим UUID и скином. Без лицензии пускается только клиент, который прямо отвечает «сессии нет»,
и только под ником, который ещё ни разу не входил с лицензией (`knownPremiumDenyOffline`,
`allowOfflineForUnknownOnly` — значения по умолчанию): под ником игрока с лицензией без неё не войти. Моды сервер
ставит из этой же `pack/` (`packwiz-installer-bootstrap -g -s server`), поэтому `side` у модов должен быть верным:
`client` сервер не ставит.
Мод ведущего (`airstrike-gm`, из выпуска Airstrike 2.9.0) — `side = "server"`: через него Claude ведёт игру на сервере,
клиентам он не нужен и у них не ставится. Его мост слушает только адрес из `config/airstrike_gm-common.toml` с ключом
из `config/airstrike_gm/token` (устройство — `.claude/rules/gm.md`).

Карта мира на сервере. В одиночке Xaero's World Map читает карту прямо из файлов мира, а в мультиплеере рисует только
чанки, которые сервер прислал игроку. Всю карту мира игрокам отдаёт MapSyncer (стоит на сервере и у каждого игрока):
сервер сам переводит файлы регионов мира в формат карты Xaero и при входе присылает игроку недостающие и изменённые
регионы. Xaero's World Map и Minimap стоят и на сервере: так у каждого мира свой идентификатор, и карты и метки
миров, которые по очереди запускаются на одном адресе, не сливаются в одну.
На сервере для каждого мира один раз, в консоли: `mapsyncer incremental scheduled` (карта сервера обновляется раз в
сутки в 04:00 по часам сервера, клиенты при входе забирают обновлённое) и `mapsyncer generate` (вся карта сразу;
ход — `mapsyncer status`). Обновление раз в сутки, а не каждые несколько минут: перед каждым проходом MapSyncer
сохраняет мир целиком на диск в потоке сервера.

Distant Horizons на macOS (Apple Silicon) выключать: DH 3.3.3 запрашивает `GL_POLYGON_MODE` через `glGetInteger`
(место под одно число на стеке LWJGL), а драйвер OpenGL от Apple пишет два — каждый кадр на 4 байта за конец буфера.
Портится соседняя память, и Java падает (`SIGSEGV` в `Chunk::chop`, компилятор JIT). В `main` DH этот запрос тот же,
падения на macOS у них — открытая [#1258](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1258).
Выключенный на странице «Моды» Prism мод packwiz-installer вернёт, поэтому DH — необязательный мод сборки: галочка
в окне «Optional mods...». Игре по сети его отсутствие у игрока не мешает: пакеты DH зарегистрированы как
необязательные (`PayloadRegistrar.optional()`).

Настройки, которые сборка кладёт сама:
- `config/entityculling.json` — значения Entity Culling по умолчанию плюс сущности Airstrike в `entityWhitelist`: такие
  сущности мод не прячет и не останавливает их тик на клиенте (в 1.11.2 этот список действует на оба). Факел снаряда
  рисуется вместе с моделью, а шлейф, облако пуска и дым горящих обломков рождаются в тике сущности на клиенте
  (`AirstrikeClient.entityTick`): без исключения снаряд за холмом или за спиной игрока терял бы факел и рвал шлейф.
- `config/aero_cam_sync-client.toml` — значения Aeronautics Camera Sync по умолчанию и `configSchemaVersion = 5`.
  Версия 1.4.0 сама пишет новый файл с версией схемы 0, а на следующем запуске считает его устаревшим и вместо
  главного меню открывает вопрос «Config Reset» (`ConfigMigrationManager`: файл был и схема меньше 5) — до первого
  нажатия «Reset» или «Ignore», которые ставят 5. С файлом сборки вопроса нет. Обновляя мод, сверить его версию схемы.
- `config/moreculling.toml` — `useBlockStateCulling = false`, остальное More Culling дописывает сам (и, как у нового
  файла, переводит дальности рамок в блоки). Рамки он рисует сам: без задней стенки и карт, к которым игрок стоит
  спиной, у блока в рамке — три видимые грани, у плоского предмета дальше 16 блоков — только лицевая сторона
  (торец в 3 см). Отсечение граней блоков выключено: оно отвечает за `Block.shouldRenderFace` в его начале и перебивает
  правку Aeronautics, по которой грань у левитита рисуется (`levitite/BlockMixin`), а даёт оно меньше вершин видеокарте,
  которая в замере 03.10.2026 была занята на 34 %, пока поток отрисовки стоял на 100 %.

## Как обновлять

- Мод с Modrinth: `packwiz update <имя>` (или `packwiz modrinth add --project-id <slug> --version-id <id>` — точная
  версия), потом `packwiz refresh`. CI проверяет, что `index.toml` свежий.
- Новый выпуск Airstrike: `packwiz url add Airstrike https://github.com/zentixua/airstrike/releases/download/v<версия>/airstrike-<версия>.jar --meta-name airstrike`.
- Версию сборки (`version` в `pack.toml`) поднимать при каждом изменении состава: у всех игроков должен быть один набор.
- `pack/` на `main` игроки ставят при следующем запуске игры: в `main` — только проверенное CI и только jar Airstrike
  из уже вышедшего выпуска (PR сборки — после релиза).

## Версии

| версия | Airstrike | что изменилось |
|---|---|---|
| 0.1.11 | 2.9.0 | Airstrike 2.9.0 ([заметки](../docs/releases/2.9.0.md)): боеприпасы, разведка, точность, Отбой своих, ядерный удар без оператора, ЗРК; мод ведущего 0.1.2 на сервере; остальные моды и настройки те же |
| 0.1.10 | 2.8.1 | Мод ведущего airstrike-gm 0.1.0 ([заметки](../docs/releases/2.8.2.md)), только на сервере: игрокам ничего не скачивается |
| 0.1.9 | 2.8.1 | Airstrike 2.8.1 ([заметки](../docs/releases/2.8.1.md)): фиксы по итогам игры 03.10; остальные моды и настройки те же |
| 0.1.8 | 2.8.0 | MapSyncer: вся карта мира с сервера в Xaero's World Map; Xaero's World Map и Minimap — и на сервере |
| 0.1.7 | 2.8.0 | TrueUUID: вход на выделенный сервер сборки с лицензией и без неё |
| 0.1.6 | 2.8.0 | Airstrike 2.8.0 ([заметки](../docs/releases/2.8.0.md)); остальные моды и настройки те же |
| 0.1.5 | 2.7.0 | More Culling (+ Cloth Config): рамки с предметами дешевле для потока отрисовки |
| 0.1.4 | 2.7.0 | Distant Horizons — необязательный (по умолчанию включён): на macOS его выключают, DH 3.3.3 там роняет игру |
| 0.1.3 | 2.7.0 | Airstrike 2.7.0 ([заметки](../docs/releases/2.7.0.md)); остальные моды и настройки те же |
| 0.1.2 | 2.6.0 | автообновление (packwiz-installer); LAN Server Properties — вход без лицензии в мир хоста, временно |
| 0.1.1 | 2.6.0 | Airstrike 2.6.0 ([заметки](../docs/releases/2.6.0.md)); остальные моды и настройки те же |
| 0.1.0 | 2.5.0 | первая версия |
