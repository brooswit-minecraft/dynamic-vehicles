bump: minor

### Added
- **Mob passenger safe ejection on crash/fast movement (bus only).** A mob passenger (cow, today's only
  auto-boarder) is immediately ejected to a safe nearby position -- never inside a block, never
  teleported more than a few blocks -- the instant the bus's own speed spikes past a new crash/fast-
  movement threshold set well above its normal top speed, or the bus is removed from the world for any
  reason (killed, discarded, unloaded). This takes priority over the normal stop-based auto-dismount for
  that tick, and a just-ejected mob cannot immediately re-board while the bus is still that fast. A
  player riding is untouched by this change. A mob that finds no free non-driver seat is left exactly
  where it was, as before -- no teleport, no forced movement, no despawn. Seat 0 (the driver's) continues
  to never be assigned to a mob, including when a player and a mob board in the same tick.
