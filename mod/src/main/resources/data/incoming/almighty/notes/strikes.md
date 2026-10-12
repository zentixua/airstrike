Incoming (https://github.com/zentixua/incoming): strikes by drones, missiles, MLRS, a B-2, and a nuclear strike.
- A strike is the command «incoming salvo <weapon> <count> <spread> at x y z [from x z] [via x z …]»: from is the
  launch site (a launcher stands there until incoming clear), via is up to 5 waypoints in order (drone, missile,
  loiter). A single projectile is count 1, spread 0.
- Fixed launcher (block incoming:fixed_launcher): «incoming launcher x y z [mission <weapon> <count> <spread> x y z
  [via …] | load N | fire | clear | owner <player>|none]». A redstone signal at the block does the same as fire.
- Strikes load their own areas: there is no need to load the place in advance.
- Routes: drone (shahed) flies 45 blocks above the launch site and the target, loiter (lancet) circles 45 blocks above
  the target, missile flies 12 blocks above the terrain; all three follow via. rocket (grad) is ballistic and bunker is
  a bomb from a B-2: they take no via, and a command with via fails.
- incoming clear removes all launchers and every projectile in flight at once: an emergency stop. To stop a campaign,
  remove its rule first.
- A campaign (a salvo every N seconds) is a tick rule with name, every (at least 100 ticks) and limit (rule runs; pauses
  count too). In the script, gm.command with the whole command as one string, numbers as numbers. Pause while mspt ≥ 35
  or ≥ 12 000 chunks are loaded (server.overworld().getChunkSource().getLoadedChunksCount()); every 20 salvos, kill the
  item entities within 200 blocks of the target. Stop it with rule remove.
- The server handles about 200 projectiles at once without noticeable lag; 750 landing at once crashed a player's client.
