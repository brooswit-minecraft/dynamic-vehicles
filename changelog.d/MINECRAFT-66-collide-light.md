bump: minor

### Added
- Collisions (MINECRAFT-66): a hard hit has a chance to break the block ahead, more likely when faster and for softer blocks; a block that survives is eroded. Never breaks unbreakable blocks, block entities or fluids, at most one block per impact. Config `collisionBreaking`, `collisionMinSpeed` (8 m/s), `collisionMaxHardness` (3.0).
- Real headlamp light: one invisible light block a few blocks ahead of a driven car with the lamps on, placed only into air and removed when the lamps go off, the car unloads or is removed; every one is recorded and any leftovers are swept on server start. Config `headlampLight`.
