bump: patch

### Fixed
- **`/dvflat` no longer rejects first entry to the flat garage** (MINECRAFT-406): the landing
  search's collision query fetched the destination chunk without forcing it to load/generate,
  so on a column nobody had ever visited (the first `/dvflat` into a fresh `flat_garage`
  dimension) the search read every Y, including the floor, as "unobstructed" and always failed
  with "Could not find a safe spot to land in the flat garage." The destination chunk is now
  force-loaded before the search runs.
