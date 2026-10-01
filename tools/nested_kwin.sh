#!/usr/bin/env bash
# Вложенный KWin без окна (--virtual + Xwayland) для клиента Minecraft: сценарии и трейлер.
#
#   tools/nested_kwin.sh <сокет> <ширина> <высота> <команда>
#
# Изолирован от рабочего стола:
#   - своя сессионная шина D-Bus (dbus-run-session): на общей шине вложенный KWin цеплялся к kglobalaccel хоста
#     под тем же именем компонента «kwin» и при выходе выключал все его сочетания (Alt+Tab и т.д.);
#   - эта шина служб не запускает (nested_kwin_bus.conf — без D-Bus-активации): XDG_RUNTIME_DIR общий с рабочим
#     столом, и поднятый ею xdg-document-portal снимал у хоста маунт $XDG_RUNTIME_DIR/doc — flatpak-приложения
#     (Prism) не запускались. Сама сессия кладёт туда только свои сокеты с уникальными именами (wayland-airstrike-…);
#   - свои каталоги XDG (config, data, cache, state) в mod/run/kwin/<сокет>: KConfig читает и пишет kwinrc
#     и прочее по спецификации XDG Base Directory, так что настройки рабочего стола не трогаются;
#   - без окна на рабочем столе: бэкенд --virtual рисует во внеэкранный буфер (на видеокарте, через EGL), окна
#     на экране хоста у него нет вовсе;
#   - без звука в колонках: клиенту закрыт звуковой сервер хоста — PIPEWIRE_REMOTE и PULSE_SERVER указывают на
#     несуществующие сокеты, так что ни PipeWire, ни PulseAudio, ни ALSA/JACK поверх PipeWire не открываются.
#     Сценарий, которому нужен звук (client_scenario.sh), пишет его драйвером OpenAL Soft «wave» в файл
#     (ALSOFT_CONF), трейлер собирает звук из журнала — ни тому ни другому сервер не нужен.
# Команда (строка) выполняется внутри сессии вложенного KWin (--exit-with-session): с его выходом KWin закрывается.
set -euo pipefail
[ $# -eq 4 ] || { echo "использование: $0 <сокет> <ширина> <высота> <команда>" >&2; exit 2; }
SOCKET="$1" WIDTH="$2" HEIGHT="$3" COMMAND="$4"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="$ROOT/mod/run/kwin/$SOCKET"
mkdir -p "$HOME_DIR/config" "$HOME_DIR/data" "$HOME_DIR/cache" "$HOME_DIR/state"
exec env -u DISPLAY -u WAYLAND_DISPLAY -u DBUS_SESSION_BUS_ADDRESS \
    XDG_CONFIG_HOME="$HOME_DIR/config" XDG_DATA_HOME="$HOME_DIR/data" \
    XDG_CACHE_HOME="$HOME_DIR/cache" XDG_STATE_HOME="$HOME_DIR/state" \
    PIPEWIRE_REMOTE="$HOME_DIR/no-audio-server" PULSE_SERVER="unix:$HOME_DIR/no-audio-server" \
    dbus-run-session --config-file="$ROOT/tools/nested_kwin_bus.conf" -- \
    kwin_wayland --virtual --xwayland --socket "$SOCKET" --width "$WIDTH" --height "$HEIGHT" \
    --exit-with-session "$COMMAND"
