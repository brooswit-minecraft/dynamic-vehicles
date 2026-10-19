# Per-car config files (MINECRAFT-443)

Owner audit (MINECRAFT-395 comment 48329) found no per-car config files anywhere
in this repo: every one of the 10 existing cars (`car`, `truck`, `trophy_truck`,
`drift_car`, `muscle_car`, `rock_crawler`, `monster_truck`, `indy_car`, `bus`,
`cargo_truck`) was a hardcoded Java `VehicleSpec` constant. This ticket adds an
optional JSON override file per car, loaded by `VehicleSpecOverrides` and
resolved into `CarEntity`.

## File location and timing

`config/dynamicvehicles/cars/<id>.json`, one optional file per car id (the same
id the entity/item registry uses — see `VehicleIds` and the `ENTITIES.register`
calls in `DynamicVehiclesMod`).

Read **once, at server start** (a `ServerStartingEvent` listener calls
`VehicleSpecOverrides.load`), same as every other `SERVER`-type config value in
this mod before this ticket (`CarConfig`'s own `toml`, excepting the two fields
it already documents as read live: `driftHandbrakeRearGripCut`'s neighbours and
`RADIO_STATIONS`). Editing a car's file while the server is running has no
effect until the next server start. This is a deliberate scope boundary, not an
oversight — see MINECRAFT-443's ticket discussion.

**Server-authoritative, not synced.** The resolved spec is only ever used
where `CarEntity` picks its physics spec; like every other config value here,
it lives on the server (or the integrated server a singleplayer world runs) and
is never sent to a remote client. A dedicated server's clients see this
server's physics outcome through the usual entity-state sync, not the file
itself.

**Missing file or a missing/invalid field inside it always falls back to that
car's stock default** — a server with no files at all behaves byte-for-byte as
it did before this ticket. A field present but out of range is clamped, not
rejected; a field that is present but not a finite number (or a whole file that
fails to parse as JSON) falls back entirely, logged once, with every other
car's file loaded independently (one corrupt file never affects another car).

## Schema

Every field is optional; an absent field keeps that car's stock value.

| Key | Unit | Range | Notes |
|---|---|---|---|
| `rideHeight` | metres | 0.1 – 5.0 | Body-centre height above the ground at rest. |
| `dampingRate` | N·s/m | 0.0 – 40,000.0 | Suspension damper rate. |
| `antiRollScale` | multiplier | 0.0 – 5.0 | Identity 1.0; see `VehicleSpec#antiRollScale`. |
| `rollingScale` | multiplier | 0.0 – 5.0 | Identity 1.0; soft-ground rolling resistance scale. |
| `lsdLockPercent` | fraction | 0.0 – 1.0 | LSD lock, see `VehicleTuning`. |
| `frontTorqueSplit` | fraction | 0.0 – 1.0 | Identity 0.5 (even split). |
| `vectoringGain` | fraction | 0.0 – 1.0 | Torque-vectoring gain. |
| `camberDeg` | degrees | -8.0 – 8.0 | Static camber. |
| `toeDeg` | degrees | -5.0 – 5.0 | Static toe. |

Ranges live in code as `VehicleSpecLimits` (the first four) and the pre-existing
`VehicleTuningLimits` (the last five, shared with the in-game per-entity tuning
command). Example, retuning just the rock crawler's ride height and LSD lock,
leaving every other field (including its own stock `antiRollScale` of 2.0) as
is:

```json
{
  "rideHeight": 2.1,
  "lsdLockPercent": 0.8
}
```

### Not covered by this ticket

Geometry (`halfX`/`halfY`/`halfZ`, wheel mounts/radius/width, wheelbase, seats),
mass, drive force/top speed, engine pitch, tire/downforce tuning and loose-grip
are not file-configurable — they define a car's identity, not its stock tuning,
and changing them here would risk the "preserve identity and currently
enjoyable stock driving feel" requirement this ticket is built around.

### Reserved for downstream tasks

The schema reserves five top-level keys for the sibling tasks that own those
systems; this loader tolerates them existing in a file today (Gson ignores any
key with no matching field) but does not read, validate or apply any of them —
adding a field under one of these sections with no behaviour behind it would
itself be "unused config fields," which this ticket's acceptance explicitly
rules out. Each task adds its own fields here when it implements real
behaviour, rather than inventing a parallel file or format:

| Key | Owner |
|---|---|
| `tireKit` | MINECRAFT-445 (suspension/wheel geometry) |
| `wing` | MINECRAFT-446 (downforce/drag) |
| `diffuser` | MINECRAFT-447 (orientation assists) |
| `computer` | MINECRAFT-448 (assists/auto-shift) |
| `engine` | MINECRAFT-449 (engine limits/turbo/nitrous/ballast) |

## Standard vs. sport

No existing car in this roster is a "sport" variant of another; this ticket
does not add one. Per the inherited story spec, a future sport variant must
reuse the same car's engine/hardware fields (outside this schema's reach
entirely) and differ only through `computer`/auto-shift/assist settings
(MINECRAFT-448's section above) — never through this file's own fields.
