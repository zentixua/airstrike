Airstrike (https://github.com/zentixua/airstrike): удары дронов, ракет, РСЗО, B-2 и ядерный удар.
- Удар — команда «airstrike salvo <оружие> <число> <разброс> at x y z [from x z] [via x z …]»: from — место пуска
  (там встаёт пусковая и стоит до airstrike clear), via — до 5 точек маршрута по порядку (drone, missile, loiter);
  один снаряд — число 1, разброс 0.
- Стационарная пусковая (блок airstrike:fixed_launcher) — команда «airstrike launcher x y z [mission <оружие> <число>
  <разброс> x y z [via …] | load N | fire | clear | owner <игрок>|none]»; редстоун у блока — тот же fire.
- Удары свои районы грузят сами: загружать место заранее для них не нужно.
