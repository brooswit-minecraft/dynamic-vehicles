bump: patch

### Fixed
- **The car did not drive** on a real server: the rider's client treated itself as the car's controller, ignored the server's position updates for it and sent its own unmoved position back every tick, snapping the car back. The car is now server-authoritative (`isControlledByLocalInstance` is false), so W/S/A/D drive it. Verified on a real dedicated server with a real client: before the fix the car moved 0.2 m in 5 s of holding W; after, it accelerates past 16 m/s.
- The handbrake was the sneak key, which is vanilla's dismount key, so braking would have thrown the rider out. The handbrake is now **space**; sneak still gets out.
- The car climbs one-block steps instead of stopping dead at the first ledge.
