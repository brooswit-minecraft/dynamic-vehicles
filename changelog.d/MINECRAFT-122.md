bump: minor

### Added
- Each wheel's suspension travel, steer angle and spin rate are now synced to clients per-wheel (not a single aggregate), carried on `CarEntity`'s existing synced-data channel and exposed client-side as `CarEntity.wheelTravel/wheelSteerAngle/wheelSpin(wheel, partialTick)`, interpolated between the last two received frames for rendering. No new raycast: suspension travel is the existing single per-wheel raycast result from `SableCarBody.tick`'s pass 1, handed out rather than recomputed. Spin and the reported-slip/N-invariance contract (`slipThisTick`/`lastSlipSpeed`/`wearSlip`/`SlipReporter`) are untouched by this change.
