bump: patch

### Changed
- Delivery rewards are now calculated as `(rewardBase + distance × rewardPerBlock) × dangerMultiplier`. New server config key `rewardBase` defaults to 2.0; the distance rate is reduced to 0.01 and the maximum danger multiplier to 2.0, bringing long-haul payouts to sensible values while retaining higher rewards for riskier routes.
