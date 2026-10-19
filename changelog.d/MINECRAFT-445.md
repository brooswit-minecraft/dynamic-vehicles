bump: minor

### Added
- **Two-rate suspension damping and bump stops** (MINECRAFT-445, first slice): a car's
  `config/dynamicvehicles/cars/<id>.json` can now carry a `tireKit` section that damps the
  compression and rebound strokes independently, each split into a slow rate (up to a configurable
  knee speed) and a fast rate beyond it, plus a progressive bump stop past a configurable amount of
  compression travel. The existing flat `dampingRate` key keeps working and is now specifically the
  slow-compression rate: every new field defaults to it, so a car with no `tireKit` damps at one
  single rate exactly as before — every car on the roster ships with that identity tuning, so stock
  driving feel is unchanged. Out-of-range values are clamped and non-finite ones fall back to stock,
  the same as every other field in this format. See `docs/per-car-config.md` for units, ranges and
  defaults, including which parts of MINECRAFT-445's scope (caster, roll center, anti-dive/anti-squat,
  tire pressure, rear steering) are **not** in this section yet.
