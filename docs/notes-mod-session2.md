# Заметки облачной сессии (27.09.2026), для продолжения перевода в мод

- Каркас `mod/` (ModDevGradle 2.0.147, NeoForge 21.1.250, Parchment 2024.11.17) собирается: `./gradlew build` — BUILD SUCCESSFUL.
- В облачном окружении сеть до maven.neoforged.net, maven.createmod.net, maven.ryanhcode.dev, Mojang, parchment — есть.
- GitHub Actions в репо не работают (задачи падают за ~3 с без логов) — собирать локально.
- Sable 2.0.5 (`dev.ryanhcode.sable:sable-neoforge-1.21.1`) в POM зависит от Create 6.0.10-280, Ponder, Flywheel, Registrate, Veil 4.3.2.
- Для аддона удобнее **sable-companion 1.6.0** (MIT, уже вшит в Sable; без Sable работает `DefaultSableCompanion`-заглушка):
  `SableCompanion.INSTANCE.getContaining(level, pos)` → `SubLevelAccess` (`getUniqueId`, `logicalPose()`, `boundingBox()`),
  `Pose3dc.transformPosition/transformPositionInverse(Vec3)` (плот ↔ мир), `projectOutOfSubLevel(level, Vec3)`,
  `getVelocity(level, pos)` — скорость аппарата в точке (для упреждения), `getAllIntersecting(level, box)`.
- Импульс ударной волны по аппарату — только полный Sable: `RigidBodyHandle.of(ServerSubLevel).applyImpulseAtPoint(Vec3, Vec3)`;
  `SubLevelContainer.getContainer(ServerLevel).getSubLevel(UUID)`.
