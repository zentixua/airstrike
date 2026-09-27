# Handoff: перевод Airstrike из датапака в мод NeoForge

Дата: 27.09.2026. Исследование: сессия Claude Code (локальная машина Артёма).
Правила общения и работы — в `CLAUDE.md` (по-русски, кратко; не управлять компьютером; минимум просьб «сделай /reload»).

## 1. Задача (предложение Артёма)

Перевести дополнение из датапака в мод, чтобы сделать его:
- **реалистичнее и кинематографичнее**;
- **удобнее (UX/UI)**: максимально понятный и удобный пуск боеголовок и дронов;
- **чище по реализации**: архитектура, код;
- в целом **круче**.

## 2. Итог исследования и статус

**Вывод: да, в мод — но поэтапно.** Датапак остаётся рабочей версией, пока мод его не догонит. После этого
датапак уходит на покой (история остаётся в git).

| Что | Статус |
|---|---|
| Исследование | готово (этот файл) |
| Друзья (ENOTzRPG, WallyFillmark) согласны ставить jar и обновлять его | **не подтверждено** — спросить Артёма |
| Шаг 0 (пробный запуск, см. §8) | следующий, ещё не начат |
| Репозиторий | GitHub `zentixua/airstrike` (приватный) |

## 3. Почему датапак упёрся в потолок (факты из кода и логов)

- **Объём:** 287 `.mcfunction`, 3043 строки. По подсистемам (файлов/строк): mfx 33/357, bfx 35/338, bunker 32/299,
  snd 25/297, fly 10/293, fx 31/223, debris 22/218, missile 16/178, drone 13/139, salvo 14/136, menu 8/54, ray 7/53.
  58 файлов с макро-строками, 195 селекторов `@e[`, 70 `data get entity`.
- **Звук:** звук собирается из моно-кусочков по 0.6 с раз в 3 тика; Доплер помнит 4 источника на игрока (ячейка = id%4);
  два свиста ракет перебивают друг друга (`stopsound` глушит все копии звука).
