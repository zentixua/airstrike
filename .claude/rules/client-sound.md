---
paths:
  - "mod/src/main/java/ua/zentix/airstrike/client/sound/**"
  - "mod/src/main/resources/assets/airstrike/sounds/**"
  - "mod/src/main/resources/assets/airstrike/sounds.json"
  - "tools/build_sounds.py"
  - "tools/synth_mod_sounds.py"
  - "tools/freesound.py"
  - "mod/src/test/java/ua/zentix/airstrike/client/sound/**"
---

# Звук

## Подводные камни
- Фильтр OpenAL (`SoundFilters`: воздух, преграды, оглушение) ставится в `PlaySoundSourceEvent` (звуковой поток) и
  остаётся на источнике до конца звука, поэтому чужие петли не трогаем, а моторы снарядов обновляем каждый тик через
  `SoundEngine.instanceToChannel`; `Channel.source`, `SoundManager.soundEngine`, `instanceToChannel` открыты AT
  (`META-INF/accesstransformer.cfg`). После перезапуска звукового движка фильтр — заново. С Sound Physics Remastered
  воздух и преграды — его, наше только оглушение.
- Звуки — только моно (стерео Minecraft не размещает в пространстве); исключение — эхо взрыва `blast.tail`, оно
  играется без места (`ClientSounds.around`); `SoundAssetsTest` это проверяет. libsndfile
  падает, если писать Vorbis одним большим блоком, — `build_sounds.py` пишет кусками.
- Громче файла Minecraft звук не играет (громкость не больше 1), поэтому громкость взрывов — в самих файлах: ракурсы
  сведены по EBU R128 с запасом пиков, как у ванильного TNT (≈ 10 дБ между пиком и громкостью), а не пережаты мягким
  ограничителем (прежние взрывы — запас 5–8 дБ, «звук старой игры»). Взрывы и разовые звуки, которые играются в тик
  прихода фронта, — с `"preload": true` (иначе первый раз файл разбирается при запуске) и с ударом с первого отсчёта.
  Ракурсы одного взрыва — одно зерно (`RandomSource.create(seed)`: ваниль выбирает вариант `nextInt` по весам), поэтому
  у `blast.near`/`mid`/`far` вариантов поровну (`SoundAssetsTest`). Петли моторов — тоже по EBU R128 и тише ближнего
  ракурса взрыва своего снаряда (`build_sounds.py`: `*_LUFS`; пережатые петли были громче взрывов). Петля — из ровной
  записи двигателя на месте, не из пролёта: тон пролёта плывёт от Доплера, и петля из него каждый круг прыгала тоном
  обратно; склейка коротких кусков дрожала громкостью («шорох» дальней ракеты). `engine()` в сборке проверяет
  ровность (СКО мгновенной громкости ≤ 1 дБ); где ровной записи нет — шум со средним спектром записей (`noise_loop`).
