bump: minor

### Added
- **Motorboat, Chopper and Biplane** (MINECRAFT-380): three new drivable/flyable vehicles alongside the
  existing car roster. None of the three carry CarEntity's Sable rigid body or wheel physics; each is a
  small, self-contained `SimpleVehicleEntity` with its own deterministic movement model, server- and
  client-identical (the same split vanilla's own Boat uses), reading `rider.zza`/`rider.xxa` for
  throttle/steer exactly as `CarEntity` already does, plus the pilot's own look pitch for climb/dive on
  the two aircraft:
  - **Motorboat**: floats to the surface of whatever water column it is over, throttle and rudder steer
    it on the water; settles under gravity when beached or airborne.
  - **Chopper**: rotor-style hover and directional flight &mdash; forward/back thrust and yaw from the
    stick, climb/dive from the pilot's own look pitch (level to hover).
  - **Biplane**: taxis on the ground, picks up speed, then climbs once past its takeoff speed; falling
    back below its stall speed while airborne lets gravity take over instead of holding altitude for free.
  Each is spawned by its own placement item (same pattern as the car's), renders a recognizable
  vanilla-block silhouette (no new texture assets, same technique `CarRenderer` already uses for every
  car), and gets entry/exit, a single driver seat, and save/load through the entity's own standard
  position/rotation persistence and tracking &mdash; no new network payload needed.
