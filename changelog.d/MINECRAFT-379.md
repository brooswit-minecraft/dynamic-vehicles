bump: minor

### Added
- Pathfinder region/circuit core (`pathfinder` package): deterministic 512x512-block region identity, Chebyshev ring enumeration, and a spatially-coherent circuit ordering, producing the designed 8/16/24/32-region circuits for levels 2-5 and a confined placeholder for level 1. A route is a pure function of origin region + level, so it is identical across villagers, retries, reload and multiplayer sessions.
- `PathfinderProgress`: server-authoritative, in-order checkpoint validation with out-of-order rejection, an overall completion time limit, and a closing leg back to the first checkpoint. Not yet wired to any profession, block, item, HUD or persistence — see MINECRAFT-379.

No gameplay-visible change yet: nothing in this slice is registered with a profession, block, or player-facing system.
