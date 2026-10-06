bump: minor

### Changed
- **The rendered car now matches its physical body (E8 S7).** One shared `CarGeometry` defines the physics box (1.9 x 1.0 x 3.0 m), the wheel mounts and the ride height; the renderer draws a chassis and cabin that exactly fill the box and four wheels placed where the suspension rays end, so the wheels touch the ground at rest, and it draws the body's own orientation (pitch, roll and yaw) with interpolation.

### Added
- Op debug command `/dtcar debug on|off`: draws each car's physics box (red particles at its eight corners) and where each suspension ray meets the ground (green), so any mismatch between the model and the body is visible in the world.

### Fixed
- A parked car on a slope no longer rolls away: the hold brake now has a stronger gain, so it creeps at under 0.1 m/s on a 17 degree ramp instead of rolling off it.
