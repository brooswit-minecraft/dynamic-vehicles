bump: patch

### Changed
- Corrected the `force <= 0` guard comment above the skip in `SableCarBody`'s wheel tick: `WheelMath.tire()` returns the four-component `Tire(0, 0, 0, 0)`, not the previously-stated three-component form, and the skip only remains behaviour-equivalent to letting the wheel through for impulses and reported slip — it now also deliberately freezes `wheelSpin[wheel]` at its last value (an airborne/zero-load/raycast-miss wheel keeps rotating with no driveline torque applied, so it neither snaps to ground speed nor free-spins). No logic change.
