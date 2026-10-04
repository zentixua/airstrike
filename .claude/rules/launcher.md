---
paths:
  - "mod/src/main/java/ua/zentix/airstrike/launcher/**"
  - "mod/src/main/java/ua/zentix/airstrike/entity/Launcher.java"
  - "mod/src/main/java/ua/zentix/airstrike/entity/LaunchQueue.java"
  - "mod/src/main/java/ua/zentix/airstrike/entity/LauncherMount.java"
  - "mod/src/main/java/ua/zentix/airstrike/strike/LaunchOrigin.java"
  - "mod/src/main/java/ua/zentix/airstrike/util/Magazine.java"
  - "mod/src/main/java/ua/zentix/airstrike/client/render/FixedLauncherRenderer.java"
  - "mod/src/test/java/ua/zentix/airstrike/entity/LaunchQueueTest.java"
  - "mod/src/devtest/java/ua/zentix/airstrike/gametest/FixedLauncherGameTests.java"
---

# Стационарная пусковая (`launcher/`)

## Устройство
- Пусковая — одна логика у прицепа и блока: `entity/Launcher` (пакет `LauncherRack` на опоре `LauncherMount`: прицеп
  `TRAILER` — ось на 1.6 над землёй и 2.2 позади центра, блок `PAD` — на 1.25 над низом блока, над серединой; курс,
  подъём пакета, очередь пусков `LaunchQueue`, доворот только молчащего пакета и только больше чем на 15°).
  `LauncherEntity` (прицеп) и `FixedLauncherBlockEntity` (блок) её реализуют; ячейки, сектор пуска
  (`LaunchSite.clearAhead`/`pickOn` с опорой) и картинка пакета (`LauncherRenderer.renderRack`) — общие.
- `FixedLauncherBlock` + `FixedLauncherBlockEntity`: хозяин — кто поставил (`setPlacedBy`; механизм Create ставит от
  имени своего хозяина — у его `FakePlayer` тот же UUID); запас — `util/Magazine` (тот же, что у ЗРК) на 9 ячеек под
  боеприпасы оружия, которое берёт пусковая (`Mission.accepts`: у оружия есть пакет в паспорте — шахед, «Ланцет»,
  ракета, «Град»; B-2 и МБР — нет), снаружи — только загрузка с любой
  стороны и для `null` (`Capabilities.ItemHandler.BLOCK`: воронка, воронка и жёлоб Create; механическая рука и лента —
  через воронку), сломан — роняет запас; компаратор — по запасу. Задача (`Mission`): оружие, число, разброс, место
  (`Target.Point`/`Ground`; сущность, аппарат, замеченная цель — место, где были при постановке) и точки маршрута.
  Клиенту уходят только оружие пакета, курс и тик подъёма (`getUpdateTag`; задача и запас — только сервер).
- Редстоун — как у раздатчика: передний фронт (`hasNeighborSignal` своего места или над ним) ставит `TRIGGERED` и тик
  через 4 тика, тик — один приказ (`LauncherOrders.fire`); отказ — щелчок `levelEvent(1001)` и причина в строке
  состояния (ПКМ пустой рукой; что сказал сам пуск, например «сектор закрыт», — `report`, точнее общего отказа).
- Пульт: ПКМ пультом по блоку (`useItemOn`, без Shift) — привязать/отвязать (`LauncherLinks`, компонент
  `airstrike:launchers` — список `GlobalPos`, до 16). Пока список не пуст, «Огонь» пульта (`ServerActions.fire`) ставит
  задачу привязанным (`LauncherOrders.assign`): цель — по правилам игрока (`ServerActions.ruled`), каждой — в этом
  мире, в готовом чанке (не грузить ради задачи), своей или своей команды, цель не дальше `map_range` от неё, маршрут
  — в дальности оружия от неё (`routeProblem` с местом пуска `LaunchOrigin.Fixed`).
- Пуск — путь приказа игрока (`ServerActions.fromLauncher`): одиночный — `StrikeService.launch`, залп — `SalvoData`;
  откуда — `strike/LaunchOrigin` (`Place` — место пуска `/airstrike salvo … from x z`, `Fixed` — пусковая; залп хранит
  его, место — прежним ключом `from`, пусковую — `from_launcher`). С `Fixed` пуск идёт только с её пакета
  (`StrikeService.Stand`): курс шахеда и ракеты — `aimFixed` (молчащий пакет доворачивается на путь или поворотом
  `LaunchSite.pickOn` на её опоре, занятый пускает своим курсом), РСЗО и «Ланцета» — `aimedFixed`; сектор закрыт, чанк
  не готов, блока нет — пуск не удался (без захода издалека), причина — `report`. Снаряды — от имени хозяина (свои по
  `/team`, его «Отбой», его предел снарядов в работе); строк и полосы залпа хозяину нет (`Salvo.reporter`).
  Правила хозяина (`designator_for_everyone` или оператор — по профилю, хозяин может быть не в сети; предел
  `max_active_per_player`; цель не дальше `map_range` от пусковой); ничья (поставлена командой) — без правил.
  Запас должен покрывать весь приказ (`Munitions.shortage`), а снимается по снаряду, когда тот встаёт на направляющую
  (`Munitions.pay(Store, Bill)` в `StrikeService`): отменённый залп и неудачный пуск в запасе и остаются, возвращать
  в чанк, который может быть не загружен, нечего. Пусковая занята, пока идёт её залп (`SalvoData.busy`) или пакет не
  молчит (`LaunchQueue.silent`).
- Ведущему и скриптам — `/airstrike launcher <x y z>` (оператор, `AirstrikeCommand.launcher`): состояние, `fire`,
  `mission` (без правил игрока, маршрут — `routeProblem` от пусковой), `clear`, `load N`, `owner`; место — только в
  готовом чанке.

## Подводные камни
- Пакет больше блока: у блок-сущности свой `getRenderBoundingBox` (иначе его отсекает по кубу блока) и
  `getViewDistance` 160; блок непрозрачный — свет пакета берётся над блоком (`LevelRenderer.getLightColor(pos.above())`).
- Залп с пусковой ждёт, пока её чанк не готов (`Terrain.ready`), и кончается, если блока на месте нет: сломанная
  пусковая уже уронила запас. Грузить чанк пусковой ради пуска нельзя.
- В сетке плотов Sable (`SubLevels.inPlotGrid`) пусковая не стреляет: блок аппарата стоит в далёком плоте, и снаряд
  ушёл бы оттуда.
- `getUpdateTag` пишет только то, что видит клиент, а `loadAdditional` читает хозяина, запас и задачу, только если они
  есть в теге: пакет обновления их не стирает.
