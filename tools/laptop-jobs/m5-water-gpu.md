# Задание ноутбуку: цена столба воды на видеокарте (ядерка, шаг M5)

Коммит zentixua/incoming — в сообщении координатора (ветка `claude/nuke-m5-water-ruk6pz`); «до» — `main` на `bd2af684`
(гриб объёмом без столба). Не во время игры Артёма: начинать, когда `pgrep -f 'instances/Airstrike Pack'` пусто; на
видеокарте — одна тяжёлая задача, клиент — один. Инстанс, его миры и настройки не трогать. Пути — от
`/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из Prism, `W=/mnt/data/projects/airstrike/mod/run/claude-work`.

Что меряем: время прохода пелены и грибов на видеокарте (`PallPass`, качество MEDIUM прибито в сценарии) у подрыва
15 кт на море в 1,5 км (`tools/client_scenario.sh nuke-water`): «до» — столб был клубами слоя эффектов, «после» — он
в объёме гриба. Бюджет пелены — 2 мс на MEDIUM.

## 1. Сборка и два прогона
```sh
cd /mnt/data/projects/airstrike
git fetch origin <коммит> bd2af684
git worktree add --detach $W/m5-gpu-new <коммит>
git worktree add --detach $W/m5-gpu-base bd2af684
for v in base new; do
  (cd $W/m5-gpu-$v && timeout -k 60 40m tools/laptop_job.sh m5-gpu-$v -- tools/client_scenario.sh nuke-water)
  cp $W/m5-gpu-$v/mod/run/scenario/logs/latest.log $W/m5-gpu-$v.log
done
```
Клиент выходит сам (строка `SCENARIO done`). Нет её — первые `ERROR` и исключения лога, один повтор прогона.

## 2. Отчёт
По каждому логу:
```sh
for v in base new; do echo "== $v"
  grep -o 'SCENARIO water pall .*' $W/m5-gpu-$v.log | sed -E 's/.*surge r ([0-9]+).*frames: [0-9]+ frames, ([0-9×]+),.*gpu ([0-9.]+) ms \(longest ([0-9.]+).*/surge \1  \2  gpu \3  longest \4/'
  grep -o '\[#[0-9]* t=[0-9.]*s [^]]*column[^,]*' $W/m5-gpu-$v.log | awk 'NR % 10 == 1' | head -8
  grep -ciE 'shader.*(error|fail)|failed to (load|compile)|Exception' $W/m5-gpu-$v.log
done
```
В отчёт — эти строки как есть (время на видеокарте по строкам пелены раз в 5 с, размер кадра пелены) и число строк
ошибок шейдеров. Кадры не нужны. После отчёта — `git worktree remove --force` обоих каталогов.
