bump: minor

### Added
- The first vehicle: a 4-wheel **Car** (creative, Tools & Utilities tab; right-click a block to place it, right-click the car to get in, sneak to handbrake). The rider's forward/back and left/right input drives a kinematic bicycle model with a lateral-grip limit, acceleration, braking and reverse.
- Slip reporting: each wheel reports slip (wheelspin, locked braking, cornering past the grip limit) for the block under it to Dynamic Terrain's `TireSlip`, so burnouts and drifts wear loose ground when Dynamic Terrain's erosion is enabled. The car never modifies terrain itself.
- Placeholder look built from vanilla blocks until a real model exists. Op-only debug command `/dvspin <slip> <ticks>` makes loaded cars report that slip, to try terrain wear without driving.
