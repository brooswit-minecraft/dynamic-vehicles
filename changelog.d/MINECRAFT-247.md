bump: patch

### Added
- **Boundary test coverage for tire dust/exhaust triggers, auto-headlight day/night switching, and impact
  thresholds** (MINECRAFT-247, verifying MINECRAFT-232): added exact-threshold tests to
  `CarEffectsMathTest` (`exhaustAmount`/`dustAmount` right at `MIN_EXHAUST_SPEED`/`MIN_DUST_SPEED`/the
  slip-alone threshold), `HeadlightMathTest` (`isDark` exactly at dusk/dawn and at the 50% rain
  cutoff), and `CollisionMathTest` (`breakChance` exactly at `collisionMinSpeed`/`collisionMaxHardness`).
  Verification found tire dust, exhaust/smoke, headlamps and impact block-break all WORKS by code
  reading — these boundary tests lock in behavior that was already correct but previously only
  exercised away from its edges. `smokeEnabled`/`wearEnabled` and their strength multipliers (new scope
  moved from MINECRAFT-234) already default to on and are honoured by both the kinematic and Sable
  physics paths; no production behavior changed.
