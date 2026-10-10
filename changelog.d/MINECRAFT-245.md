bump: patch

### Added
- Internal: per-vehicle tuning data model foundation (MINECRAFT-227) -- LSD lock percentage, front/rear torque split, torque-vectoring gain, camber and toe, grouped into a new `VehicleSpec.VehicleTuning` and threaded through to `WheelMath.tire`'s physics call site. Data and plumbing only: no behaviour change, nothing reads these values yet, and every existing vehicle carries the identity tuning.
