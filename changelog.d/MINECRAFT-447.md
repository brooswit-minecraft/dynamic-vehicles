bump: minor

### Added
- **Diffuser KIT orientation assists** (MINECRAFT-447): four distinct, independently configurable
  arcade assists for a car's attitude in the air — roll self-righting (recovers a car banked or rolled
  off upright), pitch/landing help (levels the nose as an estimated time-to-touchdown shrinks), heading
  alignment (holds the heading the car left the ground with, giving authority back as the player
  steers), and trajectory alignment (pulls the nose toward the direction the car is actually
  travelling, and stays out of the way of a deliberate handbrake slide). Each is a bounded,
  time-scaled correction applied as a real rigid-body torque, with its own enable flag, torque bound,
  gain and damping, so a car can opt into any subset. Heading and trajectory alignment are kept
  deliberately separate rather than collapsed into one setting.
- **`diffuser` section in the per-car config file** (MINECRAFT-447): the `diffuser` key reserved by
  MINECRAFT-443 is now live in `config/dynamicvehicles/cars/<id>.json`, with one optional field per
  assist setting. A missing section, or any missing field within it, leaves that car exactly as it is
  today; out-of-range values are clamped and non-finite values fall back to stock, the same as every
  other field in that file. See `docs/per-car-config.md` for the full schema, units and ranges.
- **Diffuser telemetry**: each assist's own last-applied torque is reported under its own key in the
  per-car debug line (`diffuserRoll`, `diffuserPitch`, `diffuserHeading`, `diffuserTrajectory`), kept
  distinct from aerodynamic downforce, which is a grip multiplier rather than a torque.

### Changed
- Every existing car ships with all four assists disabled, so stock handling is unchanged: the assist
  code is skipped entirely for any car that has not opted in, and no car's stock tuning opts in.
