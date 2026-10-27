bump: patch

### Fixed
- Dynamic Towns building terrain preparation now safely rolls back failed clears or fills without
  advancing construction or consuming resources.
- Add regression coverage for persistence of an explicitly quarantined post-write building cell;
  process-crash recovery at that boundary remains out of scope for this bounded test seam.
