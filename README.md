# dynamic-vehicles

Drivable vehicles for Minecraft (NeoForge 1.21.1, Java 21). Mod ID:
`dynamicvehicles`. MIT licensed. Built on
[dynamic-terrain](https://github.com/brooswit-minecraft/dynamic-terrain).

**Current state: first car.** A drivable 4-wheel car with a simple physics model that
reports tire slip to Dynamic Terrain. Surface-aware handling (grip, roughness,
rolling resistance, deformability) comes when Dynamic Terrain exposes
`SurfaceProperties` (epic MINECRAFT-62). See epic MINECRAFT-63.

## Architecture principle

**Systems emit generic physical or environmental operations; materials decide
how they respond.** A slipping tire does not know whether it is on sand or
obsidian: it reports slip to Dynamic Terrain (`TireSlip.report`), which decides
whether the surface changes. Vehicle physics asks the contacted surface for
driving properties; it never modifies terrain directly.

## Building

Requires a JDK 21 with `javac` on `JAVA_HOME`.

```sh
./gradlew build
```

The build downloads the pinned Dynamic Terrain jar (`terrain_version` in
`gradle.properties`) from its GitHub release and verifies its sha512 before
compiling against it.

## Contributing

Every PR that changes `src/` adds one `changelog.d/<TICKET>.md` fragment; see
`changelog.d/README.md`. Do not edit the version by hand.
