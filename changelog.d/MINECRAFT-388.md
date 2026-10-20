bump: minor

### Added
- Dynamic Towns: deterministic per-region town planning (town centre, four road edge points
  agreed with neighbouring regions, residential/commercial/industrial zoning relative to roads,
  storehouse site and variant), a town-level abstract resource store (food/stone/wood) fed by
  generalized Builder-style donations and a small passive trickle, resource-gated bottom-up
  construction at any villager-claimed campfire (never an unclaimed one, and never charging
  resources it doesn't have), and a once-only campfire+villager bootstrap at each region's
  designated centre when no vanilla village already exists there.
- Dynamic Towns build their planned roads, and give every resident villager a bed and workplace
  from the fixtures the town itself built - a player's own bed or workstation, even one placed
  where the town once built something else, is never taken over.
