# Ноутбук: состояние чанков на диске у карт Артёма (только чтение, лёгкая задача)

Тред «Руины ядерки на старых картах», ветка `claude/project-thread-6ggj93`, PR #206. Координатор вписывает полный SHA
коммита вместо `<SHA>` во **все** блоки ниже (и `SHA7` — первые 7 знаков). Артём в это время не играет.

**Что проверяем.** PR #206 даёт план руин с диска чанкам мира 1.17 после обновления (статус «пусто» и
`below_zero_retrogen` с целью полной генерации). Догадка: на Newisle и других старых картах таких чанков большинство,
поэтому на подрыве №2 в Newisle (2941 84 −471, 15 кт) план с диска получили 625 чанков из 15 280. Скрипт
`tools/laptop-jobs/region-status.py` (Python 3, только стандартная библиотека) читает копии файлов регионов и считает
чанки по тому же правилу, что `DiskStatus.of`, — и сколько чанков получили бы план с диска до правки и после.

## Правила
- Каждый блок — одним вызовом, как написан. Блоки с шага 2 начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/region-status-SHA7` (новая). Уже есть — стоп,
  сообщить координатору, ничего не удалять и не перезаписывать.
- Миры Артёма только читаются: копия папки `region` каждого мира — в `$W`. В его папке ничего не пишется.
- Никаких `pkill`/`kill`, `rm -rf`, `-f`/`--force`, checkout в основном клоне. Ничего не устанавливать.
- Результат — только текст (вывод шагов 1 и 3). Копии остаются в `$W`; уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод «стоп» — не запускать, сообщить координатору.
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
pgrep -x java >/dev/null && echo "стоп: работает java (игра Артёма или другая задача)"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/region-status-SHA7" && echo "стоп: папка уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack/minecraft"
ls "$MC/saves"; for d in "$MC"/saves/*/region; do du -sh "$d"; done; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше суммы папок `region` Newisle, Zearth, Greenfield и Radiant City + 2 ГБ.

## 2. Скрипт и копии регионов
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/region-status-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch -q origin claude/project-thread-6ggj93 && mkdir "$W" && cd "${W:?}" && \
git -C "$R" show <SHA>:tools/laptop-jobs/region-status.py > region_status.py && python3 -m py_compile region_status.py && echo "скрипт на месте" && \
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack/minecraft" && \
for s in "$MC"/saves/*; do case "$(basename "$s")" in *Newisle*|*Zearth*|*Greenfield*|*Radiant*) \
  mkdir -p "$W/$(basename "$s")" && nice -n 19 ionice -c3 cp -r "$s/region" "$W/$(basename "$s")/" && echo "скопирован $(basename "$s")";; esac; done
```
Должно быть «скрипт на месте» и «скопирован …» по каждому из четырёх миров.

## 3. Подсчёт
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/region-status-SHA7" && cd "${W:?}" && \
for d in */region; do nice -n 19 timeout 30m python3 region_status.py "$d"; done; \
for d in *Newisle*/region; do nice -n 19 timeout 30m python3 region_status.py "$d" --around 2941 -471 1150; done
```
Прислать координатору весь вывод шага 3 (по строке «папка …» на мир и круг подрыва №2 в Newisle).
