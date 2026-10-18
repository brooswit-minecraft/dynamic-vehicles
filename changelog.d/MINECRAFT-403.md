bump: minor

### Changed
- **Pathfinder checkpoints no longer require safe/accessible terrain** (MINECRAFT-403): a
  checkpoint's deterministic region-center/circle column is used as-is - lava, water, void,
  caves, cliffs and partial-layer terrain are all valid, and a checkpoint is never rejected,
  silently moved to a different column, or made to fail route generation because of what's
  actually there. Reaching the location and making it accessible is the player's job. Arrival now
  means entering the checkpoint's own Minecraft chunk, in the correct dimension, at any Y -
  not a fixed-radius proximity check against a specific block/Y, so an underground or airborne
  player crossing into the target chunk credits the checkpoint exactly like one standing on
  "solid ground" used to. Placement no longer forces terrain generation or edits the world to
  approve a checkpoint.

### Removed
- **`reachRadius` server config option**: superseded by the chunk-based arrival rule above, which
  has no radius to tune.
