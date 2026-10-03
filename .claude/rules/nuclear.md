---
paths:
  - "mod/src/main/java/ua/zentix/airstrike/nuclear/**"
  - "mod/src/main/java/ua/zentix/airstrike/client/nuclear/**"
  - "docs/DESIGN-nuke.md"
  - "mod/src/test/java/ua/zentix/airstrike/nuclear/**"
---

# Ядерный удар (`nuclear/`, `client/nuclear/`)

Руины и всё, что в `nuclear/world/`, — в `nuclear-world.md`.

## Устройство
- `nuclear/` — ядерный удар:
  - `model/` — чистая физика без Minecraft (давление Кинни–Грэма, приход фронта, шар, гриб, свет, проникающая
    радиация, осадки, воронка, лучевая болезнь), юнит-тесты;
  - `Detonation` — один подрыв (всё, из чего сервер и клиенты выводят картинку и последствия), `NuclearEvents`
    (SavedData: подрывы и запланированные удары), `NuclearWarhead` (подрыв; свет и радиация по одной сущности),
    `NuclearStrikes` (пуск МБР, таймер, события мира, синхронизация клиентам);
  - `radiation/` — доза игрока (attachment), `RadiationTicker` (раз в секунду: поле осадков × крыша + заражение),
    эффекты лучевой болезни и ожогов.

## Подводные камни
- Дальняя плоскость отсечения — `renderDistance × 4` блоков: гриб выше рисуется «сжатым» после неба (`NukeRenderer`).
- Пакет подрыва может прийти на тик раньше, чем часы клиента дойдут до `gameTime` подрыва — время не бывает < 0.
- Игровые часы клиента прыгают назад, когда сервер догоняет отставание: время картинки подрыва — свои тики клиента
  от прихода пакета (`ClientNuclear.Active`), а не `level.getGameTime()`.
