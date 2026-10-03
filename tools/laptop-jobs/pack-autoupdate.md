# Ноутбук: автообновление сборки packwiz-installer на настоящей сети (одна лёгкая задача, ~2 мин)

Тред «Автообновление сборки», PR #207, ветка `claude/pack-autoupdate-1uteq4`. Координатор вписывает полный SHA
кандидата вместо `<SHA>` и `SHA7` (первые 7 знаков) перед отправкой. Игра Артёма может идти: задача только качает
файлы в свою папку (≈ 430 МБ), Minecraft не запускает.

**Что проверяем.** В облаке packwiz-installer-bootstrap не может сам скачать packwiz-installer (api.github.com закрыт
прокси), поэтому там он шёл с готовым jar. Здесь — как у игрока: экземпляр из `tools/prism_instance.py`, bootstrap сам
берёт packwiz-installer с GitHub, ставит сборку с `main` по raw.githubusercontent; повторный запуск — «already up to
date». Окно packwiz-installer не открываем (`-g`: на первой установке оно ждёт галочек), Prism не трогаем.

**Успех:** первый запуск — выход 0, в логе `Finished successfully!`, в `mods/` 50 файлов `.jar` (CLI принимает все
необязательные), `mmc-pack.json` не изменился; второй — выход 0 и `Modpack is already up to date!`.

## Правила
- Каждый блок — одним вызовом, как написан. Папка — ровно
  `/mnt/data/projects/airstrike/mod/run/claude-work/pack-auto-SHA7` (новая); уже есть — стоп, сообщить координатору.
- Инстанс Артёма и сам Prism не трогать. Ничего не удалять. Результат — текстом координатору.

## 1. Экземпляр из кандидата
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || { echo "стоп: SHA не вписан"; exit 1; }
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-auto-SHA7
test -e "$W" && { echo "стоп: $W уже есть"; exit 1; }
cd /mnt/data/projects/airstrike && git fetch -q origin "$SHA" && mkdir -p "$W/src" "$W/inst" \
  && git archive "$SHA" tools/prism_instance.py tools/paths.py pack/pack.toml | tar -x -C "$W/src" \
  && python3 "$W/src/tools/prism_instance.py" "$W/inst.zip" \
  && cd "$W/inst" && unzip -q ../inst.zip && cp mmc-pack.json ../mmc-pack.orig && grep PreLaunchCommand instance.cfg
```

## 2. Установка и повторный запуск (Java 21 из Prism вместо `$INST_JAVA`)
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-auto-SHA7 && cd "${W:?}/inst/minecraft"
J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/java"
URL="$(sed -n 's|^PreLaunchCommand=.* \(https://[^ ]*\)$|\1|p' ../instance.cfg)"; echo "сборка: $URL"
timeout 600 "$J" -jar packwiz-installer-bootstrap.jar -g "$URL" > ../run1.log 2>&1; echo "выход 1: $?"; tail -3 ../run1.log
ls mods | grep -c '\.jar$'; ls packwiz-installer.jar packwiz.json; cmp ../mmc-pack.json ../mmc-pack.orig && echo "mmc-pack.json не тронут"
timeout 120 "$J" -jar packwiz-installer-bootstrap.jar -g "$URL" > ../run2.log 2>&1; echo "выход 2: $?"; grep -E "up to date|Finished" ../run2.log
```
