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
| `rideHeight` | metres | 0.1 – 5.0, AND per-car geometry (see below) | Body-centre height above the ground at rest. Also derives a new `restLength` from the car's own spring-equilibrium physics (not a simple ratio scale), so the car's ACTUAL resting height ends up equal to this requested value rather than floating or sinking relative to it. |
| `dampingRate` | N·s/m | 0.0 – 40,000.0 | Suspension damper rate. |
| `antiRollScale` | multiplier | 0.0 – 5.0 | Identity 1.0; see `VehicleSpec#antiRollScale`. |
| `rollingScale` | multiplier | 0.0 – 5.0 | Identity 1.0; soft-ground rolling resistance scale. |
| `handbrakeScale` | multiplier | 0.0 – 5.0 | Identity 1.0; scales the handbrake's brake force (`SableCarBody.tick`'s `HANDBRAKE_FORCE * forceScale() * handbrakeScale()`). Distinct from, and does not touch, the separate live `CarConfig.driftHandbrakeRearGripCut`, which scales the handbrake's lateral-grip cut on drift-tuned cars only. |
| `lsdLockPercent` | fraction | 0.0 – 1.0 | LSD lock, see `VehicleTuning`. Only applies to a car the first time it is ever saved — see "Saved-car precedence" below. |
| `frontTorqueSplit` | fraction | 0.0 – 1.0 | Identity 0.5 (even split). |
| `vectoringGain` | fraction | 0.0 – 1.0 | Torque-vectoring gain. |
| `camberDeg` | degrees | -8.0 – 8.0 | Static camber. |
| `toeDeg` | degrees | -5.0 – 5.0 | Static toe. |

Ranges live in code as `VehicleSpecLimits` (the first five) and the pre-existing
`VehicleTuningLimits` (the last five, shared with the in-game per-entity tuning
command).

