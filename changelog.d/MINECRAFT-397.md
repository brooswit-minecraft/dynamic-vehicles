bump: minor

### Added
- **A separate superflat vehicle dimension with a there-and-back command** (MINECRAFT-397): `/dvflat`
  teleports a player to a new `dynamicvehicles:flat_garage` dimension (vanilla's default superflat
  preset - bedrock, 2 dirt, grass, reusing `minecraft:overworld`'s dimension type so day/night and build
  height stay normal), and running it again sends the player back to exactly where they left from. Each
  player's departure dimension and position/facing is remembered server-side and per player, so
  multiplayer return state never crosses players; if the remembered location or its dimension is gone,
  the player lands at the overworld's spawn point instead of being trapped or placed inside terrain. Both
  the garage arrival spot and the return/fallback spot are re-checked against actual current terrain at
  teleport time and relocated to the nearest clear two-block gap if a block has since been placed there;
  if no safe spot can be found at all, the teleport fails outright and (for a return trip) the stored
  return location is kept rather than consumed. The Overworld and all existing worlds, dimensions and
  player data are untouched.
