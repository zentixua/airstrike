# netcup: серверы миров Airstrike в Crafty (шаг 2)

Тред «Сервер на netcup». Шаг 1 (Java 21 в ~/airstrike-server/jdk-21, карты в ~/airstrike-server/maps-ready/, Crafty 4.11.0
в Docker, Tailscale для панели) — сделан до этого. Рамки те же: всё только в ~/airstrike-server; Immich, Frigate,
Seafile, open-webui, naga-shadow, wg-easy, фаервол и учётки не трогать; хосту не меньше 4 ГБ свободной памяти.

## Что получить
В Crafty по серверу на каждый мир из maps-ready/ и один «Новый мир» (без карты: мир создастся при первом запуске).
Имя сервера в Crafty — название карты по-человечески («Greenfield», «Project Zearth», …). Все на порту 25565 и не
запускаются сами (auto_start выключен): одновременно работает один, мир меняют «Стоп» у одного и «Старт» у другого.
Ни один сервер в конце шага не оставлять запущенным, кроме того, что назовёт тред.

## Каталог сервера (одинаковый у всех, кроме world/)
1. Шаблон один раз, Java из ~/airstrike-server/jdk-21:
   - NeoForge 21.1.250: `https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-installer.jar`,
     `java -jar … --installServer <шаблон>`, установщик и его лог потом удалить.
   - `packwiz-installer-bootstrap.jar`: https://github.com/packwiz/packwiz-installer-bootstrap/releases/latest
2. `start.sh` (команда запуска в Crafty — `bash start.sh`; в контейнере Java 21 смонтирована в /opt/jdk-21):
   ```sh
   #!/usr/bin/env bash
   # Моды — серверная часть сборки из pack/ на main, той же, что у игроков (packwiz-installer, -s server).
   set -uo pipefail
   cd "$(dirname "$0")"
   JAVA=/opt/jdk-21/bin/java
   "$JAVA" -jar packwiz-installer-bootstrap.jar -g -s server \
       https://raw.githubusercontent.com/zentixua/airstrike/main/pack/pack.toml \
     || echo "packwiz-installer: не обновился, запуск с прежними модами"
   exec "$JAVA" @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.250/unix_args.txt nogui
   ```
3. `user_jvm_args.txt`: `-Xmx10G -XX:+UseZGC -XX:+ZGenerational`
4. `eula.txt`: `eula=true`
5. `server.properties` (остальное сервер допишет сам):
   ```
   motd=Airstrike
   server-port=25565
   online-mode=false
   white-list=true
   enforce-whitelist=true
   allow-flight=true
   spawn-protection=0
   enable-command-block=true
   view-distance=12
   simulation-distance=10
   max-players=10
   level-name=world
   ```
   `online-mode=false` — лицензию проверяет TrueUUID из сборки (PR #216). Пока #216 не в main, packwiz-installer
   его не поставит, и сервер на публичном 25565 пускал бы любого под любым ником (на этом VPS стенд с
   `online-mode=false` уже находил сканер из интернета). Поэтому ни один сервер не запускать, пока тред не напишет,
   что #216 влит.
6. `whitelist.json`:
   ```json
   [
     {"uuid": "23ce23ae-898c-443c-b278-181d682bc959", "name": "ZentixUA"},
     {"uuid": "e00e64b5-7d15-4445-acd2-479cf756e4d7", "name": "ENOTzRPG"},
     {"uuid": "526bbd63-b0d8-48f2-9924-bfb521a229e1", "name": "WallyFillmark"},
     {"uuid": "9c30cab0-8cf6-3404-a1a8-f645e08bb0cc", "name": "Maks1k8717"}
   ]
   ```
   (UUID с лицензией — api.mojang.com; Maks1k8717 — без лицензии, офлайн-UUID от «OfflinePlayer:Maks1k8717».)
7. `ops.json`: `[{"uuid": "23ce23ae-898c-443c-b278-181d682bc959", "name": "ZentixUA", "level": 4, "bypassesPlayerLimit": true}]`
8. `config/trueuuid-registry.json` — ники с лицензией сразу известны TrueUUID, под ними без лицензии не войти:
   ```json
   {
     "zentixua": {"premiumUuid": "23ce23ae-898c-443c-b278-181d682bc959"},
     "enotzrpg": {"premiumUuid": "e00e64b5-7d15-4445-acd2-479cf756e4d7"},
     "wallyfillmark": {"premiumUuid": "526bbd63-b0d8-48f2-9924-bfb521a229e1"}
   }
   ```
   После первого запуска сервер перепишет файл сам (те же ники с отметками времени) — проверить, что три ника на месте.
9. `world/` — копия maps-ready/<карта> (у «Нового мира» нет).

## Crafty
- Импорт каталогов как серверы Minecraft Java (API v2 или интерфейс), затем у каждого: команда запуска `bash start.sh`,
  команда остановки `stop`, crash detection включён, auto_start выключен.
- Расписание у каждого сервера: перезапуск каждый день в 05:00 по Варшаве, только если сервер запущен (так он берёт
  обновления сборки), и бэкап каждые 6 часов, пока запущен; бэкапы без mods/, libraries/, logs/, хранить 4.
  Если Crafty не умеет «только когда запущен» — ежедневный бэкап, хранить 3, и сказать об этом.
- Права файлов такие, чтобы пользователь контейнера Crafty читал и писал каталоги серверов.

## Проверка (в конце шага, только после сообщения «#216 влит»)
- Один сервер (Greenfield) запустить из Crafty: в логе packwiz-installer поставил моды (среди них
  `trueuuid-1.3.0-neoforge-1.21.1.jar`, `airstrike-*.jar`, нет `sodium`, `iris`, `essential`), «Done (…)», ошибок
  кроме известных (таблицы добычи create_connected, рецепт createdeco:placard) нет. Время старта — в отчёт.
- `config/trueuuid-registry.json` после старта — три ника.
- Снаружи (не с самой машины, например `nc -vz <публичный IP> 25565` с ноутбука или через тред) порт открыт.
- Сервер оставить запущенным (Greenfield): по нему тред проверит вход.

Отчёт координатору для треда «Сервер на netcup»: 5–8 строк (что создано, время старта, свободная память, публичный IP),
подробности — в ~/airstrike-server/report.md.
