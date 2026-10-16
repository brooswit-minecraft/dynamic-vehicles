bump: minor

### Added
- Vehicles feel their environment: pass-through foliage (short/tall grass,
  ferns, vines, etc. -- anything with no collision shape that is not air)
  adds a slight, material-specific resistance as a wheel drives over it,
  weighted by the block's own hardness; air adds none. Water substantially
  counteracts gravity and adds drag; lava gives more buoyancy and more drag
  than water. New `foliageResistance` and `fluidEffects` config multipliers
  (both default `1.0`, `0.0` restores the old behaviour).
