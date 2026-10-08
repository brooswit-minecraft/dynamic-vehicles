bump: minor

### Changed
- Tire forces now act at each wheel's contact point on the ground (MINECRAFT-70), not at body height, so braking, acceleration and cornering shift weight between the wheels. Anti-roll bars (one per axle, stiffness `antiRollRatio` x the wheel spring rate, default 1.0) keep the car upright. Config `tireForceAtContact` (default on) falls back to the old body-height application.
