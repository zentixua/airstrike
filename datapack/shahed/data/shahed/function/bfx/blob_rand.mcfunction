# случайный центр и размер; форма — «объёмный крест» трёх пересекающихся брусков (скруглённый ком)
$execute store result score #bx shahed run random value -$(rx)..$(rx)
$execute store result score #by2 shahed run random value -$(ry)..$(ry)
$execute store result score #bz shahed run random value -$(rz)..$(rz)
$execute store result score #ba shahed run random value $(rmin)..$(rmax)
$scoreboard players set #oy shahed $(oy)
scoreboard players operation #by2 shahed += #oy shahed
scoreboard players operation #bb shahed = #ba shahed
scoreboard players operation #bb shahed *= #6 shahed
scoreboard players operation #bb shahed /= #10 shahed
execute if score #bb shahed matches ..0 run scoreboard players set #bb shahed 1
scoreboard players operation #q shahed = #bx shahed
scoreboard players operation #q shahed -= #ba shahed
execute store result storage shahed:tmp blob.xa1 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #bx shahed
scoreboard players operation #q shahed += #ba shahed
execute store result storage shahed:tmp blob.xa2 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #bx shahed
scoreboard players operation #q shahed -= #bb shahed
execute store result storage shahed:tmp blob.xb1 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #bx shahed
scoreboard players operation #q shahed += #bb shahed
execute store result storage shahed:tmp blob.xb2 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #by2 shahed
scoreboard players operation #q shahed -= #ba shahed
execute store result storage shahed:tmp blob.ya1 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #by2 shahed
scoreboard players operation #q shahed += #ba shahed
execute store result storage shahed:tmp blob.ya2 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #by2 shahed
scoreboard players operation #q shahed -= #bb shahed
execute store result storage shahed:tmp blob.yb1 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #by2 shahed
scoreboard players operation #q shahed += #bb shahed
execute store result storage shahed:tmp blob.yb2 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #bz shahed
scoreboard players operation #q shahed -= #ba shahed
execute store result storage shahed:tmp blob.za1 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #bz shahed
scoreboard players operation #q shahed += #ba shahed
execute store result storage shahed:tmp blob.za2 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #bz shahed
scoreboard players operation #q shahed -= #bb shahed
execute store result storage shahed:tmp blob.zb1 int 1 run scoreboard players get #q shahed
scoreboard players operation #q shahed = #bz shahed
scoreboard players operation #q shahed += #bb shahed
execute store result storage shahed:tmp blob.zb2 int 1 run scoreboard players get #q shahed
data modify storage shahed:tmp blob.blk set from storage shahed:tmp br.blk
data modify storage shahed:tmp blob.rep set from storage shahed:tmp br.rep
function shahed:bfx/blob with storage shahed:tmp blob
