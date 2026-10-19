bump: minor

### Added
- **Pathfinder missions now share the Dispatcher contract waypoint's presentation** (MINECRAFT-411):
  a world-projected, edge-clamped dot marker at the next checkpoint, with a readable distance
  label and checkpoint count/countdown drawn directly below it - instead of the old fixed
  top-left corner arrow, which required looking away from wherever the player was actually
  navigating. A wrong-dimension attempt shows an explicit top-centre status line instead of a
  misleading projected target. The marker uses a fixed colour (Pathfinder has no danger value to
  reuse Dispatcher's threat colouring for) and its own count/countdown lines, so an active
  delivery contract and an active Pathfinder attempt stay visually distinguishable - and never
  paint over each other - even when both markers land at or near the same screen position.
  Each marker's own panel also stays entirely on screen, including when its edge-clamped dot
  sits right at a screen edge or corner.
- `showLegacyPathfinderHud` client config option (default off) restores the old fixed-corner
  Pathfinder HUD alongside the new marker, mirroring the existing `showLegacyContractHud` toggle.

### Changed
- The waypoint projection/clamping math, the marker/label drawing, the legacy-HUD visibility rule
  and the remaining-time countdown formatting moved into a new shared `hud` package so Dispatcher
  and Pathfinder render through one implementation instead of two independently-maintained ones;
  no behavior change for the existing Dispatcher waypoint or its legacy corner HUD.
