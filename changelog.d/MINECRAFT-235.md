bump: minor

### Added

- Completed the first playable transmission vertical slice: explicit keyboard/gamepad reverse and brake-safe gear selection now integrate with the optional, disabled-by-default physical shifter mapping for neutral, reverse, and D1 through D6.
- The Sickos pack integration carries the gear-state safety invariant through the packaged build: braking never selects reverse, and the physical shifter remains inactive until configured.

### Packaging

- The supported Sickos pack flow assembles the MINECRAFT-244 gear-state safety, MINECRAFT-297 physical-shifter, and this integration release notes into the published `.mrpack` artifact.