**`rideHeight`'s actual accepted range is narrower than 0.1 – 5.0 for every
car**, and differs per car: `VehicleSpecLimits`' 0.1 – 5.0 is a car-agnostic
outer bound, but a value inside it can still be physically unusable for a
specific car's own geometry — too low for its own wheel-mount depth or body
half-height (`WheelMath#isUsableRideHeight`: the requested value must exceed
both `halfY` and the mount's own depth below the body centre). A request that
fails this check falls back to that car's stock `rideHeight`/`restLength`
pair entirely (logged), the same as any other invalid field in this schema —
it is not silently clamped to the nearest usable value. Concretely, as of
this writing the per-car floor is: `car` 0.5, `truck` 0.65, `trophy_truck`
0.5, `drift_car` 0.42, `muscle_car` 0.5, `rock_crawler` 0.6, `monster_truck`
0.75, `indy_car` 0.35, `bus` 0.9, `cargo_truck` 0.75 (each is `max(halfY,
|mountY|)` for that car, both internal geometry constants, never
file-overridable).

### Every existing car's stock default, for every field above

Pinned against `VehicleSpec` by `PerCarConfigDocsDefaultsTest`, which fails
(naming the exact stale cell) if a future ticket changes one of these values
here without updating this table.

| id | rideHeight | dampingRate | antiRollScale | rollingScale | handbrakeScale | lsdLockPercent | frontTorqueSplit | vectoringGain | camberDeg | toeDeg |
|---|---|---|---|---|---|---|---|---|---|---|
| `car` | 0.9 | 3400.0 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `truck` | 1.3 | 5390.0 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `trophy_truck` | 1.6 | 2570.0 | 1.5 | 0.6 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `drift_car` | 0.75 | 3117.0 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `muscle_car` | 0.95 | 4816.67 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `rock_crawler` | 1.9 | 5383.33 | 2.0 | 0.5 | 1.0 | 0.65 | 0.5 | 0.0 | 0.0 | 0.0 |
| `monster_truck` | 2.3 | 4500.0 | 1.0 | 0.55 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `indy_car` | 0.5 | 1841.67 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `bus` | 1.0 | 11900.0 | 1.0 | 0.8 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |
| `cargo_truck` | 1.1 | 13600.0 | 1.0 | 0.7 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 |

`rock_crawler` is the only car with a non-identity `lsdLockPercent` (0.65,
MINECRAFT-296's limited-slip tuning); every other field at its identity value
(1.0 for the four scale multipliers, 0.5 for `frontTorqueSplit`, 0.0 for
everything else) means no existing car has opted into vectoring, camber, toe,
or a non-default handbrake/anti-roll/rolling scale besides `trophy_truck`'s
and `rock_crawler`'s own already-documented anti-roll/rolling tuning above.

Example, retuning just the rock crawler's ride height and LSD lock, leaving
every other field (including its own stock `antiRollScale` of 2.0) as is:

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

### Saved-car precedence (`lsdLockPercent`/`frontTorqueSplit`/`vectoringGain`/`camberDeg`/`toeDeg`)

These five fields are also this mod's existing, pre-this-ticket per-ENTITY
player tuning dials (`CarEntity#setVehicleTuning`, a command). `CarEntity`
unconditionally writes `Tuning*` NBT tags on every save — whether or not a
player ever customized them — and restores from those tags whenever they are
present, never from the spec's current defaults. A config file's values for
these five fields therefore apply to a **newly-spawned** car (no saved tags
yet), but **not retroactively** to a car that was ever saved before the file
existed or changed: that car's own saved values keep winning, by the same
rule that already makes a player's own in-game retune persist across
restarts. This is the pre-existing persistence contract, not something this
ticket changes — see `VehicleSpecOverridesGameTests
#aSavedCarsTuningTagsOutliveALaterConfigOverride` for the regression that
pins it. `rideHeight`/`dampingRate`/`antiRollScale`/`rollingScale`/
`handbrakeScale` carry no per-entity persistence and so have no such
limitation: they always reflect whatever was loaded at the last server start.

**`camberDeg`/`toeDeg` have no effect on a drift-tuned car.** Per
`VehicleTuning`'s own doc comment, camber and toe are read by
`CamberToeGripModel` only through `WheelMath.tire`; a car whose `tireTuning()`
is non-identity (today, only `drift_car`) routes cornering grip through
`DriftTireModel` instead, which does not consult them. This is pre-existing
architecture this ticket does not change — `drift_car.json` can still set
`camberDeg`/`toeDeg` (and they will be clamped and stored), but they will be
inert until a future ticket connects `DriftTireModel` to them.

## `tireKit`: two-rate damping and bump stops (MINECRAFT-445)

MINECRAFT-445 owns the `tireKit` section and has begun filling it. **Only the
fields listed below are implemented**; the rest of that ticket's acceptance
scope (caster, roll center, anti-dive/anti-squat, tire pressure and rear
steering) is not in this section yet, and no key for it is read, validated or
applied — see "Still reserved" at the end of this section.

Every field is optional and nested under a single `tireKit` object. An absent
`tireKit`, or an absent field within it, keeps that car's stock value for
exactly the fields it did not mention, by the same rule as the flat fields
above. Out-of-range values are clamped; non-finite values fall back to stock.

The pre-existing flat `dampingRate` key keeps its meaning as that car's damper
rate, and is now specifically its **slow-compression** rate: every field below
defaults to `dampingRate` itself, so a car with no `tireKit` (or a car that
only overrides `dampingRate`) still damps compression and rebound at one single
rate, exactly as it did before this ticket. `WheelMath.suspensionForce`'s
four-rate overload reduces algebraically to `dampingRate * compressionRate`
whenever all four rates match, for any knee speed — pinned by
`WheelMathSuspensionTuningTest#reducesToSingleRateForAnyKneeSpeedWhenEveryDampingRateMatches`.

| Key | Unit | Range | Notes |
|---|---|---|---|
| `compressionDampingFast` | N·s/m | 0.0 – 40,000.0 | Damping applied to the compression speed ABOVE `dampingKneeSpeed`, on top of (not instead of) the slow rate's own contribution up to the knee — a continuous digressive curve, not a step. Defaults to `dampingRate`. |
| `reboundDampingSlow` | N·s/m | 0.0 – 40,000.0 | Damping on the extending (rebound) stroke at or below `dampingKneeSpeed`. Defaults to `dampingRate`. |
| `reboundDampingFast` | N·s/m | 0.0 – 40,000.0 | Damping on the extending stroke above `dampingKneeSpeed`. Defaults to `dampingRate`. |
| `dampingKneeSpeed` | m/s | 0.0 – 10.0 | The compression-rate magnitude separating "slow" from "fast", for BOTH directions. `0.0` means every speed is "fast". Irrelevant (no observable effect) while all four rates are equal. Defaults to `SuspensionTuning.DEFAULT_DAMPING_KNEE_SPEED`. |
| `bumpStopTravel` | metres | 0.0 – 5.0 | Compression travel beyond which the progressive bump stop engages, on top of the regular spring/damper force. Unlike `rideHeight` this needs no per-car geometry check: a value a car's own suspension can never actually reach is simply inert (the stop never engages), not degenerate. Defaults to `SuspensionTuning.BUMP_STOP_DISABLED_TRAVEL`, which is unreachable, so the stop never engages. |
| `bumpStopRate` | N/m | 0.0 – 500,000.0 | Applied only to the compression PAST `bumpStopTravel`. `SuspensionTuning.NO_BUMP_STOP_RATE` (zero) disables the bump stop regardless of `bumpStopTravel`, and is the default. |
| `tirePressureBar` | bar | 0.5 – 8.0 | Tire pressure. Default is `WheelMath.NOMINAL_TIRE_PRESSURE_BAR` (2.2), every stock car's value, at which both pressure multipliers are exactly 1.0 and grip/rolling resistance are bit-for-bit unchanged. **Below nominal buys grip and costs rolling drag; above nominal does the reverse** — the two effects deliberately pull opposite ways, so it is a trade-off rather than a free win. Grip responds much less than proportionally (exponent `WheelMath.TIRE_PRESSURE_GRIP_EXPONENT` = 0.3, so halving pressure is worth about +23% grip, not +100%), while rolling drag responds inversely and in full. Applied in the production tick at both `gripMu` sites and the `rollingCoefficient` site in `SableCarBody.tick`. |
| `antiDive` | fraction | 0.0 – 1.0 | Anti-dive geometry: how much of a wheel's longitudinal force is carried into the chassis directly instead of through a moment arm about the body centre, **while that force opposes the car's motion** (braking). `0.0` (every stock car) applies the force at the contact patch, which is what makes a car dive — unchanged from before this field. `1.0` cancels the pitch couple entirely. |
| `antiSquat` | fraction | 0.0 – 1.0 | The same, **while the wheel's force drives the car** (acceleration), resisting the tail dropping. Separate from `antiDive` because they are separate geometries on a real car; the production tick picks between them per wheel by whether that wheel's own longitudinal force opposes or drives the motion, so a braking wheel uses `antiDive` even mid-corner. |
| `rearSteerRatio` | fraction | -1.0 – 1.0 | Fraction of the FRONT wheels' steer angle applied to the rear wheels. `0.0` (the default, and every stock car's value) keeps the rear axle straight — exactly what the production code did before this field existed. **Negative is opposite-phase**: the rear steers against the front, tightening the turn (classic low-speed four-wheel steering). **Positive is same-phase**: the rear follows the front, pushing the car sideways rather than rotating it as sharply. This is real physics, not a rendered pose — the angle rotates each tire's own forward axis, which is what the tire's longitudinal/lateral force directions are measured against. |

Ranges live in code as `VehicleSpecLimits`' `MIN/MAX_TIRE_KIT_DAMPING`,
`MIN/MAX_DAMPING_KNEE_SPEED`, `MIN/MAX_BUMP_STOP_TRAVEL` and
`MIN/MAX_BUMP_STOP_RATE`; the resolved values live on
`VehicleSpec.SuspensionTuning`, whose `singleRate(dampingRate)` factory is the
stock (identity) value every car on the roster carries today.

`rearSteerRatio` ranges live in code as `VehicleSpecLimits`'
`MIN/MAX_REAR_STEER_RATIO`, and the resolved value on
`VehicleSpec.SteeringTuning` (whose `IDENTITY` is the no-rear-steering value
every car carries today).

**Every existing car's stock default for this whole section is the identity
one**: `compressionDampingFast`/`reboundDampingSlow`/`reboundDampingFast` all
equal that car's own `dampingRate` from the table above, and the remaining three
fields take the named defaults in the table (`DEFAULT_DAMPING_KNEE_SPEED`, which
is inert at equal rates, plus the two that disable the bump stop),
`rearSteerRatio` is `0.0`, `tirePressureBar` is nominal (2.2), and `antiDive`/
`antiSquat` are both `0.0`. No car has opted into two-rate damping, a bump stop,
rear steering, a non-nominal tire pressure or pitch resistance, so stock driving
feel is unchanged.

Example, giving the trophy truck a soft slow-compression stroke that firms up
over fast hits, a stiffer rebound so it does not pogo on landings, and a bump
stop near the end of its long travel:

```json
{
  "dampingRate": 2000.0,
  "tireKit": {
    "compressionDampingFast": 9000.0,
    "reboundDampingSlow": 4000.0,
    "reboundDampingFast": 11000.0,
    "dampingKneeSpeed": 1.2,
    "bumpStopTravel": 0.45,
    "bumpStopRate": 90000.0
  }
}
```

### Still reserved for downstream tasks

The schema reserves four more top-level keys for the sibling tasks that own
those systems; this loader tolerates them existing in a file today (Gson
ignores any key with no matching field) but does not read, validate or apply
any of them — adding a field under one of these sections with no behaviour
behind it would be "unused config fields," which MINECRAFT-443's acceptance
explicitly rules out and MINECRAFT-445's repeats. Each task adds its own fields
here when it implements real behaviour, rather than inventing a parallel file
or format. The same rule applies within `tireKit` above: its remaining
MINECRAFT-445 fields (caster, roll center) appear there only as that behaviour
lands. Rear steering, tire pressure and anti-dive/anti-squat have now landed and
are documented in the table above.

| Key | Owner |
|---|---|
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
