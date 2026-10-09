bump: minor

### Added
- Periodic pillager ambush rolls (MINECRAFT-130): while a Dispatcher delivery contract is
  active, the existing contract server sweep now also rolls, on a configurable interval
  (`ambushRollIntervalTicks`, default ~30s), a chance to spawn a pillager encounter near or
  ahead of the traveling player/vehicle, using its current heading. Roll probability and
  encounter size both scale with the contract's own danger value (low-danger contracts stay
  usually quiet; high-danger ones get materially more frequent/larger encounters). Encounter
  mobs are tracked in a durable, persisted id set and cleaned up once they're no longer
  relevant (contract completed/expired, owner logged out, past a hard lifetime backstop) -
  unless another player is nearby, in which case cleanup is deferred so a mob is never
  despawned out from under someone fighting it. The player can keep driving throughout; no
  vehicle immobilization or camera lock. All new config entries live in
  `DispatcherOfferConfig` with starting-point defaults for MINECRAFT-113 to tune.
