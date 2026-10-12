# Strike rules

English · [Русский](ru/rules.md)

By default the designator works for every player, and nuclear weapons only for operators ([nuclear strike](nuke.md)).

## Spotting

Places, mobs and your own side can always be struck. **Another player** and **another player's aircraft** can be
struck only if you or your team have spotted them: with your own eyes or through the camera of your projectile.

- If you see the target now, the strike goes at it. If it was seen within the last 30 seconds, the strike goes to the
  place where it was seen.
- The designator refuses a target nobody has spotted and says that the player has not been spotted.
- A team shares its sight: once one member spots someone, anyone on the team can strike.
- If you hide behind a wall or go over the horizon, the other side loses you after 30 seconds.

An operator sets up teams: `/team add <name>`, then `/team join <name> <players>`. Without a team, your own side is you
alone.

## Moving targets

Only the Shahed and the Lancet follow a moving target, and only while you keep it in frame in their
[camera](camera.md). Other weapons hit where the target was at launch.

## Limits

Up to 30 rounds in a salvo, spread up to 150 blocks, binoculars up to 400 blocks, the map up to 10,000 blocks. These
are defaults that the world owner can change. How many strikes you can make is limited by your munitions.
