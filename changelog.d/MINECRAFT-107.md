bump: minor

### Added
- Dispatcher destination-offer generation (MINECRAFT-107/120): a Dispatcher fills its
  offer slots by searching village placement regions outward in rings via
  `VillagePlacementService`, spilling into farther rings until slots are filled or the
  villager-level-gated range is exhausted. Each offer carries a destination identity,
  recoverable location, approximate distance, a danger value rolled independently of
  distance, a distance-and-danger-scaled reward, and a time allowance. Offer-generation
  decisions (`OfferConfig`, `OfferGenerator`) are plain, Minecraft-free classes with
  seeded-RNG unit test coverage; `DispatcherOfferService` is the thin Minecraft-facing
  layer that calls them. Ring counts, slot counts per level, danger range, and the
  reward curve are server config values (`DispatcherOfferConfig`) with placeholder
  defaults — tuning is MINECRAFT-113's job. No UI, no contract state yet; nothing calls
  this service until a later slice does.
