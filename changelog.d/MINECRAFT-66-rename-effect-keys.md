bump: patch

### Changed
- The three effect settings were renamed so the stronger defaults reach existing server configs, which keep the old stored values: `wearStrength` is now `wearMultiplier`, `smokeStrength` is `smokeMultiplier` (both default 2.5) and `smokeIntervalTicks` is `smokeEmitTicks` (default 6). Old keys are ignored.
