bump: patch

### Fixed
- Dynamic Vehicles 0.15.0 refused to load next to the pack's Dynamic Atmosphere 0.20.2-alpha.1, because that version sorts below 0.20.2. The optional Dynamic Atmosphere range is now `[0.20.2-alpha.1,)`.
