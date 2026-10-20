bump: minor

### Added
- **Two-rate suspension damping and bump stops** (MINECRAFT-445, first slice): a car's
  `config/dynamicvehicles/cars/<id>.json` can now carry a `tireKit` section that damps the
  compression and rebound strokes independently, each split into a slow rate (up to a configurable
  knee speed) and a fast rate beyond it, plus a progressive bump stop past a configurable amount of
  compression travel. The existing flat `dampingRate` key keeps working and is now specifically the
  slow-compression rate: every new field defaults to it, so a car with no `tireKit` damps at one
  single rate exactly as before — bit for bit, not merely to a tolerance, which matters because a
  last-bits difference in a car's spawn-settling transient is enough to change a chaotic rigid-body
  outcome dozens of ticks later. Every car on the roster ships with that identity tuning, so stock
  driving feel is unchanged. Out-of-range values are clamped and non-finite ones fall back to stock,
  the same as every other field in this format. See `docs/per-car-config.md` for units, ranges and
  defaults, including which parts of MINECRAFT-445's scope (caster, roll center) are **not** in this
  section yet.
- **Rear-wheel steering** (MINECRAFT-445): a car's `tireKit` section can now set `rearSteerRatio`, the
  fraction of the front wheels' steer angle applied to the rear wheels. Negative steers the rear against
  the front to tighten a turn (low-speed four-wheel steering); positive steers it with the front. The
  angle rotates each rear tire's own forward axis in the production tick, so it changes that tire's force
  directions rather than only its rendered pose. `0.0` is the default and every stock car's value, so the
  rear axle stays exactly straight — bit for bit what the code did before the field existed — unless a
  file opts in. Out-of-range values are clamped and non-finite ones fall back to stock, as everywhere
  else in this format.
- **Tire pressure** (MINECRAFT-445): a car's `tireKit` section can now set `tirePressureBar`. Below the
  nominal 2.2 bar a tire buys grip and pays for it in rolling drag; above it, the reverse — the two
  effects deliberately pull opposite ways, so pressure is a trade-off rather than a free win, and grip
  responds much less than proportionally while rolling drag responds in full. Both effects are applied in
  the production tick (both grip sites and the rolling-resistance site), and both multipliers are exactly
  1.0 at nominal, so every stock car's grip and rolling resistance are bit-for-bit unchanged. A
  non-positive or non-finite pressure can never reach the friction circle: the loader clamps to a positive
  range and the multipliers themselves fall back to neutral.
- **Anti-dive and anti-squat** (MINECRAFT-445): a car's `tireKit` section can now set `antiDive` and
  `antiSquat`, each the fraction of a wheel's longitudinal force carried into the chassis directly rather
  than through a moment arm about the body centre — which is what makes a real car dive under braking and
  squat under power. Anti-dive applies while the longitudinal push a wheel actually gives the chassis (its
  tire force plus any wheel-spin recovery) opposes the car's motion and anti-squat while it drives it,
  chosen per wheel, so a braking wheel resists dive even mid-corner. Only that longitudinal force moves;
  the tire's cornering force keeps acting where it always did. Both are `0.0` for every stock car, and
  the blend returns the contact-patch height bit-for-bit at `0.0`, so no existing car's pitch behaviour
  changes.
