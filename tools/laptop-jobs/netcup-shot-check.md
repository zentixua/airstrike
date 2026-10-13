# Задание netcup: камера по сцене на записях проверки трейлера (только процессор)

Ветка `claude/project-thread-aealhl` (zentixua/incoming), коммит — в сообщении координатора. Цель — проверить на
настоящих планах (Zearth и журналы записей проверки), что `tools/shot` (README там) за секунды находит камеру, при
которой объект плана в кадре и виден, и что его мера согласна с проверкой кадров вывода (`FrameQa`): камера сценария
(`own`) той же мерой против вердикта `render/best.txt`. Игру не запускать: только Python по готовым файлам.

Не трогать: сервер Артёма, его миры, чужие службы. Мир `~/trailer-war` — только читать (файлы `region/*.mca`), не
копировать. Свободного места — не меньше 4 ГБ (кэш карты — до ~30 МБ на план). Наружу — только текст ниже.

## Подготовка
```sh
cd <рабочее дерево incoming> && git fetch origin claude/project-thread-aealhl && git worktree add ~/claude/shot-wt <коммит>
python3 -m venv ~/claude/shotenv && ~/claude/shotenv/bin/pip install -q numpy scipy pillow   # или uv, если есть
~/claude/shotenv/bin/python -m unittest discover -s ~/claude/shot-wt/tools/shot/tests       # 7 тестов, OK
```

## Запуск
Журналы — все `*.events.jsonl` записей проверки (`mod/run/scenario/check-*/`), карта — `~/trailer-war/region`.
Туман края прорисовки при проверке — 24 чанка (0,8 × 24 × 16 = 307 блоков), облака — 320:
```sh
cd ~/claude/shot-wt
EV=$(find <рабочее дерево incoming>/mod/run/scenario/check-* -name '*.events.jsonl' | sort)
~/claude/shotenv/bin/python tools/shot/shot.py plan $EV --regions ~/trailer-war/region --out ~/claude/shot-check \
    --fog 307 --clouds 320 --previs none 2>&1 | tee ~/claude/shot-check.log
```
План без объекта (сцена, пролёт) пишет «… нет» и пропускается — так и надо.

## Прислать (текстом)
1. Из `~/claude/shot-check.log`: строки каждого плана (камеры `own`, `fpv`, `drone`, `tripod`, `tracker`, «лучшая») и
   «Итог» целиком; время каждого плана.
2. Для сравнения — вердикты вывода тех же планов: `render/best.txt` каждой части `check-*` (строки PASS/FAIL с
   причинами) и, где есть, строку `SCENARIO film solve <план>` с первой (выбранной) камерой.
3. Ошибки Python, если были, — целиком.

## После
`rm -rf ~/claude/shot-check ~/claude/shot-check.log` (кэш карты — данные Zearth) и `git worktree remove ~/claude/shot-wt`.
