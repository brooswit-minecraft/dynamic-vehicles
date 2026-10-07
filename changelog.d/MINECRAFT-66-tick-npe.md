bump: patch

### Fixed
- The server crashed ("Ticking entity", NullPointerException in Sable `BoxPhysicsObject.updatePose`) when a car's Sable physics body had been released. The car now drops the dead body and builds a new one on the next tick.
