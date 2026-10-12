---
paths:
  - "mod/src/main/java/ua/zentix/incoming/compat/**"
  - "mod/src/main/java/ua/zentix/incoming/mixin/sable/**"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/world/**"
  - "mod/src/main/java/ua/zentix/incoming/warhead/**"
---

# Sable (аппараты Create Aeronautics)

## Подводные камни
- Sable 2.0.5–2.0.6 роняет мир («Sub-level assembly attempted inside plot of already removed sub-level»), когда аппарат
  после взрыва распадается на много кусков: кусок без массы удаляется, его плот сразу отдаётся следующему, и дробление
  в этом плоте находит удалённый аппарат. Миксин `mixin/sable/SubLevelSplitGuardMixin` (`incoming.mixins.json`,
  `required: false`: не вставший миксин — только предупреждение в логе, поэтому каждый проверяет свой GameTest) оборачивает `SubLevelHeatMapManager.split` и глотает только это
  исключение (`compat/SplitGuard`); GameTest `sableSplitGuardApplied` проверяет, что он встал.
- Sable на каждое изменение блока читает соседние блоки (физика аппаратов), у твёрдого соседа — и его соседей, до 2 блоков
  (`NuclearTickets.SABLE_REACH`): менять блоки только там, где готовы и соседние чанки (`NuclearTickets.neighbourhoodLoaded`,
  `aroundLoaded`), иначе он синхронно грузит соседа.
