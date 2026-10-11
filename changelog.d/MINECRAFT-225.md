bump: minor

### Added
- **Client-side skid mark decals from wheel slip (MINECRAFT-225).** Each car wheel now lays a fading
  dark decal on the ground once `CarEntity#clientSlip()` (the same signal `CarSoundsClient` already
  uses for the skid sound) crosses a threshold and the car is on the ground. Marks are spaced along
  each wheel's path, fade out and are removed after a few seconds, and are capped per wheel and across
  the whole client, so render cost stays bounded regardless of how long a car slides. Purely visual:
  no new synced data, no server tick cost, no change to terrain wear (`SlipReporter`) or existing
  sound. Decision logic (`SkidMarkMath`, `SkidMarkRingBuffer`) is pure and unit tested; rendering
  (`SkidMarkRenderer`) and per-wheel tracking (`SkidMarksClient`) are client-only.
