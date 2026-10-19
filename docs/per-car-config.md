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

Read **once, at server start** — a `ServerAboutToStartEvent` listener calls
`VehicleSpecOverrides.load` before any level loads, so the override table is
populated before a single saved car can be deserialized from world data.
Editing a car's file while the server is running has no effect until the next
server start. This is **narrower** than most of `CarConfig`'s own existing
`toml` values: NeoForge's `ModConfigSpec` already re-reads most of those live
on every `.get()` call (an edited file takes effect without a restart — see
`RADIO_STATIONS` and the drift tuning values' own doc comments, which call
this out explicitly). This loader's start-only behavior is a new, deliberate
scope boundary unique to this per-car format, not a claim that the rest of
this mod's config already worked this way — see MINECRAFT-443's ticket
discussion for why this one stays start-only (no implemented reload test).

**Server-authoritative, not synced.** `CarEntity` only consults a per-car
override on its logical-server side (`!level.isClientSide()`, which includes a
singleplayer world's own integrated server); a logical client always uses the
plain stock spec. This is deliberate, not just "physics are server-decided
anyway": `VehicleSpecOverrides`' table is one process-global map, so a client
that previously hosted/played a singleplayer world with overrides loaded, then
joins a remote server in that same client session, must not keep reading that
now-stale table.

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
| `rideHeight` | metres | 0.1 – 5.0 | Body-centre height above the ground at rest. Also scales `restLength` by the same ratio, so the suspension's rest geometry moves with it instead of leaving the car floating at its old height or a spring that cannot reach the new one. |
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

**Handbrake is explicitly NOT migrated.** The acceptance list names it, but
there is no per-car handbrake *strength* field in the existing baseline to
migrate: `SableCarBody.tick`'s handbrake behavior (`HANDBRAKE_FORCE`, a
`0.35` lateral-grip multiplier) is one hardcoded constant shared by every car,
scaled only by `forceScale` (not file-configurable). The one handbrake-shaped
field that does exist per vehicle, `TireTuning#handbrakeRearGripCut`, belongs
only to drift-tuned cars and is already explicitly owned by the live
`CarConfig.driftHandbrakeRearGripCut` value, not a per-car file, per that
field's own doc comment. Rather than invent a new strength field with no
existing baseline behind it, this is flagged here for confirmation instead —
see the ticket's own instruction to report missing design decisions rather
than invent them.

**`camberDeg`/`toeDeg` have no effect on a drift-tuned car.** Per
`VehicleTuning`'s own doc comment, camber and toe are read by
`CamberToeGripModel` only through `WheelMath.tire`; a car whose `tireTuning()`
is non-identity (today, only `drift_car`) routes cornering grip through
`DriftTireModel` instead, which does not consult them. This is pre-existing
architecture this ticket does not change — `drift_car.json` can still set
`camberDeg`/`toeDeg` (and they will be clamped and stored), but they will be
inert until a future ticket connects `DriftTireModel` to them.

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
