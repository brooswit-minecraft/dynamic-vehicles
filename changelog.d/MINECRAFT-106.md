bump: minor

### Added
- Server-side village destination-discovery service (`delivery` package):
  given a level and an origin, enumerates village structure placement
  regions outward in randomized distance rings and returns only confirmed
  generated villages, each with enough stored data to recover its actual
  start location. Nothing player-visible yet; this is the foundation for the
  upcoming Dispatcher villager delivery job system.
