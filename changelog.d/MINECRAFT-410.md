bump: minor

### Changed
- **Motorboat buoyancy and steering rebuilt on rigid-body physics** (MINECRAFT-410): the motorboat now
  shares the car's Sable rigid-body engine instead of its old scalar single-water-column, direct-yaw-
  assignment model. Four independent hull support points (front/rear x port/starboard) each sample their
  own local water surface and apply a bounded buoyant force, so differential immersion produces real
  pitch/roll/heave restoring torque rather than a single whole-body spring. The rear motor's thrust is
  applied at its real off-center mount, fully in the hull's own local frame (so it tracks the boat's actual
  pitch/roll, not just yaw) and rotated by the steering input, producing turning through real torque rather
  than a direct heading write. Separately tunable longitudinal and lateral hydrodynamic drag replace the
  old isotropic drag. All tuning is exposed through a new `motorboat-server.toml` config, mirroring the
  car's own config pattern.
