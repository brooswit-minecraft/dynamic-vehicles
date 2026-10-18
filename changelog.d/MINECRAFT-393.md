bump: minor

### Changed
- A successful delivery's destination no longer generates a Trading Post pavilion (shack, floor,
  corner posts, roof, Bell, and eight villagers). It now places a single Dispatch Board and a
  single Dispatcher villager directly on the surface, with no terrain clearing or filling. The
  top of water counts as a surface. A destination column with no usable surface at all leaves the
  delivery active for a later retry instead of forcing a placement.
