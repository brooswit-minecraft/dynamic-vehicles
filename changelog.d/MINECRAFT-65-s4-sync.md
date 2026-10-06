bump: minor

### Added
- **Sable rigid-body car, sync and rendering (E8 S4).** The body's orientation is synced to clients every tick and the renderer interpolates it, so the car visibly pitches, rolls and leans on slopes and over partial layered blocks, with the wheels at the ground. The renderer keeps the old yaw-only drawing for cars using the simple model.
