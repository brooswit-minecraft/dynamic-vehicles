bump: minor

### Added
- Shared multi-seat vehicle base (MINECRAFT-172/169): `VehicleSpec` now carries a seat list (any
  number of seats, an 8-seat bus included), each with its own attachment/camera point and dismount
  point. Boarding fills the lowest-numbered free seat; seat 0 is always the driver and the only
  seat that can steer. A multi-seat vehicle refuses to let a new rider board while its horizontal
  speed is above a small threshold (today's single-seat vehicles are never speed-gated, same as
  before); exiting is never speed-gated, on any vehicle, so a rider is never trapped. `CAR`,
  `TRUCK`, `TROPHY`, `DRIFT` and `MUSCLE` are unchanged and still single-seat.
