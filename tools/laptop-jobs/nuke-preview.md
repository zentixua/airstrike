# Ноутбук: ядерный взрыв на видео — один дубль в реальном времени (тред «Снять ядерку»)

Артём 01.10: снять ядерку так, как она выглядит в обычной игре — его сборка, его шейдеры, Distant Horizons
с настройками по умолчанию, — без покадрового рендера. Один дубль; повтор — только если сам дубль не удался (клиент
упал, видео нет или оно чёрное), и только по слову координатора.

Второй дубль (первый, 01.10 ~10:25 UTC, в `claude-work/nuke-preview` — не трогать: окно снялось углом 854×480, монтаж
сдвинул отрезки на час). Ветка `claude/nuke-preview-capture-9iq1ma`, коммит **@SHA@** — полный SHA из сообщения координатора подставить во все
блоки вместо `@SHA@` (одна замена, до шага 2). В коммите: `tools/prod_client.py --video` пишет окно игры в реальном
времени (`tools/x11_record.py`: ffmpeg `x11grab` окна во вложенном KWin, окно ищется через libX11 без внешних программ, звук игры — `audio.wav` 48 кГц), монтаж —
`tools/laptop-jobs/nuke-preview-cut.py`.

Условия: Артём не играет; одна тяжёлая задача на машине; только `tools/laptop_job.sh`; клиент только через
`tools/nested_kwin.sh` (это делает `prod_client.py`). **Инстанс Артёма, его миры и настройки не трогать** — с них
только копируется. Инстанс фильма (`mod/run/film/instance/minecraft`) и мир `greenfield-film` не трогать — мир
копируется. Всё своё — только в `$W`. Отката не нужно: вне `$W` ничего не меняется.

**Каждый блок — одним вызовом, как написан** (состояние оболочки между вызовами не сохраняется). Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`. Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2`
(новая; если уже есть — стоп, сообщить координатору, ничего не удалять и не перезаписывать).
**Остановить задачу — только** `systemctl --user stop 'airstrike-job-nuke-preview2-*'`. Никаких `pkill`/`killall`/`kill`
по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд или строка «стоп» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
SHA=@SHA@; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2" && echo "стоп: папка nuke-preview2 уже есть"
for t in ffmpeg ffprobe; do command -v "$t" >/dev/null || echo "стоп: нет $t"; done
ffmpeg -hide_banner -h demuxer=x11grab 2>&1 | grep -q window_id || echo "стоп: ffmpeg без x11grab -window_id"
ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libx264 || echo "стоп: ffmpeg без libx264"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft"
test -d "$FILM/saves/greenfield-film" || echo "мира greenfield-film нет — будет взят «Greenfield v0.5.4» Артёма"
echo "моды фильма, которых нет у Артёма:"; comm -23 <(ls "$FILM/mods" | grep -v '^airstrike-' | sort) <(ls "$MC/mods" | grep -v '^airstrike-' | sort)
du -sh "$FILM/saves/greenfield-film" "$MC/mods" "$MC/config" "$MC/shaderpacks"; df -h /mnt/data/projects/airstrike/mod/run
grep -E '^(renderDistance|simulationDistance|fov|fullscreen|guiScale|soundCategory_master):' "$MC/options.txt"
grep -E '^(shaderPack|enableShaders)=' "$MC/config/iris.properties"
ls "$MC/config" | grep -i distant
```
Свободно — не меньше мира + mods + config + 15 ГБ (видео дубля — несколько ГБ).

## 2. Подготовка: worktree, сборка, копия
Мир: `greenfield-film` (в нём заранее построены LOD Distant Horizons на километры), но если в шаге 1 у фильма нашлись
моды, которых нет у Артёма, или мира фильма нет, — мир Артёма `Greenfield v0.5.4` (тот же город, те же координаты).
```sh
cd /mnt/data/projects/airstrike && git fetch origin claude/nuke-preview-capture-9iq1ma && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2" @SHA@ && \
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2" && cd "${W:?}" && mkdir -p "$W/mod/run" && git log --oneline -1
```
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```
Копия инстанса Артёма (моды, config, шейдеры, `options.txt`) и мира — в `$W/mod/run/preview`. В копии: настройки
Distant Horizons — в сторону (DH создаст свои по умолчанию); окно игры — 1920×1080 с первого кадра: `prod_client.py` передаёт
`--width/--height` по `--size`, окно загрузки NeoForge (`fml.toml`) — тот же размер, полный экран выключен (в дубле 01.10
он не включился, и весь дубль вышел 854×480).
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2" && cd "${W:?}" && \
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && P="$W/mod/run/preview" && \
if [ -d "$FILM/saves/greenfield-film" ] && [ -z "$(comm -23 <(ls "$FILM/mods" | grep -v '^airstrike-' | sort) <(ls "$MC/mods" | grep -v '^airstrike-' | sort))" ]; \
then WORLD="$FILM/saves/greenfield-film"; else WORLD="$MC/saves/Greenfield v0.5.4"; fi && echo "мир: $WORLD" && \
python3 tools/prod_client.py --copy-only --world "$WORLD" --dir "$P" && \
mkdir -p "$P/config-aside" && for f in "$P/config"/DistantHorizons*; do [ -e "$f" ] && mv "$f" "$P/config-aside/" && echo "в сторону: $(basename "$f")"; done; \
sed -i -E 's/^fullscreen:.*/fullscreen:false/' "$P/options.txt"; \
F="$P/config/fml.toml"; [ -f "$F" ] && sed -i -E 's/^(\s*earlyWindowWidth\s*=).*/\1 1920/; s/^(\s*earlyWindowHeight\s*=).*/\1 1080/' "$F" && grep -E 'earlyWindow(Width|Height)' "$F"; \
ls "$P/saves"; grep -E '^(renderDistance|fov|fullscreen):' "$P/options.txt"; grep -E '^(shaderPack|enableShaders)=' "$P/config/iris.properties"; \
C="$P/saves/$(basename "$WORLD")/serverconfig/airstrike-server.toml"; [ -e "$C" ] || C="$P/config/airstrike-server.toml"; \
grep -hE '^\s*(flight_time|siren|effects_scale)\s*=' "$C" || echo "строк нет — значения по умолчанию"
```
`flight_time` больше 3600 — не запускать, сообщить (по умолчанию 1800 — полёт МБР 90 с).

## 3. Дубль (один; в фоне, тайм-аут вызова 40 мин)
Ночь (вспышка превращает её в день). Камера 1 — над городом в 1,6 км к западу от эпицентра, на 225, взгляд на
восток чуть вверх: пуск МБР у камеры (стол — в 30 блоках позади), сирена, вспышка, шар, кольцо и стена пыли через
город на камеру (~20 с). Через 30 с после подрыва камера 2 — в 2,6 км к юго-западу, над портом и заливом, на 150,
взгляд на эпицентр вверх на 20°: гриб над городом, стена пыли доходит и сюда. Камера в спектаторе, интерфейс скрыт.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
P="$W/mod/run/preview" && WN=$(ls "$P/saves" | head -1) && echo "мир $WN, начало: $(date -u +%T) UTC" && \
timeout -k 60 40m tools/laptop_job.sh nuke-preview2 -- python3 tools/prod_client.py commands --no-copy --dir "$P" --world "$WN" \
  --size 1920x1080 --video "$P/take.mkv" --seconds 1800 --prop airstrike.commands.gap=20 \
  --prop "airstrike.commands=hud:off;gamemode spectator;time set 18000;gamerule doDaylightCycle false;gamerule doWeatherCycle false;weather clear;tp @s -1463.5 225 -495.5 -90 -6;wait:1200;airstrike nuke at 136.5 69 -495.5 15 air;wait:nuke;wait:600;tp @s -1124.5 150 1779.5 -151 -20;wait:1300"; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-nuke-preview2-*';; esac; true
```
Конец — `SCENARIO done` в `$P/logs/latest.log`, клиент выходит сам: **код 0**. Всего ~4–6 мин игры плюс загрузка.

