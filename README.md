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

## Releasing to Modrinth

The Modrinth project is created as a **draft** by the `Modrinth draft project create` workflow (manual dispatch only). Run it with `confirm` blank for a dry run that prints the payload; the live create needs the exact string `CREATE-DRAFT-PROJECT`, claims the slug permanently, and uses the org-level `MODRINTH_TOKEN`. Nothing here submits the project for Modrinth review; that is a deliberate human step. After a live create, record the printed project id as the repo variable `MODRINTH_PROJECT_ID`.

## Sable physics (required)

The car is a [Sable](https://modrinth.com/mod/sable) rigid body with per-wheel suspension, so Sable is
a required dependency (the old kinematic model and the `useSablePhysics` config are gone). Sable is
only compiled against, never bundled. **Sable is licensed under the PolyForm Shield License 1.0.0**,
which allows using and depending on it but not shipping something that competes with it; read it before
redistributing anything built on it.

## Sounds

The car has an engine (start and stop sounds, idle, mid and high loops faded and pitched by speed and throttle), impact sounds chosen by how much speed a crash sheds, and tire sounds (rolling noise rough or smooth by surface, a snow loop on snow and ice, light and hard skid loops on slip and hard braking). All are positional, so nearby players hear the car. The client config `soundVolume` scales the looping sounds. Space also sounds the horn. The car sounds were provided by the repository owner; `scripts/convert_sounds.sh` converts the source WAV files to the mono OGG files in `src/main/resources/assets/dynamicvehicles/sounds/car/`.

## Exhaust smoke

Server configuration controls vehicle-local Dynamic Atmosphere exhaust. `exhaustEnabled` defaults to true; `exhaustMultiplier` defaults to 2.5 (0 disables output); `exhaustThrottleStart` defaults to 0.05; `exhaustThrottleCurve` defaults to 1.0 (linear); and `exhaustMaxAmount` defaults to 32. Exhaust emits only while a car is moving at least 2 m/s and throttle is above the configured start. The normalized throttle above that start is raised to the configured curve, scaled, then capped per emission. Emissions are server-authoritative, originate at the rear of the vehicle, and are evaluated once per configured emission interval rather than per physics sub-step. These server TOML values are read on use, so NeoForge config reload applies them without a restart. Tire dust remains a separate effect with its existing settings.

## Radio

Vehicles have a radio: press **N** (or your rebound `key.dynamicvehicles.radio`) while driving to cycle through **Off**, then every station in the server's configured list, in order, then back to **Off**. A steering wheel or gamepad reaches the same action through its own configurable button -- see `wheel.radioButton` in the client config (`-1` by default, disabled; a PlayStation-style pad's Triangle is a common choice once you find its real GLFW button index with `/dvwheel`). Unlike the honk/handbrake paddles, the radio button is edge-triggered: one press cycles one step, holding it down does not repeat. There is no volume control for the radio, by design -- it uses the same sound sliders as every other sound.

The station list is **server-configured**: `radioStations`, an ordered list of internet radio streaming URLs, in the server config (`CarConfig`/`dynamicvehicles-server.toml`). "Off" is always available and is never one of these URLs -- an empty list simply means the radio only ever offers Off. The one default entry is a verified working OC ReMix Icecast stream (`http://ocremixstream.rainwave.cc:8000/ocremix.mp3`; plain HTTP, since that host's HTTPS certificate does not match its hostname).

Playback is entirely **client-side and per-client**: each player's own client connects directly to the selected station's URL and decodes/plays it itself (MP3 over HTTP, via the bundled JLayer decoder) -- the Minecraft server never touches the audio bytes, proxies the stream, or stores anything beyond the URL list and each vehicle's current selection. The selected station and on/off state are synced per vehicle, so everyone nearby hears the *same* vehicle playing the *same* station (shared vehicle audio, not driver-only headphones), each from their own direct connection to that URL. The sound is vehicle-positioned, like the engine and tire sounds above, and stops the instant the vehicle has no driver -- turning the vehicle off never leaves an orphaned stream playing. A station that fails to connect, times out, or is not valid MP3 is logged and silently skipped (no sound, no crash, no leftover thread or socket) rather than retried forever.

# Builder donation trades

The Builder uses a campfire workstation. Its donation trades scale by tier:

| Tier | Material and cost | Output | Villager XP |
| --- | --- | --- | --- |
| 1 | 8 oak logs | 1 stick | 1 |
| 2 | 16 cobblestone | 1 dirt | 5 |
| 3 | 20 sandstone | 1 oak leaves | 10 |
| 4 | 24 bricks | 1 stick | 15 |
| 5 | 28 quartz blocks | 1 dirt | 30 |

Each completed donation uses settlement chance `((villagerLevel - 1) / 4.0) * ((tradeLevel - 1) / 4.0)`; a failed roll or unsafe placement must retain its campfire.
