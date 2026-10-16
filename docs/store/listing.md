# Store listing: Modrinth and CurseForge

Fields to paste when the project pages are created. The long description is [description.md](description.md).

| Field | Value |
|---|---|
| Title | Incoming: Air Strikes and Air Defense |
| Slug | `incoming` |
| Summary | Modern air strikes on a real scale: Shahed drones, Lancets, cruise missiles, Grad rocket salvos, a B-2 bomber and a nuclear ICBM, aimed with one designator. A surface-to-air missile system shoots down drones, cruise missiles and bombers. |
| Loader and version | NeoForge, Minecraft 1.21.1 (NeoForge 21.1.259 or newer) |
| Environment | client: required; server: required (same version on both) |
| Modrinth categories | Equipment, Technology, Game Mechanics |
| CurseForge categories | Armor, Tools, and Weapons; Technology; Miscellaneous |
| License | GPL-3.0-only |
| Content disclosure | Modrinth: "Contains AI-generated content" (the code and the page texts are written with Claude Code). It is set by hand in the project's Settings → Disclosures. CurseForge has no such field |
| Source | https://github.com/zentixua/incoming |
| Issues | https://github.com/zentixua/incoming/issues |
| Wiki / guide | https://github.com/zentixua/incoming/blob/main/docs/guide/README.md |
| Mod id | `incoming` |
| Dependencies | none required; optional: Create, Create Aeronautics + Sable, Distant Horizons |

## Media

The icon is `docs/media/icon.png` (512×512), the same picture as the mod's logo `incoming.png` in the jar. CurseForge
takes a 400×400 avatar and no WebP, so its avatar is `docs/media/icon-400.png`, the same picture scaled down. The page
opens with the loop `docs/media/hero.webp` (960×540), which also heads the README. The gallery, in this order (the
first one is featured):

| File | Caption |
|---|---|
| `docs/media/still-2.jpg` | Cruise missile in flight over a city |
| `docs/media/still-1.jpg` | Cruise missile flying low over the terrain toward its target |
| `docs/media/still-3.jpg` | Cruise missile in its final dive onto a burning factory |
| `docs/media/still-4.jpg` | The warhead explodes inside the factory |
| `docs/media/still-5.jpg` | Cruise missiles dive onto a factory: the first one has just hit |
| `docs/media/still-6.jpg` | More hits on the factory as another cruise missile comes in |
| `docs/guide/img/nuke-wave.jpg` | Fireball and blast wave of a 15 kt airburst over a city |
| `docs/guide/img/nuke.jpg` | Mushroom cloud; the dust wall of the blast wave below |
| `docs/guide/img/nuke-ruins.jpg` | A city after a 15 kt strike: ruins and fires |

## Publishing

The workflow `.github/workflows/publish.yml` creates the Modrinth page and uploads the jar of a GitHub release. It
runs only when started by hand. [modrinth.json](modrinth.json) holds the Modrinth fields from the table above, the
icon path and the gallery with captions; change it together with this file.

Secrets and variable, in Settings → Secrets and variables → Actions:

- `MODRINTH_TOKEN`: New repository secret. A Modrinth personal access token with the scopes Create projects, Read
  projects, Write projects, Create versions and Read versions.
- `CURSEFORGE_TOKEN`: New repository secret. A CurseForge API token for file uploads.
- `CURSEFORGE_ID`: Variables tab → New repository variable. The project ID shown on the CurseForge project page.

Without `MODRINTH_TOKEN` the workflow skips Modrinth, and without `CURSEFORGE_TOKEN` or `CURSEFORGE_ID` it skips
CurseForge. The run does not fail because of that.

To start it: Actions → publish → Run workflow, choose the step, enter the version (for example `3.0.0`; empty means
the latest GitHub release) and press Run workflow.

- `draft`: creates the Modrinth project as a draft, or updates the draft: the fields, the page text from
  [description.md](description.md), the icon `docs/media/icon.png` and the gallery images from `modrinth.json` that
  exist in the repository, and the jar. Only the project members see a draft and its versions. Run it again after
  changing the images or the texts: it uploads only new images and skips a jar that is already uploaded.
- `submit`: sends the Modrinth draft for review and uploads the jar to CurseForge. On CurseForge the first file sends
  a new project for review. Turn on the Modrinth content disclosure by hand before the first `submit`.
- `release`: for later versions, after Modrinth has approved the project: updates the Modrinth page and uploads the
  jar to Modrinth and CurseForge.

Each uploaded file lists the optional mods as dependencies: Create, Create Aeronautics, Sable and Distant Horizons on
Modrinth, and the same without Create Aeronautics on CurseForge, which has no project with the slug
`create-aeronautics`.
An image removed from `gallery` is removed from the site on the next `draft` or `release`; captions of uploaded
images are changed on the site. Running `submit` or `release` twice for the same version uploads the file to
CurseForge twice.

A CurseForge project can only be created on the site. Create it by hand first with the fields above (title, summary,
CurseForge categories, license, links, the avatar `docs/media/icon-400.png`, and description.md as the description),
then add its ID as `CURSEFORGE_ID`.
