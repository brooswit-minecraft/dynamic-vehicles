# Car tire tuning

Covers the drift-tuned tire model (MINECRAFT-144, story MINECRAFT-141): the pure-function
`DriftTireModel`, layered on top of `WheelMath`'s own friction-circle tire, and the six config
keys that tune it. All six live in `CarConfig`, written to `dynamicvehicles-server.toml` on the
server, in the style of that file's existing `tireForceAtContact` / `antiRollRatio`.

These keys only ever affect a vehicle whose `VehicleSpec` has already opted in with a
non-identity `tireTuning()` — currently `VehicleSpec.DRIFT` only. `VehicleSpec.CAR`, `TRUCK` and
`TROPHY` all carry `VehicleSpec.TireTuning.IDENTITY` hard-wired in code, so none of the values
below can ever change their behaviour, regardless of what an operator sets here. The defaults
below match `VehicleSpec.DRIFT`'s own `tireTuning()` numbers exactly, so a fresh config changes
nothing about the drift car's behaviour either — these are knobs for an operator who wants to
retune it, not a feature that is off by default.

| Config key | What it does | Default | Range |
|---|---|---|---|
| `driftRearGripScale` | A drift-tuned vehicle's rear tire grip (mu) as a fraction of its front's. Lower = a looser, more oversteer-prone rear end. | 0.72 | 0.0–1.0 |
| `driftSlipAngleThreshold` | Lateral slip speed (m/s) below which a drift-tuned tire grips at its full (scaled) mu; beyond it, grip starts falling off toward the floor `1 - driftGripFalloff` sets. Lower = slides start sooner and more easily. | 1.2 | 0.01–50.0 |
| `driftGripFalloff` | Fraction of grip a drift-tuned tire can lose once sliding well past `driftSlipAngleThreshold`. 0 disables the falloff curve entirely (grip stays at the plain scaled mu regardless of slip); higher makes a slide easier to start and harder to simply coast out of. | 0.45 | 0.0–1.0 |
| `driftHandbrakeRearGripCut` | Extra multiplier on a drift-tuned rear tire's handbrake lateral-grip cut, layered on top of the cut every vehicle already gets from pulling the handbrake (`SableCarBody`'s own `lateralScale`). 1.0 = no extra cut; lower = a sharper handbrake-induced oversteer. | 0.5 | 0.0–1.0 |
| `driftThrottleBite` | Fraction of a drift-tuned rear tire's grip given up to the drive force's own share of the friction circle — friction-circle style throttle-induced oversteer, distinct from (and on top of) the plain friction circle every vehicle already has. 0 disables it. | 0.5 | 0.0–1.0 |
| `driftCounterSteerAssist` | Fraction of the grip lost to `driftGripFalloff` that is restored when the driver steers into the slide (the classic countersteer). 0 = no recovery assist at all (a slide, once broken away, never comes back under this knob alone); 1 = a full countersteer input fully restores grip. | 0.6 | 0.0–1.0 |

## How the model composes these

`DriftTireModel.effectiveMu` applies them in order, each a no-op at its identity value (so the
whole chain is skipped entirely for a non-opted-in vehicle, see below):

1. **Front/rear split** (`driftRearGripScale`): the rear tire's base mu is the surface's mu times this scale; the front's is untouched.
2. **Slide threshold / falloff** (`driftSlipAngleThreshold`, `driftGripFalloff`): below the threshold, grip stays at the split base mu; beyond it, grip saturates down toward `base mu * (1 - driftGripFalloff)` — a floor, never zero, so a sliding tire can always be caught.
3. **Throttle bite** (`driftThrottleBite`, rear wheels only): shrinks grip further by how much of the (un-tuned) friction circle the drive force alone is already using.
4. **Countersteer recovery** (`driftCounterSteerAssist`): when the driver's steer input points the same way the tire is sliding (the classic countersteer), grip is interpolated back up toward the split base mu.

`SableCarBody.tick` only ever calls into this chain (via `DriftTireModel.tire`) when the
vehicle's own `VehicleSpec.tireTuning().isIdentity()` is false — a property of the spec, not of
config, so `CAR`/`TRUCK`/`TROPHY` call `WheelMath.tire` directly, exactly as before this model
existed.
