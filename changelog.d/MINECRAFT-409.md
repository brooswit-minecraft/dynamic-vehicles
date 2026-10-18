bump: patch

### Fixed
- **Pathfinder checkpoint arrival that could be missed, and gave no visible confirmation when it
  wasn't** (MINECRAFT-409): the server-side evaluation that credits a checkpoint only sampled a
  player's position once every 20 ticks with no prior-position history, so a player or vehicle
  that entered and left the target chunk entirely between two samples got no credit at all, not
  merely delayed credit. Evaluation now runs every server tick instead, closing that gap at
  negligible cost (the position/chunk check is cheap; the durability-checked save/payout I/O
  still only runs on an actual state transition, unchanged).
- Reaching an intermediate checkpoint now sends a player-visible chat confirmation
  ("Checkpoint reached! (x/y)") in addition to the existing HUD update - previously the HUD count
  silently ticking up (a small corner overlay, easy to miss while navigating) was the only signal
  a checkpoint had registered, which read as "nothing happened" even on a successful credit.
- The HUD's directional arrow now rotates relative to the actual rendered camera orientation
  instead of the player entity's own facing, so a fixed-angle camera view installed by another
  mod no longer points the arrow in a misleading on-screen direction; an ordinary perspective is
  unaffected, since its camera yaw already matched the player's.
- Arrival evaluation reading position every tick (above) no longer also raises the retry rate of
  an already-terminal attempt's durable save when that save is persistently failing - that retry
  keeps its original, much coarser cadence, since retrying a stuck save faster doesn't help and
  only adds synchronous I/O load.
