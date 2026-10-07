bump: minor

### Added
- Headlights (MINECRAFT-66): visual-only lamps and a faint beam. Press H in a car to cycle auto, on, off; auto is on at night and in rain. Client config `headlights` and `headlightBeamLength`.
- Car exhaust and dust through Dynamic Atmosphere: exhaust while on the throttle and moving (never when standing still), dust at speed or while sliding, from behind the car, at most one of each per `smokeIntervalTicks`. Server config `smokeEnabled` and `smokeStrength`. Needs Dynamic Atmosphere 0.20.2 or later (optional).
- Cars wear the ground under their wheels, more while accelerating (and some while braking hard), even with world erosion off. Server config `wearEnabled` and `wearStrength`. Needs Dynamic Terrain 0.21.0 (`vehicleErosion`).

### Changed
- Requires Dynamic Terrain 0.21.0 or later.
