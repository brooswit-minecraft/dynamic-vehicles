bump: minor

### Added
- **Lava and fire damage for vehicles** (MINECRAFT-385): real lava contact and being actually on fire
  both now damage a vehicle through the existing health system, including from full health -- not only
  the pre-existing low-health self-burning. Lava ignition and external ignition (a flame arrow, a fire
  charge) share the same single per-tick damage call as the existing self-burn, so overlapping sources
  never double-damage; a healthy vehicle's own fire-tick countdown still expires naturally instead of
  being kept alive forever, and entering water extinguishes the fire and stops the damage under the
  game's established extinguishing rule.
