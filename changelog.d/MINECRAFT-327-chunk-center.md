bump: patch

### Fixed
- Builder settlement now centres the replacement house on the campfire's own
  chunk instead of offsetting it a fixed 4 blocks from the campfire, so the
  house always sits in the chunk's dead centre rather than merely staying
  inside the chunk boundary.
- Since the centred house can now contain the originating campfire and the
  Builder standing at it, both are excluded from the pre-placement occupancy
  check so settlement can still succeed, and the campfire is only explicitly
  removed afterwards when it fell outside the new house's own footprint.
- The settling Builder is now relocated to the house's validated-clear
  entrance the moment settlement succeeds, so it is never left standing
  inside a wall or corner post the new house just built through it.
- That exit itself is now preflighted too: a sturdy-but-hazardous block
  (a lit campfire) or another entity already standing there now blocks
  settlement there the same way an unrelated obstacle in the footprint
  does, rather than relocating the Builder onto a tile that was never
  actually checked.
