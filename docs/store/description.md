![Cruise missiles dive onto a factory with tall chimneys and explode one after another](https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/hero.webp)

Air strikes and air defense for Minecraft 1.21.1 on NeoForge, built to real scale: one block is one meter, and the projectiles,
launchers and the effects of a nuclear explosion have their real sizes.

## Weapons

| Weapon | What it does |
|---|---|
| Shahed-style drone | Launched from a trailer next to you, flies for about 50 seconds and dives on the target. Follows a moving target while you keep it in its camera. |
| Lancet-style loitering munition | Circles over the target until you pick what to hit from its camera. |
| Cruise missile | Flies low over the terrain and dives at the end. Can carry a nuclear warhead. |
| Grad-style rocket launcher | A 40-tube trailer that covers an area with unguided rockets. |
| B-2 with a bunker buster | The bomb goes through about 30 blocks of stone and explodes underground. Can carry a nuclear warhead. |
| ICBM | A nuclear warhead of 1 or 15 kt, 90 seconds after launch. |

## How you use it

- The **Strike Designator** aims and launches everything. Hold right-click for its binoculars (up to 400 blocks),
  choose the weapon with the mouse wheel and launch with left-click. Its console sets up salvos of up to 30
  projectiles spread over up to 150 blocks.
- Targets: blocks, mobs, players and Create Aeronautics aircraft, including aircraft in flight. Another player, or an
  aircraft that is not yours, can be targeted only after you or your team have spotted it.
- The **targeting map** strikes anywhere within 10,000 blocks. Drones and the cruise missile can fly through up to
  five waypoints, for example around air defenses.
- The **projectile camera** shows the flight and the hit from the projectile, and lets you pick a new target.
- Every launch uses a crafted munition from your inventory.
- The **fixed launcher** is a block that launches a preset strike on a redstone signal.

## Defense

Players near the target hear an air raid siren and get a warning on screen before the strike arrives. The
surface-to-air missile system is a block that detects drones, cruise missiles and the B-2 by radar and shoots them
down; it is reloaded by hand, with a hopper or with Create machines. The safest place during a strike is
underground.

## Nuclear strike

Flash, fireball, mushroom cloud and a blast wave that ruins buildings as it reaches them, then fires. A surface
burst also leaves a crater and radioactive fallout downwind, with radiation sickness and a Geiger counter to measure
the dose. By default only operators can launch nuclear weapons. The world owner can allow them for everyone; a launch by
a non-operator then needs a second player nearby to confirm it when anyone else is online.

## Also

- **Blackout**: destroying a transformer substation turns off the electric lights in its district for 15 minutes.
- **Sound**: engines and explosions carry for kilometers and travel at the speed of sound, so you see a distant
  explosion before you hear it.
- **Settings**: the world owner sets explosion power, flight times, salvo limits, who may use the designator and
  nuclear weapons, and more.

## Compatibility

No other mods are required. Optional:

- **Create Aeronautics** with **Sable** 2.0.6 or newer: aircraft can be targeted, and explosions break and push them.
- **Create**: munition recipes use Create parts when Create is installed.
- **Distant Horizons** 3.3 or newer: distant terrain on the targeting map and strike damage in distant terrain.
- **Sound Physics Remastered**, **Iris** shaders, **Flashback** replays.

The mod is needed on the server and on every client, in the same version.

## How it is made

The mod is designed by its author and tested in play with friends on the author's server. The code and the texts are
written with Claude Code. The pictures on this page are screenshots from the game.

## Links

- [Player guide](https://github.com/zentixua/incoming/blob/main/docs/guide/README.md)
- [Source code](https://github.com/zentixua/incoming) (GPL-3.0)
