bump: patch

### Fixed
- Add regression coverage for persistence of an explicitly quarantined post-write building cell across reload and retry; process-crash recovery at that boundary remains out of scope for this bounded test seam.
