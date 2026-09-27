# случайный центр и размер; форма — «объёмный крест» трёх пересекающихся брусков (скруглённый ком)
$execute store result score #bx airstrike run random value -$(rx)..$(rx)
$execute store result score #by2 airstrike run random value -$(ry)..$(ry)
$execute store result score #bz airstrike run random value -$(rz)..$(rz)
$execute store result score #ba airstrike run random value $(rmin)..$(rmax)
$scoreboard players set #oy airstrike $(oy)
scoreboard players operation #by2 airstrike += #oy airstrike
scoreboard players operation #bb airstrike = #ba airstrike
scoreboard players operation #bb airstrike *= #6 airstrike
scoreboard players operation #bb airstrike /= #10 airstrike
execute if score #bb airstrike matches ..0 run scoreboard players set #bb airstrike 1
scoreboard players operation #q airstrike = #bx airstrike
scoreboard players operation #q airstrike -= #ba airstrike
execute store result storage airstrike:tmp blob.xa1 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #bx airstrike
scoreboard players operation #q airstrike += #ba airstrike
execute store result storage airstrike:tmp blob.xa2 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #bx airstrike
scoreboard players operation #q airstrike -= #bb airstrike
execute store result storage airstrike:tmp blob.xb1 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #bx airstrike
scoreboard players operation #q airstrike += #bb airstrike
execute store result storage airstrike:tmp blob.xb2 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #by2 airstrike
scoreboard players operation #q airstrike -= #ba airstrike
execute store result storage airstrike:tmp blob.ya1 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #by2 airstrike
scoreboard players operation #q airstrike += #ba airstrike
execute store result storage airstrike:tmp blob.ya2 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #by2 airstrike
scoreboard players operation #q airstrike -= #bb airstrike
execute store result storage airstrike:tmp blob.yb1 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #by2 airstrike
scoreboard players operation #q airstrike += #bb airstrike
execute store result storage airstrike:tmp blob.yb2 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #bz airstrike
scoreboard players operation #q airstrike -= #ba airstrike
execute store result storage airstrike:tmp blob.za1 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #bz airstrike
scoreboard players operation #q airstrike += #ba airstrike
execute store result storage airstrike:tmp blob.za2 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #bz airstrike
scoreboard players operation #q airstrike -= #bb airstrike
execute store result storage airstrike:tmp blob.zb1 int 1 run scoreboard players get #q airstrike
scoreboard players operation #q airstrike = #bz airstrike
scoreboard players operation #q airstrike += #bb airstrike
execute store result storage airstrike:tmp blob.zb2 int 1 run scoreboard players get #q airstrike
data modify storage airstrike:tmp blob.blk set from storage airstrike:tmp br.blk
data modify storage airstrike:tmp blob.rep set from storage airstrike:tmp br.rep
function airstrike:bfx/blob with storage airstrike:tmp blob
