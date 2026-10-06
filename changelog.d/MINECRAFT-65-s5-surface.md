bump: minor

### Added
- **Sable rigid-body car, surfaces and erosion (E8 S5).** Each tire's grip and rolling resistance now come from the block under it through Dynamic Terrain's surface model (`Surfaces.at`): ice is slippery, sand and snow are draggy, gravel and grass sit in between, stone and pavement grip. Slip still reports to `TireSlip`, so burnouts and drifts on loose ground wear it (when erosion is enabled). Requires Dynamic Terrain 0.20.0 or newer.
