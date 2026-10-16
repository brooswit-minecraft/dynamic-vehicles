bump: patch

### Fixed
- The Sable car now feels `SurfaceProperties.deformability()` from Dynamic
  Terrain in its rolling resistance, not just `grip()` and
  `rollingResistance()`: a wheel on a deformable (soft/loose) surface sinks
  in and rolls harder, on top of that surface's own rolling-resistance
  coefficient.
