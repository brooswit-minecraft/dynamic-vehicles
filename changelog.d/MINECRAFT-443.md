bump: minor

### Added
- **Per-car config files** (MINECRAFT-443): an optional `config/dynamicvehicles/cars/<id>.json`
  per existing car, read once (before any level loads) at server start, overrides that car's ride
  height, damping rate, anti-roll scale, rolling-resistance scale, handbrake-strength scale, and
  its LSD lock/front-rear torque split/vectoring gain/camber/toe tuning. A missing file, a missing
  field, or a file that fails to parse leaves that car on its exact stock default (no file at all
  is byte-for-byte unchanged from before this ticket); an out-of-range value is clamped, and a
  non-finite value falls back to stock. The LSD/split/vectoring/camber/toe values only affect a
  car the first time it is ever saved (the pre-existing per-entity player-tuning persistence keeps
  winning for any already-saved car). See `docs/per-car-config.md` for the full schema, units and
  ranges, including the five sections (`tireKit`, `wing`, `diffuser`, `computer`, `engine`)
  reserved for later tickets to fill in.
