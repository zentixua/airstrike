---
paths:
  - "tools/**"
  - "mod/src/devtest/java/ua/zentix/airstrike/rig/**"
  - "mod/src/devtest/java/ua/zentix/airstrike/stress/**"
---

# Стенды, мультиплеер, вложенный KWin, боевой клиент

## Подводные камни
- Стенд нагрузки (`tools/stress.sh`, в облаке `MODS=run/ci-mods`): сервер и три клиента на 4 ядрах и 16 ГБ — у JVM
  пределы памяти в `build.gradle` (иначе ядро убивает клиента); `AIRSTRIKE_RIG_JVM=artem` — сервер с JVM, как
  у Артёма (8 ГБ, поколенческий ZGC; только где хватает памяти — VPS), сборщик — в строке «сервер запущен». Тики дольше 0,5 с режиссёр пишет со стеком
  сервера: 35 с в облаке — Sable (`PhysicsChunkTicketManager` грузит чанки синхронно), пока генерация стоит в очереди.
- Серверы проверок слушают только петлю: `server-ip=127.0.0.1` и свободный порт (`tools/free_port.py`) в `stress.sh`
  и `mp_scenario.sh` — на VPS сервер стенда на 0.0.0.0:25565 с `online-mode=false` нашёл сканер из интернета.
  Сторож devtest `rig/LoopbackGuard` роняет сервер (до загрузки мира, мир в LAN — по тику), если сокет не на петле.
  UDP: канал Sable встаёт в тот же список каналов на адресе TCP (сторож видит и его); рассылку LAN NeoForge
  (`advertiseDedicatedServerToLan` в `config/neoforge-server.toml`: сокет на 0.0.0.0, пишет в 224.0.2.60:4445) скрипты
  выключают (`tools/rig_config.py`), сторож роняет сервер, если она включена. UDP-сокет самого Gradle (блокировки
  файлов, `DefaultFileLockCommunicator`) слушает 0.0.0.0 без настройки адреса, поэтому стенд и мультиплеер
  Gradle только готовят: `./gradlew rigLaunch -PrigRun=<задача run…> -PrigOut=<имя>` пишет `build/rig/<имя>.sh`
  (java, @файлы MDG — копией на каждый запуск, classpath, каталог, окружение задачи; настройки, которые задача
  читает из окружения и `-P`, — на момент подготовки), Gradle выходит, игра идёт из скриптов без живого Gradle.
  Сервер и клиенты стенда и мультиплеера — каждый в своей сессии (`tools/rig_procs.sh`: `rig_spawn`, `rig_trap`):
  JVM клиента — внук обёртки (xvfb-run, nested_kwin → dbus-run-session → kwin), `kill` обёртки оставлял его сиротой;
  выход скрипта (конец, ошибка, Ctrl+C, TERM) гасит сессии целиком — TERM, через 20 с KILL (повторный Ctrl+C её
  не обрывает), потом сам выходит тем же сигналом. Гасит только сессии с меткой `AIRSTRIKE_RIG_TAG` в окружении:
  номер закончившейся сессии после оборота PID может стать чужим.
- Вложенный KWin запускать только через `tools/nested_kwin.sh` (своя шина `dbus-run-session`, свои `XDG_*_HOME`):
  на общей шине он цеплялся к kglobalaccel рабочего стола под именем «kwin» и при выходе выключал все сочетания
  KWin у Артёма (Alt+Tab), а kwinrc писал в общий `~/.config`. Проверка: `busctl --user call org.kde.kglobalaccel
  /component/kwin org.kde.kglobalaccel.Component isActive` — должно остаться `true`. Шина вложенной сессии служб
  не запускает (`tools/nested_kwin_bus.conf`, без D-Bus-активации), а `XDG_RUNTIME_DIR` у неё общий с рабочим столом:
  с активацией она поднимала xdg-desktop-portal и xdg-document-portal, а тот при старте снимал у хоста маунт
  `/run/user/1000/doc` (`fusermount3 -u -z`), при выходе — свой; flatpak-приложения (Prism) не запускались
  («bwrap: Can't find source path …/doc/by-app/…»; вернуть — `systemctl --user restart xdg-document-portal.service`).
  Проверка: `findmnt /run/user/1000/doc` до и после — тот же маунт.
- Вся сборка хоста (217 модов) в Gradle-запуске не стартует: Sinytra Connector требует боевую раскладку Minecraft
  («Could not determine clean minecraft artifact path»). Для проверок с полной сборкой — `tools/prod_client.py`
  (библиотеки и ForgeWrapper из каталога Prism, копия инстанса в `mod/run/prod`; инстанс Артёма не трогается).
