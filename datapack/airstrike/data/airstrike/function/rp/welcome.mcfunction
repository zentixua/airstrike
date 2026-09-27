# игрок со старой версии (shahed) — перенести его выбор, без повторного приветствия
execute if score @s shahed_seen matches 1 run return run function airstrike:migrate/player
scoreboard players set @s airstrike_seen 1
execute unless score @s airstrike_mode matches 0.. run scoreboard players set @s airstrike_mode 0
scoreboard players enable @s airstrike_rp
function airstrike:rp/prompt
