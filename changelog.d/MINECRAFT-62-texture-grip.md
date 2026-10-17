bump: minor

### Added
- Tire grip and rolling resistance now also respond to per-texel texture detail (Dynamic Terrain's canonical-texture contact-patch evidence, pinned to terrain 0.28.0), on top of the existing block-level surface model: a busier texture costs a little peak grip and adds a little rolling resistance, bounded to a small refinement rather than a replacement for the block-level values.
