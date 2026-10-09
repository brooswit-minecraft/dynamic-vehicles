bump: minor

### Added
- Multi-seat specs for four existing vehicles (MINECRAFT-182/170), on the shared seat base
  (MINECRAFT-172/169): `drift_car` and `trophy_truck` now carry 2 seats, `car` and `truck` now carry
  4 (2+2); the driver stays seat 0 at its original position. Entity/item ids, registration, and
  every non-seat tuning field are unchanged. Passengers add no mass to the physics simulation.
