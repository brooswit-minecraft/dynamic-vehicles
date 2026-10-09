bump: patch

### Added
- Tests locking in old-format saved-vehicle/spawn-item compatibility with the multi-seat specs from
  MINECRAFT-170/182 (MINECRAFT-171/183): a pre-change entity's NBT (orientation-only, or empty) loads
  without error and its seats come from the spec alone (full 2/4/2/4 seat count, seat 0 as driver),
  and the four entity/item ids (`drift_car`, `car`, `trophy_truck`, `truck`) are pinned against rename.
  No behavior changed; `CarEntity`'s orientation-tag parsing was extracted into the pure, testable
  `OrientationNbt` and the four ids into `VehicleIds` to make this verifiable without a Minecraft/
  NeoForge bootstrap.
