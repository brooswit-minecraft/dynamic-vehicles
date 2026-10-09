bump: minor

### Added
- Contract lifecycle (MINECRAFT-110): accepting a Dispatcher offer now creates a real,
  server-authoritative delivery contract (source and destination identity, destination
  location, deadline, danger, reward) instead of the previous no-op stub. Only one
  contract can be active per player at a time; a Dispatcher refuses new offers
  server-side with player-visible feedback until the current contract succeeds or
  expires. Contract state persists across logout/relog and server restart. A persistent
  HUD element (direction, remaining distance, remaining time) shows while a contract is
  active, reusing `DangerGauge` and `OfferDistanceFormat` so it never disagrees with the
  offer screen. Arrival uses a tight criterion (the destination's recovered actual
  village start chunk, never the whole placement region) and is evaluated, along with
  deadline expiry, on a coarse server timer rather than only on interaction. Completing a
  contract pays its reward in emeralds; expiring clears it and frees the player to accept
  another. Danger categories (`SAFE`/`LOW`/`MODERATE`/`HIGH`/`EXTREME`) are now
  translated instead of rendered as raw enum names, in both the offer screen and the HUD.

### Fixed
- `DispatcherOfferMenu#stillValid` no longer returns unconditionally true. Server-side
  validity now requires the Dispatcher villager to still exist, be alive, still carry the
  `dispatcher` profession, and be within normal interaction range (8 blocks, mirroring
  vanilla's own default for block-anchored container menus) of the player; acceptance is
  refused server-side otherwise. Previously an accepted offer's menu could be exploited
  by walking arbitrarily far away or by the Dispatcher being killed mid-session.
