bump: patch

### Fixed
- Self-build campfire eligibility now applies the level's current sky darkening to raw sky light when deciding whether a position is dark enough at night, instead of comparing raw propagation light alone (which never reads as dark under open sky regardless of time of day).
