# Settings and keys

English · [Русский](ru/settings.md)

Settings open with the Settings button on the console or under Mods → Incoming → Config. Every option has a tooltip.

## Your settings

Each player has their own, stored in `config/incoming-client.toml`.

| Setting | Default | What it does |
|---|---|---|
| Camera shake | 1 | 0: no shake |
| Flash brightness | 1 | 0: no on-screen flash from explosions |
| Show strikes in flight | on | list of your projectiles and marks in the world |
| Projectile camera on launch | off | the camera shows every launch by itself |
| Binocular zoom | 4 | from 1.5 to 10 |
| Mushroom cloud detail | MEDIUM | lower is lighter on a weak computer |
| Nuclear dust pall | MEDIUM | dust and smoke over the area after a nuclear blast; lower is lighter on a weak graphics card, OFF turns it off |
| Deafness and ringing ears | on | muffled hearing after a nearby blast wave |

The mod's sounds follow the Ambient/Environment volume slider.

## Keys

Options → Controls → Key Binds → Incoming: launch in the binoculars (LMB), projectile camera (`N`), targeting map
(`,`), second key for a nuclear launch (`.`), open console (unbound by default). The binoculars and the console use the
regular Use Item key (RMB and Shift+RMB).

## World settings

The world owner changes these: in singleplayer in the file `saves/<world>/serverconfig/incoming-server.toml`, on a
server its owner does. The most useful ones are below; the rest are in the same file, each with a comment.

| Key | Default | What it does |
|---|---|---|
| `launch.designator_for_everyone` | on | the designator works for all players, not only operators |
| `launch.max_salvo`, `max_spread` | 30, 150 | largest salvo and spread |
| `launch.max_active_per_player` | 0 | how many of one player's rounds may be in progress; 0: no limit |
| `launch.sight_rules` | on | [spotting](rules.md#spotting): strike only spotted targets |
| `warheads.*_power` | 12, 20, 7, 8, 20 | blast power of the Shahed, missile, Grad, Lancet, bomb (TNT is 4) |
| `world.block_damage`, `fire` | on | destruction and fires |
| `nuclear.enabled`, `ops_only` | on, on | nuclear weapons exist; only operators launch them |
| `nuclear.effects_scale` | 1.0 | scale of nuclear strike radii |
| `nuclear.wave_speed` | 0.42 | speed of the blast wave away from the fireball, as a share of the speed of sound (1.0 is true to life) |
| `grid.enabled` | on | [blackout](blackout.md) |
