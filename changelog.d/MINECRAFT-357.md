bump: patch

### Fixed
- Builder passive settlement rolls now use persisted elapsed game time (about every 10 seconds) rather than an exact noon tick, so time jumps and reloads cannot silently skip an eligible check or cause an unbounded catch-up burst. Passive rates are conservatively level-scaled at 0% to 0.08% per check; the independent donation-triggered settlement path remains available.
