bump: minor

### Added
- **Wing downforce and aerodynamic drag** (MINECRAFT-446): genuine aerodynamic forces in the real
  rigid-body path, distinct from the existing MINECRAFT-184 tire-grip-at-speed multiplier (left
  unchanged). Configurable per car through the reserved `wing` section of a per-car config file
  (MINECRAFT-443): `frontDownforceCoeff`/`rearDownforceCoeff` (N per (m/s)^2, applied as a genuine
  downward impulse at each axle's own mount point, producing a real pitch moment on a front/rear
  split, not just a vertical force) and `dragCoeff` (N per (m/s)^2, the generalized, per-vehicle
  form of the old hardcoded global air-drag constant). Downforce scales with the square of the
  car's own forward speed; drag scales with the square of its total speed (the same quantity the
  old global constant always used), both stable at a standstill. Downforce is applied purely
  vertically, so it adds no energy on ordinary horizontal travel (though, like gravity, it can do
  real work against a vertical velocity component on a slope or while airborne); drag always
  opposes the body's own velocity directly, so it can only ever remove kinetic energy. Every existing car's stock defaults
  (`0.0`/`0.0`/`0.4`) reproduce the exact pre-this-ticket global drag behaviour and no wing
  downforce at all, until an operator opts a car in. See `docs/per-car-config.md`'s new `wing`
  section for the full schema, units, ranges and application timing.
