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
| `driftRearGripScale` | A drift-tuned vehicle's rear tire grip (mu) as a fraction of its front's. Lower = a looser, more oversteer-prone rear end. | 0.88 | 0.0–1.0 |
| `driftSlipAngleThreshold` | Lateral slip speed (m/s) below which a drift-tuned tire grips at its full (scaled) mu; beyond it, grip starts falling off toward the floor `1 - driftGripFalloff` sets. Lower = slides start sooner and more easily. | 3.5 | 0.01–50.0 |
| `driftGripFalloff` | Fraction of grip a drift-tuned tire can lose once sliding well past `driftSlipAngleThreshold`. 0 disables the falloff curve entirely (grip stays at the plain scaled mu regardless of slip); higher makes a slide easier to start and harder to simply coast out of. | 0.15 | 0.0–1.0 |
| `driftHandbrakeRearGripCut` | Extra multiplier on a drift-tuned rear tire's handbrake lateral-grip cut, layered on top of the cut every vehicle already gets from pulling the handbrake (`SableCarBody`'s own `lateralScale`). 1.0 = no extra cut; lower = a sharper handbrake-induced oversteer. | 0.8 | 0.0–1.0 |
| `driftThrottleBite` | Fraction of a drift-tuned rear tire's grip given up to the drive force's own share of the friction circle — friction-circle style throttle-induced oversteer, distinct from (and on top of) the plain friction circle every vehicle already has. 0 disables it. | 0.2 | 0.0–1.0 |
| `driftCounterSteerAssist` | Fraction of the grip lost to `driftGripFalloff` that is restored when the driver steers into the slide (the classic countersteer). 0 = no recovery assist at all (a slide, once broken away, never comes back under this knob alone); 1 = a full countersteer input fully restores grip. | 0.8 | 0.0–1.0 |

### MINECRAFT-210: retuned between muscle (identity) and the old drift values

The six defaults above were retuned (MINECRAFT-207/MINECRAFT-210) to make the drift car feel a
little more muscle-like — still clearly a drifter, but closer to muscle's planted, easy-to-catch
feel than the old values were. Each new value sits strictly between `VehicleSpec.TireTuning
.IDENTITY` (muscle's tuning: `1.0, 999.0, 0.0, 1.0, 0.0, 0.0`) and the old drift value, closer to
muscle than the arithmetic midpoint — except `driftCounterSteerAssist`, explained separately below.

| Field | Muscle (identity) | Old drift | New | Why |
|---|---|---|---|---|
| `driftRearGripScale` | 1.0 | 0.72 | 0.88 | Less front/rear split than before — a looser rear than muscle, but noticeably more planted than the old drift car. |
| `driftSlipAngleThreshold` | 999.0 (never falls off) | 1.2 | 3.5 | A modest bump, deliberately nowhere near muscle's effectively-infinite 999.0: the threshold is a slip *speed*, not a linear dial, so the arithmetic midpoint of 1.2 and 999.0 would be an absurd value. 3.5 lets the tire grip a little longer before breaking away than the old 1.2 did, without approaching muscle's "never breaks away" behaviour. |
| `driftGripFalloff` | 0.0 (no falloff) | 0.45 | 0.15 | A shallower grip-loss curve once sliding — a slide still happens and still has to be managed, just less severely than before. |
| `driftHandbrakeRearGripCut` | 1.0 (no extra cut) | 0.5 | 0.8 | A gentler handbrake-induced oversteer than the old car's sharp cut, while still giving a handbrake turn more bite than muscle's unmodified rear. |
| `driftThrottleBite` | 0.0 (no extra bite) | 0.5 | 0.2 | Less throttle-induced oversteer than before — power-on slides are still possible, just less eager to start than the old tuning's. |
| `driftCounterSteerAssist` | 0.0 | 0.6 | 0.8 | **Deliberate deviation from the identity-to-old range** (confirmed by MINECRAFT-207's owner): the story's framing is "higher = more muscle-like/easier recovery", but identity's `0.0` reflects *absence of a slide to recover from*, not an "easier" endpoint on the same scale as the other five fields — a slide-free car has no countersteer assist to speak of, it simply never needs one. Reading `0.0` as an "easy" endpoint and picking a value strictly between it and the old `0.6` would, perversely, *reduce* recovery assist and make the car harder to catch than before, the opposite of "more muscle-like". So this field is tested and set the other way: strictly greater than the old drift value (`0.6`), capped at CarConfig's own upper bound (`1.0`). `0.8` gives a noticeably easier recovery than the old car's `0.6` without maxing out the knob. |

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
