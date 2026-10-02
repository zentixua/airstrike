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

Prism Launcher: «Добавить экземпляр» → «Импорт» → файл `.mrpack`. Prism покажет окно необязательных модов: запись
(Flashback, Sinytra Connector, Forgified Fabric API, Flashback NeoForge Fixed) нужна только тем, кто снимает;
не выбранные моды ставятся выключенными, их можно включить потом во вкладке модов. Если Prism попросит подтвердить
загрузку не с Modrinth — это jar Airstrike из выпуска на GitHub.

## Что внутри

| группа | моды |
|---|---|
| основа | Create, Sable, Create Aeronautics, Airstrike (jar выпуска с GitHub), Distant Horizons |
| производительность | Sodium, Sodium Extra, Reese's Sodium Options, Iris, Colorwheel, Lithium, FerriteCore, ModernFix, ImmediatelyFast, Entity Culling, Dynamic FPS |
| Create | Propulsion: Simulated, Aeroworks, Tweaked Controllers, Big Cannons (+ RPL), Connected, Deco, Diesel Generators, Aeronautics Hot Air Fix |
| авиация и оружие | Immersive Aircraft, Man of Many Planes, Vic's Point Blank (+ GeckoLib), Point Blank Aeronautics compat |
| картинка и камера | шейдерпак Complementary Reimagined, Euphoria Patches, Aeronautics Camera Sync, 3D Skin Layers, Not Enough Animations |
| мультиплеер и удобство | Essential, JEI (+ MezzConfig), Jade, Jade Sable Compat, Xaero's Minimap и World Map, Create – Xaero's map, GraveStone и его патч для Sable, Mouse Tweaks, No Chat Reports |
| запись (по выбору) | Flashback, Flashback NeoForge Fixed, Sinytra Connector, Forgified Fabric API |

Настройки, которые сборка кладёт сама:
- `config/entityculling.json` — значения Entity Culling по умолчанию плюс сущности Airstrike в `entityWhitelist`: такие
  сущности мод не прячет и не останавливает их тик на клиенте (в 1.11.2 этот список действует на оба). Факел снаряда
  рисуется вместе с моделью, а шлейф, облако пуска и дым горящих обломков рождаются в тике сущности на клиенте
  (`AirstrikeClient.entityTick`): без исключения снаряд за холмом или за спиной игрока терял бы факел и рвал шлейф.

## Как обновлять

- Мод с Modrinth: `packwiz update <имя>` (или `packwiz modrinth add --project-id <slug> --version-id <id>` — точная
  версия), потом `packwiz refresh`. CI проверяет, что `index.toml` свежий.
- Новый выпуск Airstrike: `packwiz url add Airstrike https://github.com/zentixua/airstrike/releases/download/v<версия>/airstrike-<версия>.jar --meta-name airstrike`.
- Версию сборки (`version` в `pack.toml`) поднимать при каждом изменении состава: у всех игроков должен быть один набор.
