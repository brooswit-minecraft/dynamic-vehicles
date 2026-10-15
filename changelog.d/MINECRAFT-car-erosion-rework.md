bump: minor

### Changed
- Tire wear now adds to real slip instead of being hidden by it: the acceleration/braking wear term is added on top of the tires' real slip (scaled by `WEAR_SCALE`, 0.3), so burnouts, drifts and locked braking clearly wear the ground more than plain driving. `wearMultiplier` now defaults to 5.0 (was 2.5), and wheel load matters more with Dynamic Terrain's new 300 kg reference wheel load.
- A collision reports to Dynamic Terrain like one wheel taking the hit (a quarter of the car's mass) and at no more than 10 m/s of slip, instead of the whole car at its full impact speed, so one bump no longer outweighs a sustained burnout.
- Sable is now a required dependency (README, `neoforge.mods.toml`).

### Removed
- The old kinematic car model, its `useSablePhysics` config option, the `/dvspin` debug command and the `CarPhysics.step` physics with its tests. The car is always a Sable rigid body.
