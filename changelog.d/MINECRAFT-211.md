bump: minor

### Added
- **Cow auto-boarding (bus only).** An adult cow that isn't leashed now boards the bus on contact -- the
  bus driving into the cow, not the other way around -- into the first free non-driver seat (seat 0, the
  driver's, is never assigned to a mob even when empty). Baby, leashed and hostile mobs never board, and a
  cow never boards if the bus has no free non-driver seat; it is simply left behind, never teleported,
  forced, or despawned. Boarding is detected through the existing per-tick entity-collision check every
  mob already does, not a new scan over loaded mobs. Once the bus has been stopped for a couple of
  seconds, a boarded cow automatically dismounts; a player's own boarding, driving, and manual (sneak-key)
  dismount are unaffected, and the bus still accepts a player driver or passenger exactly as before.
  No other mob type and no other vehicle are in scope yet.
