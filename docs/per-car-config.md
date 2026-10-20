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
| `engineInertia` | kg·m² | 0.01 – 5.0 | MINECRAFT-444. Crankshaft rotational inertia about its own axis: how fast the engine's own speed can change for a given net torque, and the inertia the engine-axis chassis reaction is computed against. The low bound is not 0: this value is a divisor in `DrivetrainMath.engineAngularVelocity`. |
| `enginePeakTorque` | N·m | 0.0 – 10,000.0 | MINECRAFT-444. Torque at full throttle before the clutch. This is what sets the car's actual drive force through the engaged ratio — the stock value is calibrated so top gear lands on the pre-existing tuned `DRIVE_FORCE` (see that constant's own javadoc), so changing it retunes acceleration in every gear proportionally. |
| `engineBrakingTorque` | N·m | 0.0 – 5,000.0 | MINECRAFT-444. Closed-throttle drag the engine applies against its own rotation. Reaches the wheels through the clutch, so raising it makes coasting in gear slow the car harder; 0.0 is a freewheeling engine with no engine braking at all. |
| `clutchTorqueCapacity` | N·m | 0.0 – 20,000.0 | MINECRAFT-444. How much torque the clutch transmits before it slips. The stock value sits above peak net engine torque, so a healthy engaged clutch is never capacity-limited; lowering it toward (or below) peak torque makes the clutch slip under load, which bounds the wheel torque the throttle can actually deliver. 0.0 is a clutch that transmits nothing (the car never drives). |

Ranges live in code as `VehicleSpecLimits` (the first five and the last four)
and the pre-existing `VehicleTuningLimits` (the five `VehicleTuning` dials,
shared with the in-game per-entity tuning command).

