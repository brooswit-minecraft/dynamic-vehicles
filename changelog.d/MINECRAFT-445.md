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
  defaults, including which parts of MINECRAFT-445's scope (caster, roll center, anti-dive/anti-squat,
  tire pressure) are **not** in this section yet.
- **Rear-wheel steering** (MINECRAFT-445): a car's `tireKit` section can now set `rearSteerRatio`, the
  fraction of the front wheels' steer angle applied to the rear wheels. Negative steers the rear against
  the front to tighten a turn (low-speed four-wheel steering); positive steers it with the front. The
  angle rotates each rear tire's own forward axis in the production tick, so it changes that tire's force
  directions rather than only its rendered pose. `0.0` is the default and every stock car's value, so the
  rear axle stays exactly straight — bit for bit what the code did before the field existed — unless a
  file opts in. Out-of-range values are clamped and non-finite ones fall back to stock, as everywhere
  else in this format.
