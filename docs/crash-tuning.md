# Vehicle crash tuning

Vehicle block crashes are server-authoritative and read their values live from
`dynamicvehicles-server.toml`; an edited value applies to the next collision
without a restart. The existing `collisionBreaking`, `collisionMinSpeed`, and
`collisionMaxHardness` values still decide whether a particular block is ever
eligible.

`collisionSevereSpeed` (m/s, default `16.0`) enables severe traversal. A hit
at or above it can consider up to `collisionSevereMaxBlocks` (default `3`,
range `1..8`) adjacent front blocks. A hit at or above `collisionMinSpeed` but
below this threshold remains a one-block impact. Work is bounded to that
budget and unloaded chunks are never queried or loaded.

`collisionBreakSpeedScale` (default `0.3`, range `0..1`) is the fraction of
horizontal momentum retained after each actual destruction. The speed used for
the next block's eligibility and break chance is reduced by the same factor,
so a crash naturally stops when it falls below `collisionMinSpeed`. `0` stops
at the first broken block; `1` deliberately retains all collision momentum.

Protected/unbreakable blocks, block entities, fluids, and passable glass are
not broken. Dirt and sand remain reported to Dynamic Terrain, which remains
the material authority; this route never destroys them directly. The ordinary
truck retains its bamboo-only special interaction. The collision cooldown is
still the single per-impact gate, preventing repeated reports across ticks.
