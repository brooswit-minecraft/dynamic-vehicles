bump: patch

### Fixed
- Test-only: isolated `SettlementFounderGameTests`' delivery-destination GameTests, which
  previously used fixed destination region keys and could see a stale `ALREADY_FOUNDED` claim
  (and silently skip asserting the board placement) when rerun against a reused local world.
  Each test now draws a fresh region key, and an explicit repeated-arrival regression now covers
  the dedup path directly. No production behavior changed.
