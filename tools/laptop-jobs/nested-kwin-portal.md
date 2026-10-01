# Ноутбук: вложенный KWin не трогает маунт портала документов (одна лёгкая задача, ~1 мин)

Тред «Вложенный KWin и flatpak». Коммит **@SHA@** — полный SHA из сообщения координатора подставить во все блоки ниже
вместо `@SHA@` (одна замена, до шага 2; строки с `@SHA@` после неё быть не должно).

Что проверяется: `tools/nested_kwin.sh` с этого коммита поднимает шину D-Bus вложенной сессии без запуска служб
(`tools/nested_kwin_bus.conf`). До него шина сессии поднимала xdg-desktop-portal и xdg-document-portal, и тот снимал
у Артёма маунт `/run/user/1000/doc` — flatpak-приложения (Prism) не запускались. Старую версию для сравнения **не
запускать**: она снова снимет маунт.

Условия: Артём не играет; только `tools/laptop_job.sh`; инстанс Артёма, его миры и настройки не трогаются, Prism
не открывается (`flatpak run --command=true` запускает в песочнице Prism только `true`, без окна).
**Каждый блок — одним вызовом, как написан.** Остановить задачу — только
`systemctl --user stop 'airstrike-job-nested-kwin-portal-*'`; никаких `pkill`/`kill` по имени, `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд или строка «стоп» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nested-kwin-portal" && echo "папка nested-kwin-portal уже есть — стоп"
findmnt -n -o ID,SOURCE,FSTYPE /run/user/1000/doc || echo "маунта /run/user/1000/doc нет — стоп (вернуть: systemctl --user restart xdg-document-portal.service)"
```

## 2. Worktree
```sh
cd /mnt/data/projects/airstrike && git fetch origin @SHA@ && git cat-file -e '@SHA@^{commit}' && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/nested-kwin-portal" @SHA@ && \
W="/mnt/data/projects/airstrike/mod/run/claude-work/nested-kwin-portal" && cd "${W:?}" && mkdir -p mod/run && \
git log --oneline -1 && grep -c 'nested_kwin_bus.conf' tools/nested_kwin.sh
```

## 3. Прогон (один вызов: до, вложенный KWin, после)
Внутри вложенной сессии: сокет Wayland, подключение к Xwayland, запуск обоих порталов по имени (ждём отказ
`ServiceUnknown`), список имён, которые шина может запустить (ждём только `org.freedesktop.DBus`), маунт — изнутри.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nested-kwin-portal" && cd "${W:?}" && \
cat > mod/run/portal-probe.sh <<'PROBE'
echo "внутри: WAYLAND_DISPLAY=$WAYLAND_DISPLAY DISPLAY=$DISPLAY шина=$DBUS_SESSION_BUS_ADDRESS"
test -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" && echo "wayland: сокет есть" || echo "wayland: сокета НЕТ"
python3 -c 'import ctypes; x = ctypes.CDLL("libX11.so.6"); x.XOpenDisplay.restype = ctypes.c_void_p; print("x11:", "открылся" if x.XOpenDisplay(None) else "НЕ открылся")'
for n in org.freedesktop.portal.Desktop org.freedesktop.portal.Documents; do
  echo "запуск $n:"; dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.StartServiceByName "string:$n" uint32:0 2>&1 | head -2
done
echo "может запустить:"; dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.ListActivatableNames 2>&1 | grep string
echo "маунт изнутри: $(findmnt -n -o ID,FSTYPE /run/user/1000/doc || echo нет)"
PROBE
before() { echo "== $1"; findmnt -n -o ID,SOURCE,FSTYPE /run/user/1000/doc || echo "маунта нет"; \
  systemctl --user show -p ActiveState -p MainPID xdg-document-portal.service | tr '\n' ' '; echo; \
  flatpak run --command=true org.prismlauncher.PrismLauncher; echo "prism (flatpak run --command=true): код $?"; \
  busctl --user call org.kde.kglobalaccel /component/kwin org.kde.kglobalaccel.Component isActive; }
T0=$(date +%s); before "до"; \
timeout -k 10 120 tools/laptop_job.sh nested-kwin-portal -- "$W/tools/nested_kwin.sh" wayland-airstrike-portalcheck 640 480 "sh $W/mod/run/portal-probe.sh" > mod/run/portal-check.out 2>&1; \
code=$?; echo "код задачи $code"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-nested-kwin-portal-*';; esac; before "после"; \
echo "== вывод сессии ($(wc -l < mod/run/portal-check.out) строк): проба, шина, ошибки"; \
grep -E '^(внутри|wayland:|x11:|запуск|может запустить|маунт изнутри)|string "|Error|rror:|Activating|dbus-daemon' mod/run/portal-check.out | cut -c1-300 | head -60; \
echo "== последние строки"; tail -15 mod/run/portal-check.out | cut -c1-300; \
echo "== строки активации служб в выводе сессии: $(grep -c 'Activating' mod/run/portal-check.out)"; \
J=$(journalctl -b --since "@$T0" --no-pager -o short-iso 2>/dev/null | grep -E 'run-user-1000-doc|document-portal|xdg-desktop-portal' | cut -c1-300); \
echo "== журнал с начала прогона (маунт doc, порталы):"; echo "${J:-нет строк}"
```

Пройдено, если всё так:
1. «до» и «после»: тот же ID маунта `fuse.portal`, тот же `MainPID` у `xdg-document-portal.service`, `ActiveState=active`;
   Prism — код 0 оба раза; kglobalaccel — `b true` оба раза.
2. Код задачи 0. Внутри: сокет Wayland есть, x11 открылся, оба запуска — `org.freedesktop.DBus.Error.ServiceUnknown`,
   «может запустить» — только `org.freedesktop.DBus`, маунт изнутри — тот же ID.
3. Строк активации в выводе сессии — 0; в журнале с начала прогона нет строк о `run-user-1000-doc` и порталах.

Если маунт всё же пропал — сразу вернуть: `systemctl --user restart xdg-document-portal.service`, и сообщить.

## Что прислать координатору
Весь вывод шага 3 текстом (он короткий) и итог по трём пунктам. Worktree остаётся (уборка — только по слову Артёма
в треде «Ноутбук»).
