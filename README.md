# dynamic-vehicles

Drivable vehicles for Minecraft (NeoForge 1.21.1, Java 21). Mod ID:
`dynamicvehicles`. MIT licensed. Built on
[dynamic-terrain](https://github.com/brooswit-minecraft/dynamic-terrain).

**Current state: scaffold only.** This repo ships an empty mod plus the Gradle
project, CI and release gate. The first vehicle will be a 4-wheel car (epic
MINECRAFT-63).

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
