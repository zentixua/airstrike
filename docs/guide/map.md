# Targeting map

English · [Русский](ru/map.md)

The map strikes any place up to 10,000 blocks away: behind a hill, in the next town. Open it with `,` or from the
console (target On map, then Open targeting map…).

![Map: pick a place, set a route, fire, projectiles in flight](img/map.gif)

| Action | Key |
|---|---|
| pick a place or another player | LMB |
| pan / zoom | drag with LMB / mouse wheel |
| back to your position | Space |
| add a route point (up to five) | Shift+LMB |
| move / remove a point | drag the point / RMB on it |
| pick a launch site (operators and the host) | Ctrl+LMB, or **Launch site…** and LMB |
| move / remove the launch site | drag its mark / RMB on it |
| fire | Enter or FIRE |
| cancel picking a launch site / back to the console | Esc |

The strike uses the weapon, count and spread set on the [console](designator.md#console). The map shows your
projectiles in flight, your teammates, and other players where they were last [spotted](rules.md#spotting).

## Route

The Shahed, the cruise missile and the Lancet fly through route points: around a SAM, or to come in from the side you
choose. The bottom of the map shows the route length and flight time. A route that is too long turns red and cannot be
fired. Without points the projectile picks its own path. Other weapons do not take a route.

## Launch site

An operator or the world host can choose where the projectiles take off: Ctrl+LMB on the map, or **Launch site…** and
a click. A violet mark appears there. On fire, a launcher sets up at that spot, turns toward the target (or the first
route point) and stays until [Abort](designator.md#abort); the next strikes with the same weapon from that spot use it
again. If the chunk there is not loaded or there is no room for a launcher, the projectiles take off from that spot
without one. The route starts at the launch site, and the bottom of the map shows its distance to the target. The B-2
comes in from that side; the ICBM always takes off from its own site. **Launch from me** removes the launch site, and
strikes launch as usual again.

The launch site works like the route: it applies when you fire from the map or from the console with the target **On
map**, and it stays for the next strikes. It must be within 10,000 blocks of you. A designator with
[linked launchers](launcher.md) does not use it: they launch from where they stand. Other players do not see the
button.

## If the map is empty

Without Distant Horizons the map shows only loaded chunks, but you can still strike a blank spot. The map does not work
in the Nether.
