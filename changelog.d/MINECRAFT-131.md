bump: minor

### Added
- Delivery tuning: three new server config entries — `rewardPayoutFloor` (moved out of a hard-coded literal in `ContractTickHandler`, same default), and two documentation-only placeholders exposing design questions inherited from merged slices without resolving them: `dispatcherAcquisitionCapPerVillage` (default -1/uncapped, not yet enforced) and `dangerRollMode` (default `PER_REGION`, the only implemented mode; `PER_OFFER` is a documented future option).
- `docs/delivery-tuning.md`: every delivery tuning knob with unit, default, range, and what to watch when changing it, plus a dev-client verification checklist collecting unobserved behaviours from slices MINECRAFT-105/109/110/112.

### Changed
- `ContractTickHandler`'s reward payout floor is now read from config instead of a hard-coded `1`; default value and behaviour are unchanged (see `OfferGenerator.payoutAmount`, unit-tested against the old literal).
