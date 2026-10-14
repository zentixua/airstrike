# Задание ноутбуку: данные дублей v5 для экзамена монтажа

Ветка `claude/project-thread-1bcgqw` (zentixua/incoming), коммит — в сообщении координатора. Только чтение и один
архив текста: процессор, секунды, видеокарта не нужна, игру Артёма и его файлы не трогает. Ничего не удалять.

Зачем: новый монтаж трейлера проверяет каждую склейку по данным игры (событие и его звук уместились, объект крупный,
не в дыму, у боя есть выстрелы, сирена целиком). Прежде чем верить этой проверке, она должна упасть на v5 ровно там,
где Артём увидел брак. Для этого нужны данные дублей, из которых собран v5: проверки кадров, журналы звука и журналы
записи. Кадров и файлов карты Zearth в архиве нет — только тексты (места снарядов, людей и камер по тикам).

```sh
cd /mnt/data/projects/airstrike && git fetch origin claude/project-thread-1bcgqw
T=mod/run/claude-work/trailer-wt
CUTS=$(ls -t $T/dist/incoming-trailer-v5-cuts.md $T/dist/*v5*cuts.md 2>/dev/null | head -1); echo "$CUTS"
git show <коммит>:tools/trailer/exam_pack.py > /tmp/exam_pack.py && mkdir -p $T/dist/exam-v5
python3 /tmp/exam_pack.py "$CUTS" $T $T/dist/exam-v5 --name trailer-exam-v5 2>&1 | tee $T/dist/exam-v5/pack.log
```

Если таблицы склеек v5 там нет, найти её: `find mod/run/claude-work -name '*v5*cuts.md'` (это та же таблица, что
`trailer-v5-incoming-trailer-v5-cuts.md` в файлах проекта: 84 склейки, первая строка `0:00.00–0:01.00 | 1 | p01a`).

Прислать (в корень файлов проекта): `pack.log` → `trailer-exam-v5-pack.log`, каждую часть
`trailer-exam-v5.tar.gz.NNN` как есть. Строки «нет …» в `pack.log` — не ошибка: у живых планов без повтора журнала
записи нет; их список нужен проверке.