- **Тряска:** `fx/shake` делает `tp @s ~ ~ ~ ~±5 ~±3.5` каждый тик — дёргается настоящий взгляд игрока, сбивается прицел.
- **Отладка:** ошибки выполнения функций в лог не пишутся. Каждый `/reload` в этой сборке замораживает сервер у всех:
  за вечер 27.09 было 7 раз по 3.9–5.5 с («Can't keep up … ~100 ticks» сразу после «Loaded 14952 recipes»).
- **Обходные пути:** фиксированная точка ×10/×100 с ограничением ±25000 (иначе переполнение int), «гейт» в `tick`
  из-за перебора `@e`, кэш позиций игроков из-за `data get entity`, макросы, которые разбираются заново, свет блоками `light` с t=1,
  взрыв через крипера.
- **Интерфейс:** только чат (`menu`), actionbar, bossbar.
- Не выяснено: лаги 2.6–7.3 с в 17:53–17:57 во время `menu/fire_me` (взрывы? генерация чанков?).

## 4. Окружение (проверено локально)

- Сборка: CurseForge-модпак «All of Create - Aeronautics» **v2.6** (ID 1518930), управляется Prism (`ManagedPack=true`).
  NeoForge **21.1.250**, MC 1.21.1, 217 модов. Пути — `tools/paths.py`.
- **JDK 21 с javac уже есть:** `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta`
  (OpenJDK 21.0.7). Там же `java-runtime-epsilon` (25.0.1). Системных java/gradle нет, `~/.gradle` нет, Xvfb нет.
  Сеть до maven есть.
- Железо: 16 ядер, 31 ГБ ОЗУ.
- **Шейдеры у хоста включены:** Iris + Complementary Reimagined r5.9.3 + Euphoria Patches 1.10.5 (`config/iris.properties`).
- Запуск игры до входа в мир — около 2 минут (по `latest.log`).
- Ключевые jar в `mods/` и что лежит внутри (jar-in-jar):
  - `create-1.21.1-6.0.10.jar` → flywheel-neoforge-1.21.1-1.0.6, ponder-neoforge-1.0.82+mc1.21.1, Registrate-MC1.21-1.3.0+67;
  - `sable-neoforge-1.21.1-2.0.5.jar` → sable_rapier, sable-companion-common-1.21.1-1.6.0, **veil-neoforge-1.21.1-4.3.2**;
    лицензия PolyForm Shield 1.0.0;
  - `create-aeronautics-bundled-1.21.1-1.3.2.jar` → aeronautics-neoforge-1.21.1-1.3.2, simulated-neoforge-1.21.1-1.3.2,
    offroad-neoforge-1.21.1-1.3.2;
  - также geckolib-neoforge-1.21.1-4.9.3, snassets-1.0.2, immersive_aircraft 1.5.0, sodium 0.8.13, iris 1.8.14-beta.1,
    create_tweaked_controllers 1.2.7, player-animation-lib 2.0.4, xaeroworldmap 1.46.0, sablexaeromaps 1.4.0, curios 9.5.1,
    Essential 1.5.0.1, e4mc 6.2.1.

### API Sable (прочитано `javap` из jar 2.0.5)
- `api.physics.handle.RigidBodyHandle`: `of(ServerSubLevel)`, `applyImpulseAtPoint(Vec3, Vec3)`, `applyLinearImpulse`,
  `applyAngularImpulse`, `getLinearVelocity()`, `getAngularVelocity()`, `teleport(pos, quat)`, `isValid()`.
  → ударная волна может толкать аппарат; упреждение по скорости аппарата.
- `api.SubLevelHelper`: `pushEntityLocal/popEntityLocal`, `getVelocityRelativeToAir`, `registerWindProvider`,
  `getConnectedChain`.
- `api.entity.EntitySubLevelUtil`, события `SablePrePhysicsTickEvent` / `SablePostPhysicsTickEvent` /
  `SableSubLevelContainerReadyEvent`, `api.sublevel.ServerSubLevelContainer` / `ClientSubLevelContainer` / `SubLevelObserver`,
  физобъекты `BoxPhysicsObject`, `RopePhysicsObject`, связи (`api.physics.constraint.*`).
- Миксины `mixin/clip_overwrite/*` (ClipContext, BlockGetter, GameRenderer, HitResult) — **похоже**, обычный луч `clip/pick`
  уже попадает в блоки аппаратов. **Проверить на шаге 0**: какие координаты приходят в `HitResult` (мир или «плот»).
- В Sable есть свои GameTest (`dev/ryanhcode/sable/neoforge/gametest`).
- `sable-companion` (MIT, можно вшивать): `getContaining`, `projectOutOfSubLevel`, `distanceSquaredWithSubLevels`.
  Sable 2.0.5 не принимает companion новее 1.6.0.
- **Стабильность API не обещана** (README Sable); переход на 2.0 ломал аддоны.

## 5. Технические факты (веб, с источниками)

### Мультиплеер и раздача
- NeoForge не сравнивает списки модов. Друга не пустит, если на сервере есть записи в синхронизируемых реестрах
  (ENTITY_TYPE, ITEM, BLOCK, SOUND_EVENT, PARTICLE_TYPE, MENU, DATA_COMPONENT_TYPE…), которых нет у клиента,
  или обязательный канал пакетов, которого нет у клиента.
  https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/registries/NeoForgeRegistriesSetup.java
- Мод «обязателен только на сервере» возможен (только ванильные display-сущности, звуки без регистрации, опциональные
  пакеты `PayloadRegistrar.optional()` + `hasChannel`), но **без** GeckoLib, своих сущностей и предметов.
  Рекомендация для троих друзей: не стоит, делаем мод, обязательный у всех (Артём ещё не подтвердил, см. §11).
- **Essential и e4mc моды не раздают.** e4mc — только туннель. Essential 1.5.0 раздаёт пакеты ресурсов, но не моды
  («have to join with the exact same mods installed»). https://essential.gg/wiki/start-hosting , https://essential.gg/changelog
- Друзьям: Prism → Edit Instance → Mods → Add file (jar). Не проверено: сохраняет ли Prism вручную добавленные моды
  при обновлении CurseForge-пака.

### Инструменты
- ModDevGradle (для мода на одну версию). Официальный MDK `NeoForgeMDKs/MDK-1.21.1-ModDevGradle`: `net.neoforged.moddev`
  2.0.147, NeoForge 21.1.252, Parchment 2024.11.17, Java 21. Мы собираем под **21.1.250** (как в сборке).
- Зависимости:
  - Create: `https://maven.createmod.net`, `com.simibubi.create:create-1.21.1:6.0.10-280:slim` (transitive=false)
    + Ponder, Flywheel, Registrate (`maven.ithundxr.dev/snapshots`). Шаблон:
    https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1 и build.gradle самого Create (ветка mc1.21.1).
  - Sable: `https://maven.ryanhcode.dev/releases`, `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5`;
    companion `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0`.
  - Aeronautics/Simulated: тот же maven, но только до 1.3.1 (`dev.eriksonn.aeronautics:aeronautics-neoforge-1.21.1`,
    `dev.simulated_team.simulated:simulated-neoforge-1.21.1`). Для 1.3.2 — Modrinth maven
    (`https://api.modrinth.com/maven`, `maven.modrinth:create-aeronautics:<ver>`, в `exclusiveContent`) или локальные jar из `mods/`.
  - GeckoLib: `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/`, `software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3`.
  - Veil: `maven.blamejared.com`, `foundry.veil:veil-neoforge-1.21.1:4.3.2` (runtime даёт Sable).
  - NeoForge 1.21.1 в игре работает на mojmap-именах, поэтому jar из `mods/` должны подключаться в dev-запуск без
    ремаппинга — **проверить на шаге 0**.
- **Тесты без окна:** `runGameTestServer` — сервер без окна, код выхода = число упавших обязательных тестов.
  Нужен `neoforge.enabledGameTestNamespaces`; без зарегистрированных тестов падает. Рендер и звук так не проверить.
  https://docs.neoforged.net/docs/1.21.1/misc/gametest/
- Горячая замена кода: JetBrains Runtime + `-XX:+AllowEnhancedClassRedefinition` (регистрации, миксины и подписки
  на события всё равно требуют перезапуска).

### Клиентские API (имена проверены по NeoForge 1.21.1)
- Звук: `AbstractTickableSoundInstance` — движок каждый тик передаёт в OpenAL позицию, громкость и тон.
  OpenAL-Доплер в MC не используется (скорость источника не задаётся) — тон считаем сами.
  Лимит: около 247 обычных + 8 потоковых источников.
- Камера: `ViewportEvent.ComputeCameraAngles` (yaw/pitch/roll — тряска), `ComputeFov` (зум),
  `Minecraft.setCameraEntity` (вид из ракеты; сущность должна быть на клиенте). Серверный `ServerPlayer.setCamera`
  тащит игрока за камерой — не использовать.
- HUD: `RegisterGuiLayersEvent`, `RenderGuiLayerEvent.Pre/Post`; мир: `RenderLevelStageEvent`; свои `Screen`;
  `RegisterClientCommandsEvent`, `ClientTickEvent`.
- **Veil** (LGPL-3, есть в сборке через Sable): пост-обработка, динамический свет, ScreenShake, частицы Quasar.
  **Под шейдерами Iris эффекты Veil ломаются или пропадают** (Veil сам отключает часть конвейера; issue #34 закрыт
  как not planned; есть сторонний iris-veil-compat). У хоста шейдеры включены → основу делаем на частицах, камере и звуке,
  Veil — только бонус, когда шейдеров нет (`IrisApi.getInstance().isShaderPackInUse()`).

### Подводные камни
- Ванильные дальности отслеживания (чанков / интервал тиков): display 10/1, falling_block 10/20, tnt 10/10,
  стрелы 4/20, fireball/snowball/firework 4/10, marker — не отправляется клиенту. Для своей сущности задаём
  `clientTrackingRange` и `updateInterval(1)` сами.
- Пакет скорости сущности обрезает скорость до ±3.9 блока/тик → при 11.5 блока/тик (ракета) экстраполяция на клиенте
  врёт: слать позицию каждый тик и интерполировать (или детерминированно симулировать на клиенте).
- Сущности вне тикающих чанков не тикают; ракета проходит ~0.7 чанка за тик. Грузить чанки по курсу тикетами
  (`RegisterTicketControllersEvent` / `TicketController.forceChunk`), не генерировать новые чанки на лету.

### Лицензии
- Sable: PolyForm Shield — аддону можно всё, кроме конкурирующего с Sable продукта; код Sable не копировать.
- Simulated/Aeronautics: код MIT, `assets/` — All Rights Reserved.
- Superb Warfare: код GPL-3 (брать только если наш мод тоже GPL-3), ассеты — ARR. Ассеты чужих модов не брать.
- GeckoLib MIT, Veil LGPL-3, Create Big Cannons — код MIT.

## 6. Что уже есть у других (референсы, не изобретать заново)

| Мод | Что есть | Лицензия |
|---|---|---|
| Sable Long Range Warfare | радар, свой-чужой, вертикальные пусковые, ракеты с профилями полёта (прямой, горка, сверху, над водой), держит чанки в полёте; зависит от Sable, GeckoLib, Create, Tweaked Controllers, SnAssets | ARR |
| Create: Simulated Missiles (~103 тыс.) | ракеты — объекты Sable, неконтактные взрыватели | ARR |
| Create Missiles (Woukie) | пусковая, панель навигации с картой, разведдроны, многопоточные взрывы | GPL-3 |
| Create: Droneworks Simulated | дрон с видом от первого лица, пульт до 1500 блоков | ARR |
| Superb Warfare (+ SBW Drone Warfare) | FPV-дроны, Javelin с захватом, миномёты | GPL-3 / AGPL-3 |
| «Shahed» (Modrinth) | дрон с терминала по координатам, радар, сирены, звук 17 блоков/тик | ARR |
| Create Big Cannons 5.11.7 | пушки, поддержка Sable 2.0 | MIT |

**Ни у кого не нашли:** наведение взглядом на что угодно, включая аппараты; бетонобойку с бурением; Доплер;
кинематографичные залпы. Наша ниша — «вызванный удар как в кино», а не конструктор ракет.
Перед этапом 1 стоит посмотреть UI у Sable Long Range Warfare и Create Missiles.

## 7. Что даёт мод (цели по направлениям)

**Реализм и кино**
- Непрерывный звук на `AbstractTickableSoundInstance`: плавный Доплер без кусочков и лимита в 4 источника;
  каждый источник независим; задержка звука по расстоянию считается на клиенте.
  Нужны новые зацикленные звуки (двигатель, свист) — генерировать `tools/synth_sounds.py`.
- Тряска камеры с креном без изменения прицела; вид из дрона/ракеты; зум бинокля.
- Модели GeckoLib: винт шахеда, рули ракеты, бомболюк B-2. Дымный след рисует клиент каждый кадр.
- Sable: импульс от ударной волны по аппаратам; пропорциональная навигация с упреждением по скорости аппарата.
- Свои взрывы: форма воронки и каверны, расчёт растянут на несколько тиков, без крипера и блоков `light`.

**Удобство (UX/UI)**
- Пульт-предмет с экраном: оружие, количество, разброс, список целей (игроки и аппараты рядом), подтверждение.
- Бинокль-целеуказатель: зум, прицельная марка, дальность, тип цели, рамка вокруг аппарата, клавиша пуска.
- HUD: подлётное время своих ударов; предупреждение тому, по кому бьют (сирена уже есть).
- Настройки: `ModConfigSpec` + экран вместо `storage airstrike:cfg`.
- Позже: пусковая на аппарате Aeronautics, пуск от редстоуна и Create, радар, перехват.

**Код**
- Java вместо 287 файлов и фиксированной точки; ошибки пишутся в лог со стеком.
- Юнит-тесты для математики (наведение, Доплер) и GameTest без окна с Create + Sable + Aeronautics —
  большую часть проверок Claude делает сам, без Артёма.

## 8. План

### Шаг 0 — пробный запуск (без участия Артёма)
- Каталог `mod/` в этом репо, отдельный Gradle-проект на ModDevGradle, JDK из Prism (`java-runtime-delta`).
- Зависимости: Create, Sable, Aeronautics, GeckoLib (maven или jar из `mods/`).
- `runGameTestServer` поднимается с Create + Sable + Aeronautics.
- Один GameTest: ракета долетает до точки и взрывается.
- Проверить: попадает ли `clip/pick` в блоки аппарата и в каких координатах; работает ли `RigidBodyHandle`.
- **Критерий:** всё это собирается и проходит без окна. Если нет — остаёмся на датапаке, пишем почему.

### Шаг 1 — вертикальный срез: крылатая ракета целиком в моде
- Сущность + модель GeckoLib, наведение (бреющий, горка, пикирование) с упреждением по аппаратам.
- Новый звук с Доплером, тряска камеры, вспышка.
- Бинокль-целеуказатель и пуск по клавише.
- **Критерий:** один вечер с друзьями, сравнение с датапаком.

### Шаг 2 — паритет
- Шахед, B-2 + бетонобойка (бурение, каверна, выброс газов, обломки), залпы, экран пульта, настройки.
- Звуки из `resourcepack/` переезжают в `assets/` мода; отдельный пакет ресурсов и режимы звука 0/1/2 больше не нужны.
- После этого датапак уходит на покой (остаётся в git).

### Шаг 3 — новое
- Пусковые на аппаратах, радар и перехват, импульсы по аппаратам, вид из ракеты.

## 9. Набросок архитектуры (предложение, не догма)

```
mod/src/main/java/.../airstrike/
  AirstrikeMod            регистрации, конфиг
  entity/                 StrikeProjectile (база: полёт, тик, синхронизация) → Drone, CruiseMissile, Bomber, BunkerBomb
  guidance/               Guidance: Cruise, TerrainFollow, Loft, Dive, ProportionalNav (упреждение)
  target/                 Target: BlockTarget, EntityTarget, PlayerTarget, SubLevelTarget (локальные координаты «плота»)
  warhead/                Warhead: Blast (формы воронки), Penetrator (бурение → детонация), Debris
  compat/sable/           ЕДИНСТВЕННОЕ место работы с Sable (мягкая зависимость, try/catch)
  net/                    пакеты: состояние снаряда, события взрыва/звука, команды пульта
  client/sound/           DopplerSoundInstance, задержка звука, хвосты
  client/camera/          тряска, вид из снаряда, зум
  client/hud/ client/screen/   HUD, экран пульта, прицельная марка
  client/render/          рендер GeckoLib, следы, частицы
```

Соответствие датапаку: `fly/*` → `guidance/`, `ray/*` + `track*` → `target/`, `snd/*` → `client/sound/`,
`fx|mfx|bfx/*` → `warhead/` + клиентские эффекты, `menu/*` + `ui/*` → `client/screen/`, `debris/*` → `warhead/Debris`.
Единицы: блоки в `double`, м/с явно; никакой фиксированной точки.

## 10. Риски и меры

| Риск | Мера |
|---|---|
| Jar нужен всем, каждое обновление — переслать и перезапустить игру; разные версии → не пустит | редкие обновления пачкой; версия протокола в пакетах с понятным сообщением |
| Обновление сборки ломает мод (Sable без стабильного API) | весь Sable в `compat/sable/`; компилировать против версий из сборки; GameTest на Sable |
| Ошибка в моде роняет мир у всех | try/catch вокруг тика каждого снаряда (снаряд удаляется, ошибка в лог); тесты |
| Veil ломается под шейдерами хоста | основа на частицах, камере, звуке; Veil только без шейдеров |
| Графику и звук не проверить без окна | GameTest на логику; Артём смотрит только готовые срезы |
| Конфликт имён: мод `airstrike` и датапак `airstrike`, `assets/airstrike/sounds.json` в моде и в пакете звуков | в тестовом мире мода датапака и пакета звуков быть не должно; `tools/deploy.sh` копирует датапак во **все** миры — доработать (исключение для мира мода) |

## 11. Что нужно от Артёма

1. Подтвердить, что ENOTzRPG и WallyFillmark готовы поставить jar и обновлять его.
2. Разрешить шаг 0 (он не трогает игру и миры).
3. Позже: мир для тестов мода (копия или новый) — Claude сам миры не создаёт и не удаляет.

## 12. С чего начать новой сессии

1. Прочитать `CLAUDE.md` и этот файл.
2. Если работаешь **не** на машине Артёма, пути из `tools/paths.py` и jar из `mods/` недоступны: зависимости только
   с maven (§5), а Aeronautics 1.3.2 — через Modrinth maven.
3. Шаг 0 (§8). Результат — короткий отчёт Артёму: что заработало, что нет, решение идти дальше или нет.