**The four `engine*`/`clutch*` fields are MINECRAFT-444's own coupling
parameters, not MINECRAFT-449's engine scope.** They are deliberately flat
top-level keys rather than entries under the reserved `engine` section below:
444 implements the physical engine/clutch coupling (inertia, clutch torque
transfer, engine braking, engine-axis reaction) and so owns its own settings,
while 449 keeps ownership of the rev-limit thresholds, turbo, nitrous and
ballast and adds those under `engine` when it implements them. Every car on the
roster ships the same stock values (`VehicleSpec.DrivetrainCoupling.STOCK`,
i.e. `DrivetrainMath`'s own constants) — shared stock engine hardware, exactly
as before this field existed — which is why the defaults table below has one
identical column per field rather than per-car variation.

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

| id | rideHeight | dampingRate | antiRollScale | rollingScale | handbrakeScale | lsdLockPercent | frontTorqueSplit | vectoringGain | camberDeg | toeDeg | engineInertia | enginePeakTorque | engineBrakingTorque | clutchTorqueCapacity |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `car` | 0.9 | 3400.0 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `truck` | 1.3 | 5390.0 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `trophy_truck` | 1.6 | 2570.0 | 1.5 | 0.6 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `drift_car` | 0.75 | 3117.0 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `muscle_car` | 0.95 | 4816.67 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `rock_crawler` | 1.9 | 5383.33 | 2.0 | 0.5 | 1.0 | 0.65 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `monster_truck` | 2.3 | 4500.0 | 1.0 | 0.55 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `indy_car` | 0.5 | 1841.67 | 1.0 | 1.0 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `bus` | 1.0 | 11900.0 | 1.0 | 0.8 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |
| `cargo_truck` | 1.1 | 13600.0 | 1.0 | 0.7 | 1.0 | 0.0 | 0.5 | 0.0 | 0.0 | 0.0 | 0.18 | 930.0 | 55.0 | 1200.0 |

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

### `wing` (MINECRAFT-446): downforce and aerodynamic drag

Genuine aerodynamic forces in the real rigid-body path (`SableCarBody.tick`)
— distinct from `downforceGripPerSpeed` (MINECRAFT-184, listed under "Not
covered by this ticket" above), which is a tire-grip multiplier with no
downward force or moment at all, and left exactly as it is: it is a separate,
already-shipped mechanism this ticket does not touch or supersede.

| Key | Unit | Range | Notes |
|---|---|---|---|
| `wing.frontDownforceCoeff` | coefficient, per (m/s)^2 of FORWARD speed (see formula below) | 0.0 – 5.0 | Downward force at the front axle's own mount point. Identity/stock default `0.0` (no wing) for every existing car. |
| `wing.rearDownforceCoeff` | coefficient, per (m/s)^2 of FORWARD speed | 0.0 – 5.0 | As `frontDownforceCoeff`, applied at the rear axle's own mount point instead. Identity/stock default `0.0`. |
| `wing.dragCoeff` | coefficient, per (m/s)^2 of TOTAL speed | 0.0 – 5.0 | Aerodynamic drag opposing the car's own full 3D velocity vector (`subLinear.length()` in `SableCarBody.tick` — the body's total speed, not just its forward component), combined with the existing fluid/foliage drag coefficients at the exact point in `SableCarBody.tick` that already applied air drag. Stock default `0.4` for every existing car: the old hardcoded, global `AIR_DRAG` constant every vehicle shared before this field existed, now per-car configurable. |

### Exact applied formulas

**These coefficients are not literal newtons-per-(m/s)^2.** Review fix
(MINECRAFT-446 comment 49064): an earlier version of this section said "N per
(m/s)^2", which did not match what the production path applies. Every force in
`SableCarBody.tick` — drive, brake, handbrake, drag, and now wing downforce —
is scaled by the vehicle's own `forceScale()`, and drag additionally carries a
low-speed dead band. Stated exactly, per tick, with `v` the body's velocity
and `vf = v · forward` its forward component:

```
front downforce (N, world-down, at the front axle's mount point):
    frontDownforceCoeff * vf^2 * forceScale()
rear downforce  (N, world-down, at the rear axle's mount point):
    rearDownforceCoeff  * vf^2 * forceScale()
drag            (N, opposing v exactly, at the centre of mass):
    (dragCoeff + fluidDrag + foliageDrag) * forceScale() * |v| * v
    — and exactly zero while |v| <= SableCarBody.DRAG_SPEED_DEADBAND (0.5 m/s)
```

Consequences worth being explicit about, since both surprised a reviewer:

- **`forceScale()` multiplies the configured coefficient.** It ranges from
  `1.0` (only `car` and `drift_car`) up to `3.0` (`bus`) across the roster, so
  the same configured coefficient produces up to 3x the force on different
  cars. Read each car's own value from `VehicleSpec#forceScale`. This is
  deliberate consistency with every other force in that method — notably the
  pre-existing air drag, which was always scaled this way — not an oversight,
  but it does mean a coefficient is a per-car-relative knob rather than an
  absolute force figure.
- **Drag has a 0.5 m/s dead band; downforce does not.** Below that total
  speed no drag impulse is applied at all (pre-existing behaviour, preserved
  byte-for-byte). Wing downforce has no dead band: it applies at any nonzero
  forward airflow speed, and is exactly zero at a standstill only because the
  `vf^2` term is.

Both are pinned against drift by `WingAeroGameTests`: the applied-force test
reads the production path's own recorded aero telemetry and checks it against
`coeff * vf^2 * forceScale()` on a car whose `forceScale` is deliberately NOT
`1.0` (so dropping the multiplication fails the test rather than passing
unnoticed, as it would on `car`), and a separate assertion pins the documented
`0.5` dead band against `SableCarBody.DRAG_SPEED_DEADBAND` itself.

Ranges live in code as `VehicleSpecLimits.MIN_WING_DOWNFORCE_COEFF`/
`MAX_WING_DOWNFORCE_COEFF` and `MIN_WING_DRAG_COEFF`/`MAX_WING_DRAG_COEFF`,
clamped the same way as every other scale field in this schema (a finite
out-of-range value is clamped, not rejected; a missing or non-finite value
falls back to that car's stock value — `0.0`/`0.0`/`0.4`). Read and applied
on exactly the same "once, at server start" timing as every other field in
this schema (see "File location and timing" above) — there is no separate
timing for this section.

**Front/rear split produces a genuine pitch moment, not just a vertical
force**: each coefficient's force is applied as an impulse at that axle's
own mount point (averaged across its wheels), off the car's centre of mass,
exactly like the existing per-wheel suspension/tire forces already are — a
front-heavy wing config pitches the nose down under speed, a rear-heavy one
pitches the tail down, the same way a real car's aero balance would.

**Speed/airflow dependence**: downforce scales with the square of the car's
own forward speed (the forward component of velocity, signed but squared, so
reversing does not flip or cancel it); drag scales with the square of the
car's own TOTAL speed (the full 3D velocity vector's magnitude — the same
quantity the pre-existing global `AIR_DRAG` constant always used, unchanged
by this ticket). Downforce is exactly zero at a standstill and stable there
(no discontinuity, no division); drag is zero below its 0.5 m/s dead band
rather than merely at zero — see "Exact applied formulas" above.

Downforce is applied purely vertically (world-down), drag strictly opposing
the full velocity vector. On ordinary horizontal travel, a purely vertical
force does no work on the car's own forward motion, so downforce adds no
energy there — but world-down is **not** generally perpendicular to velocity
on a slope or while airborne, so on sloped or vertical travel downforce can
genuinely add or remove vertical kinetic energy, exactly the way gravity
already does; that is expected real physics, not an injected-energy bug.
Drag, by contrast, always opposes the body's own velocity directly, so it
can only remove kinetic energy, never add it, regardless of travel direction.

Example, a front-heavy drift car with a modest rear wing and slightly more
drag than stock:

```json
{
  "wing": {
    "frontDownforceCoeff": 1.5,
    "rearDownforceCoeff": 0.8,
    "dragCoeff": 0.6
  }
}
```

### Reserved for downstream tasks

The schema reserves four more top-level keys for the sibling tasks that own
those systems; this loader tolerates them existing in a file today (Gson
ignores any key with no matching field) but does not read, validate or apply
any of them — adding a field under one of these sections with no behaviour
behind it would itself be "unused config fields," which this ticket's
acceptance explicitly rules out. Each task adds its own fields here when it
implements real behaviour, rather than inventing a parallel file or format:

| Key | Owner |
|---|---|
| `tireKit` | MINECRAFT-445 (suspension/wheel geometry) |
| `diffuser` | MINECRAFT-447 (orientation assists) |
| `computer` | MINECRAFT-448 (assists/auto-shift) |
| `engine` | MINECRAFT-449 (engine limits/turbo/nitrous/ballast) |

## Standard vs. sport

No existing car in this roster is a "sport" variant of another; this ticket
does not add one. Per the inherited story spec, a future sport variant must
reuse the same car's engine/hardware fields (outside this schema's reach
entirely) and differ only through `computer`/auto-shift/assist settings
(MINECRAFT-448's section above) — never through this file's own fields.
