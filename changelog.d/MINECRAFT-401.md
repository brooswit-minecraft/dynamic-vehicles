bump: minor

### Added
- **Manual gear ratios and an automatic mode.** D1-D5 now carry distinct drivetrain ratios (D1 strongest/shortest, D5 tallest/fastest); the physical shifter's sixth position selects automatic mode instead of a sixth manual gear, which shifts itself by engine RPM. Engine RPM is now derived from the actual driven-wheel speed through the engaged ratio and final drive, and drives the engine sound (idle/mid/high crossfade and pitch) directly, so a manual or automatic shift is audible immediately instead of only as road speed changes. Debug telemetry (`/dtcar debug`) now reports requested/effective gear, RPM, throttle and every wheel's own spin/linear speed.

### Changed
- The accelerator's drive force is no longer gated to zero while the car is rolling the opposite way: forward torque while rolling backward, and reverse torque while rolling forward, now apply through the normal traction-limited tire force, same as driving in the car's own direction. Nothing teleports velocity -- the chassis still coasts until the applied force actually overcomes its motion.
