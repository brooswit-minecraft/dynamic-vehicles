bump: minor

### Changed
- Each wheel now tracks a real spin rate (rad/s) as state, advanced every (sub-)step from the driveline's commanded force against the tire's actual delivered reaction (MINECRAFT-118/MINECRAFT-73): wheelspin and lock-up now come from that torque balance rather than being inferred from body velocity. Tire grip (the friction limit) already scaled with each wheel's own vertical load; this change adds unit tests pinning that scaling and its zero-load limit, and ties the two together (a zero-load wheel's tire reaction is always 0, so it spins freely under its own commanded force). The pre-existing, separately tick-dt-evaluated reported slip (`SlipReporter`/`wearSlip`/`lastSlipSpeed`) is unchanged.