## 4. Наблюдатель
- Прогон жив: растут `$P/logs/latest.log` (строки `SCENARIO`) и `$P/take.000.mkv` (или следующий кусок). Смерть
  прогона — координатору в течение 5 мин: последние 40 строк лога, `crash-reports/`, `$P/take.jsonl`, хвост `take.*.log`.
- Через ~3 мин после старта — один кадр из последнего куска видео (только чтение, файл пишется дальше):
  `ffmpeg -v error -sseof -3 -i "$P/take.<N>.mkv" -frames:v 1 -y "$W/mod/run/probe.jpg"` — посмотреть: должна быть
  картинка игры (загрузка, меню или мир). В `$P/take.jsonl` у куска с миром — `"width": 1920, "height": 1080`; другой размер — стоп так же. Сплошь чёрный кадр — запись окна не работает: остановить задачу (только
  `systemctl --user stop`), сообщить координатору с `take.jsonl` и `take.*.log`.

## 5. Монтаж и выжимка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-preview2" && cd "${W:?}" && P="$W/mod/run/preview" && \
timeout -k 30 20m tools/laptop_job.sh nuke-preview2-cut -- python3 tools/laptop-jobs/nuke-preview-cut.py "$P" "$P/nuke-preview.mp4"; echo "код $?"; \
ls -l "$P"/take.* "$P/audio.wav" "$P"/nuke-preview.*; cat "$P/take.jsonl"; \
if [ "$(stat -c %s "$P/nuke-preview.mp4")" -gt 200000000 ]; then ffmpeg -hide_banner -loglevel error -y -i "$P/nuke-preview.mp4" \
  -vf scale=1600:900 -c:v libx264 -preset slow -crf 23 -c:a copy -movflags +faststart "$P/nuke-preview-small.mp4"; ls -l "$P/nuke-preview-small.mp4"; fi; \
grep -E 'SCENARIO|МБР №|Подрыв №|Руины удара №|has crashed|emergencySaveAndCrash|Unreported exception|Distant Horizons' "$P/logs/latest.log" | cut -c1-300 | head -60; \
python3 tools/logscan.py "$P/logs/latest.log" --all | head -80
```

## Что прислать координатору
1. Вывод шагов 1 и 2 (настройки копии, мир, строки «в сторону»), код и время дубля (шаг 3), вывод шага 5 целиком
   (≤ 40 КБ).
2. Файлы (SendUserFile): `$P/nuke-preview.mp4` (больше 200 МБ — вместо него `$P/nuke-preview-small.mp4`) и `$P/nuke-preview.txt`. Сырые куски `take.*.mkv` и копия остаются в
   `$W` (уборка — по слову Артёма в треде «Ноутбук»).
